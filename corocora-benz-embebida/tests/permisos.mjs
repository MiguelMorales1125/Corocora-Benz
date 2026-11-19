// Comprueba la sesión y los permisos de la API de aplicación de cada portal (/api/app, sección 3 y 5
// del contrato): inicio y cierre de sesión, CSRF, permisos por grupo sobre las tareas, aislamiento
// entre clientes y entre portales, y que las API internas exigen la credencial interna.
// Requisitos: los 6 sistemas corriendo (Motor 9000, PortalCliente 9001, PortalTI 9002,
// CentralSys 9003, Banco 9004, Taller-Bodega 9005).
// Uso: npm run permisos
import {
  BANCO, BODEGA, CENTRAL, MOTOR_REST, PORTAL_CLIENTE, PORTAL_TI,
  COOKIE_CENTRAL, COOKIE_CLIENTE, COOKIE_TI,
  Session, anonimo, borrarInstancias, esperar, esperarSistemas, motor, typed
} from './comun.mjs';

const RUN = 'AUTH-' + Date.now().toString(36);
const MENSAJE_CREDENCIAL = 'Credencial interna requerida';

let verificaciones = 0;

function comprobar(condicion, mensaje) {
  if (!condicion) {
    throw new Error('No se cumple: ' + mensaje);
  }
  verificaciones += 1;
}

function comprobarCodigo(respuesta, esperado, mensaje) {
  const detalle = `${mensaje} (esperado ${esperado}, recibido ${respuesta.status} ${JSON.stringify(respuesta.body)})`;
  comprobar(respuesta.status === esperado, detalle);
}

async function iniciarSesion(base, usuario) {
  return new Session(base).login(usuario);
}

/** Valores actuales de una variable en el historial de la instancia (REST del motor). */
async function variableEnMotor(processInstanceId, nombre) {
  const ruta = `/history/variable-instance?processInstanceId=${processInstanceId}&variableName=${nombre}`;
  const lista = await motor('GET', ruta);
  return lista.map((variable) => variable.value);
}

// businessKey de todo lo que la prueba arranca, para borrarlo al terminar.
const instanciasCreadas = [];

// Camunda bloquea unos segundos la cuenta tras una contraseña incorrecta y la bloquea del todo al
// décimo fallo seguido. Por eso el intento fallido usa una cuenta que el resto de la prueba no
// necesita, y al final se entra con la contraseña correcta para poner el contador en cero.
const CUENTA_LOGIN_FALLIDO = 'talento1';
let loginFallidoPendiente = false;

async function entrarTrasElBloqueo() {
  await esperar(async () => {
    const intento = await new Session(CENTRAL).intentarLogin(CUENTA_LOGIN_FALLIDO);
    return intento.status === 204;
  }, `que ${CUENTA_LOGIN_FALLIDO} vuelva a entrar con su contraseña`, 30000);
  loginFallidoPendiente = false;
}

async function sesionYCsrf() {
  // Sin sesión no hay API de aplicación en ningún portal.
  const sinSesion = await new Session(CENTRAL).api('/central/tasks');
  comprobarCodigo(sinSesion, 401, 'sin sesión no hay bandeja');
  comprobar(sinSesion.body?.error === 'Inicia sesión', 'el 401 trae {"error":"Inicia sesión"}');
  comprobarCodigo(await new Session(CENTRAL).api('/me'), 401, 'sin sesión no hay /me en CentralSys');
  comprobarCodigo(await new Session(PORTAL_TI).api('/ti/tickets'), 401, 'sin sesión no hay tickets en PortalTI');
  comprobarCodigo(await new Session(PORTAL_CLIENTE).api('/client/requests'), 401,
    'sin sesión no hay solicitudes en PortalCliente');

  // Contraseña incorrecta.
  const fallida = new Session(CENTRAL);
  loginFallidoPendiente = true;
  const rechazo = await fallida.intentarLogin(CUENTA_LOGIN_FALLIDO, 'no-es-la-clave');
  comprobarCodigo(rechazo, 401, 'login con contraseña incorrecta');
  comprobar(rechazo.body?.error === 'Usuario o contraseña incorrectos', 'el login fallido explica el motivo');
  comprobarCodigo(await fallida.api('/me'), 401, 'un login fallido no abre sesión');

  // El login también es una escritura: sin token CSRF no pasa.
  const sinToken = new Session(CENTRAL);
  await sinToken.csrf();
  const loginSinCsrf = await sinToken.intentarLogin('mesa1', undefined, { conCsrf: false });
  comprobarCodigo(loginSinCsrf, 403, 'login sin token CSRF');
}

async function empleadoDeMesa() {
  const mesa = await iniciarSesion(CENTRAL, 'mesa1');
  const yo = await mesa.api('/me');
  comprobarCodigo(yo, 200, '/me de mesa1');
  comprobar(yo.body.user === 'mesa1', '/me devuelve el usuario');
  comprobar(yo.body.groups?.includes('mesa-servicio'), 'mesa1 pertenece a mesa-servicio');
  comprobar(yo.body.cliente === false && yo.body.admin === false, 'mesa1 no es cliente ni admin');
  comprobar(mesa.cookies.has(COOKIE_CENTRAL), `la sesión de CentralSys viaja en la cookie ${COOKIE_CENTRAL}`);
  return mesa;
}

async function sesionNoSirveEnOtroPortal(mesa) {
  // El navegador envía a PortalTI las cookies de CentralSys (mismo host, otro puerto): no deben valer.
  const enTi = mesa.mismasCookiesEn(PORTAL_TI);
  comprobarCodigo(await enTi.api('/me'), 401, 'la sesión de CentralSys no sirve en PortalTI');
  const enCliente = mesa.mismasCookiesEn(PORTAL_CLIENTE);
  comprobarCodigo(await enCliente.api('/me'), 401, 'la sesión de CentralSys no sirve en PortalCliente');

  const mesaEnTi = await iniciarSesion(PORTAL_TI, 'mesa1');
  comprobar(mesaEnTi.cookies.has(COOKIE_TI), `PortalTI usa su propia cookie ${COOKIE_TI}`);
  comprobar(!mesaEnTi.cookies.has(COOKIE_CENTRAL), 'PortalTI no emite la cookie de CentralSys');
  comprobarCodigo(await mesaEnTi.mismasCookiesEn(CENTRAL).api('/me'), 401,
    'la sesión de PortalTI no sirve en CentralSys');
}

async function permisosSobreTareas(mesa) {
  const incidentKey = `TI-${RUN}`;
  instanciasCreadas.push(incidentKey);
  const incidente = await motor('POST', '/process-definition/key/CB_GestionIncidenteTI/start', {
    businessKey: incidentKey,
    variables: typed({
      requesterId: `usuario-${RUN}`,
      systemName: 'ERP',
      description: 'No carga la bandeja',
      impact: 'Medio',
      urgency: 'Media',
      safetyImpact: false,
      affectedTeams: 1
    })
  });

  const triaje = await esperar(async () => {
    const bandeja = await mesa.api('/central/tasks');
    if (bandeja.status !== 200) {
      throw new Error('GET /central/tasks -> ' + bandeja.status);
    }
    return bandeja.body.find((t) => t.businessKey === incidentKey && t.taskDefinitionKey === 'triageIncident');
  }, 'la tarea de triaje en la bandeja de mesa1');
  comprobar(!triaje.assignee, 'la tarea de triaje llega sin asignar');
  comprobar(triaje.candidateGroups?.includes('mesa-servicio'), 'la bandeja indica el grupo candidato');
  const tarea = '/central/tasks/' + triaje.id;

  // Otro grupo no la ve, no la reclama y no lee su formulario.
  const responsable = await iniciarSesion(CENTRAL, 'responsable1');
  const bandejaAjena = await responsable.api('/central/tasks');
  comprobarCodigo(bandejaAjena, 200, 'responsable1 consulta su bandeja');
  comprobar(!bandejaAjena.body.some((t) => t.id === triaje.id), 'responsable1 no ve la tarea de mesa-servicio');
  comprobarCodigo(await responsable.api(tarea + '/claim', { method: 'POST', body: {} }), 403,
    'responsable1 no puede reclamar una tarea de mesa-servicio');
  comprobarCodigo(await responsable.api(tarea + '/deployed-form'), 403,
    'responsable1 no puede leer el formulario de una tarea ajena');

  // Una escritura sin token CSRF se rechaza aunque la sesión y el permiso sean correctos.
  comprobarCodigo(await mesa.api(tarea + '/claim', { method: 'POST', body: {}, conCsrf: false }), 403,
    'POST sin token CSRF');
  // Tampoco pasa escribiendo la ruta con un carácter codificado (%61 es "a"): es la misma ruta.
  const codificada = await mesa.call('/api/%61pp' + tarea + '/claim', { method: 'POST' });
  await codificada.text();
  comprobar(codificada.status === 403, `POST sin token CSRF por /api/%61pp (recibido ${codificada.status})`);

  // El grupo correcto sí la reclama.
  const reclamo = await mesa.api(tarea + '/claim', { method: 'POST', body: {} });
  comprobarCodigo(reclamo, 200, 'mesa1 reclama la tarea de su grupo');
  comprobar(reclamo.body.assignee === 'mesa1', 'la tarea queda asignada a mesa1');

  // Ya asignada, nadie más puede soltarla ni completarla.
  comprobarCodigo(await responsable.api(tarea + '/unclaim', { method: 'POST', body: {} }), 403,
    'responsable1 no puede soltar la tarea de mesa1');
  const completarAjena = await responsable.api(tarea + '/complete', {
    method: 'POST',
    body: { variables: typed({ technicalDomain: 'especializado' }) }
  });
  comprobarCodigo(completarAjena, 403, 'responsable1 no puede completar la tarea de mesa1');

  const formulario = await mesa.api(tarea + '/deployed-form');
  comprobarCodigo(formulario, 200, 'mesa1 lee el formulario de su tarea');
  comprobar(JSON.stringify(formulario.body).includes('technicalDomain'), 'el formulario de triaje está desplegado');

  // complete solo acepta los campos editables del formulario: `priority` está en el formulario pero
  // es de solo lectura, y `variableAjena` no existe en él. Ninguna de las dos debe llegar al proceso.
  const prioridadAntes = await variableEnMotor(incidente.id, 'priority');
  comprobar(prioridadAntes.length >= 1 && !prioridadAntes.includes('P1'),
    `el motor calculó una prioridad distinta de P1 (${prioridadAntes.join(', ')})`);
  const completar = await mesa.api(tarea + '/complete', {
    method: 'POST',
    body: { variables: typed({ technicalDomain: 'catalogoNivel1', priority: 'P1', variableAjena: 'intrusa' }) }
  });
  comprobarCodigo(completar, 200, 'mesa1 completa la tarea');
  comprobar(completar.body.completed === true, 'complete responde completed:true');
  const prioridadDespues = await variableEnMotor(incidente.id, 'priority');
  comprobar(prioridadDespues.includes(prioridadAntes[0]) && !prioridadDespues.includes('P1'),
    `complete ignoró priority: debe seguir en ${prioridadAntes[0]} (ahora ${prioridadDespues.join(', ')})`);
  comprobar((await variableEnMotor(incidente.id, 'variableAjena')).length === 0,
    'complete descartó una variable que no está en el formulario');
  const dominio = await variableEnMotor(incidente.id, 'technicalDomain');
  comprobar(dominio.includes('catalogoNivel1'), 'complete sí guardó el campo editable');

  // Iniciar nómina es solo para analistas-nomina.
  const nominaAjena = await mesa.api('/central/nomina', {
    method: 'POST',
    body: { payPeriodId: 'PERMISOS', payCycle: 'QUINCENAL', cutoffDate: '2026-11-25' }
  });
  if (nominaAjena.body?.businessKey) {
    instanciasCreadas.push(nominaAjena.body.businessKey);
  }
  comprobarCodigo(nominaAjena, 403, 'mesa1 no puede iniciar la nómina');
}

async function perfilesPorPortal() {
  // Un cliente no entra a los portales de empleados.
  const cliente1EnCentral = await iniciarSesion(CENTRAL, 'cliente1');
  comprobarCodigo(await cliente1EnCentral.api('/central/tasks'), 403, 'cliente1 no accede a la bandeja interna');
  const cliente2EnTi = await iniciarSesion(PORTAL_TI, 'cliente2');
  comprobarCodigo(await cliente2EnTi.api('/ti/tickets'), 403, 'cliente2 no consulta tickets en PortalTI');
  const reporteDeCliente = await cliente2EnTi.api('/ti/reports', {
    method: 'POST',
    body: {
      systemName: 'ERP',
      description: 'Reporte que no debe crearse',
      impact: 'Bajo',
      urgency: 'Baja',
      affectedTeams: 1,
      safetyImpact: false
    }
  });
  if (reporteDeCliente.body?.businessKey) {
    instanciasCreadas.push(reporteDeCliente.body.businessKey);
  }
  comprobarCodigo(reporteDeCliente, 403, 'cliente2 no puede reportar incidentes en PortalTI');

  // Un empleado no entra al portal de clientes.
  const mesaEnCliente = await iniciarSesion(PORTAL_CLIENTE, 'mesa1');
  comprobarCodigo(await mesaEnCliente.api('/client/requests'), 403, 'mesa1 no accede al portal de cliente');
}

async function aislamientoEntreClientes() {
  const cliente1 = await iniciarSesion(PORTAL_CLIENTE, 'cliente1');
  comprobar(cliente1.cookies.has(COOKIE_CLIENTE), `PortalCliente usa su propia cookie ${COOKIE_CLIENTE}`);
  const yo = await cliente1.api('/me');
  comprobar(yo.body.cliente === true && yo.body.groups?.includes('clientes'), 'cliente1 tiene perfil de cliente');

  const creada = await cliente1.api('/client/requests', {
    method: 'POST',
    body: {
      customerName: 'Cliente autenticado',
      customerEmail: 'cliente1@example.invalid',
      vin: 'WDB0000000000000B',
      mileageKm: 15000,
      serviceType: 'Garantía',
      symptom: 'Ruido en la suspensión'
    }
  });
  comprobarCodigo(creada, 200, 'cliente1 crea una solicitud');
  const solicitud = creada.body.businessKey;
  instanciasCreadas.push(solicitud);
  comprobar(/^PV-/.test(solicitud), `el portal genera la businessKey (${solicitud})`);

  const mias = await cliente1.api('/client/requests');
  comprobarCodigo(mias, 200, 'cliente1 consulta sus solicitudes');
  comprobar(mias.body.some((s) => s.businessKey === solicitud), 'cliente1 ve su propia solicitud');
  comprobar(mias.body.every((s) => s.id && s.variables), 'cada solicitud trae su instancia y sus variables');

  const cliente2 = await iniciarSesion(PORTAL_CLIENTE, 'cliente2');
  const ajenas = await cliente2.api('/client/requests');
  comprobarCodigo(ajenas, 200, 'cliente2 consulta sus solicitudes');
  comprobar(!ajenas.body.some((s) => s.businessKey === solicitud), 'cliente2 no ve la solicitud de cliente1');
  const respuestaAjena = await cliente2.api('/client/requests/' + encodeURIComponent(solicitud) + '/answer', {
    method: 'POST',
    body: { action: 'aceptar' }
  });
  comprobarCodigo(respuestaAjena, 404, 'cliente2 no puede responder por cliente1');

  // El dueño tampoco puede responder mientras el proceso no espere la cotización.
  const respuestaTemprana = await cliente1.api('/client/requests/' + encodeURIComponent(solicitud) + '/answer', {
    method: 'POST',
    body: { action: 'aceptar' }
  });
  comprobarCodigo(respuestaTemprana, 409, 'no se responde una cotización que el proceso aún no espera');
}

async function permisosDeBodega() {
  const bodega = await iniciarSesion(CENTRAL, 'bodega1');
  const reservas = await bodega.api('/bodega/reservas');
  comprobarCodigo(reservas, 200, 'bodega1 consulta reservas');
  comprobar(Array.isArray(reservas.body), 'las reservas llegan como lista');

  const asesor = await iniciarSesion(CENTRAL, 'asesor1');
  comprobarCodigo(await asesor.api('/bodega/reservas'), 403, 'asesor1 no consulta reservas');
  // Cuerpo vacío: si el permiso fallara, la simulación de la bodega quedaría igual.
  comprobarCodigo(await asesor.api('/bodega/simulacion', { method: 'PUT', body: {} }), 403,
    'asesor1 no cambia la simulación de la bodega');

  const admin = await iniciarSesion(CENTRAL, 'demo');
  const yo = await admin.api('/me');
  comprobar(yo.body.admin === true, 'demo tiene perfil de administrador');
  comprobarCodigo(await admin.api('/bodega/reservas'), 200, 'el administrador también consulta reservas');
}

async function cierreDeSesion(mesa) {
  const salida = await mesa.logout();
  comprobar(salida.status === 204 && salida.texto.length === 0, 'logout responde 204 sin cuerpo');
  comprobarCodigo(await mesa.api('/central/tasks'), 401, 'al cerrar sesión se pierde el acceso');
}

async function apisInternasProtegidas() {
  const sinCredencial = [
    ['Motor', MOTOR_REST + '/group'],
    ['PortalTI', PORTAL_TI + '/api/tickets?requesterId=x'],
    ['Banco', BANCO + '/api/dispersiones'],
    ['Taller-Bodega', BODEGA + '/api/reservas']
  ];
  for (const [sistema, url] of sinCredencial) {
    const respuesta = await anonimo(url);
    comprobarCodigo(respuesta, 401, `${sistema} rechaza sin credencial interna`);
    comprobar(respuesta.body?.error === MENSAJE_CREDENCIAL, `${sistema} explica que falta la credencial interna`);
  }
  // Tener sesión en un portal no da acceso a su API interna.
  const empleado = await iniciarSesion(PORTAL_TI, 'soporte1');
  const conSesion = await empleado.call('/api/tickets?requesterId=x');
  comprobar(conSesion.status === 401, 'la sesión de una persona no sustituye la credencial interna');
}

async function limpiar() {
  if (loginFallidoPendiente) {
    try {
      await entrarTrasElBloqueo();
    } catch (error) {
      console.log(`     ${CUENTA_LOGIN_FALLIDO} quedó con un intento fallido sin limpiar: ${error.message}`);
    }
  }
  for (const businessKey of instanciasCreadas) {
    try {
      await borrarInstancias(businessKey);
    } catch (error) {
      console.log(`     No se pudo limpiar ${businessKey}: ${error.message}`);
    }
  }
}

let fallo;
try {
  await esperarSistemas();
  await sesionYCsrf();
  const mesa = await empleadoDeMesa();
  await sesionNoSirveEnOtroPortal(mesa);
  await permisosSobreTareas(mesa);
  await perfilesPorPortal();
  await aislamientoEntreClientes();
  await permisosDeBodega();
  await cierreDeSesion(mesa);
  await apisInternasProtegidas();
  await entrarTrasElBloqueo();
  comprobar(true, `tras un intento fallido, ${CUENTA_LOGIN_FALLIDO} entra con su contraseña`);
} catch (error) {
  fallo = error;
} finally {
  await limpiar();
}

if (fallo) {
  console.log(`FAIL permisos (${verificaciones} verificaciones correctas antes del fallo)`);
  console.log('     ' + fallo.message);
  process.exit(1);
}
console.log(`OK: ${verificaciones} verificaciones de sesión, CSRF, permisos por grupo, aislamiento entre `
  + 'clientes y portales, y APIs internas protegidas');
