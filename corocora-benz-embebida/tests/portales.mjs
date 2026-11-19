// Los tres procesos de punta a punta POR LAS APIS DE LOS PORTALES (/api/app), con sesiones reales
// de cada persona, tal como lo haría el navegador.
// Requisitos: los 6 sistemas corriendo (Motor 9000, PortalCliente 9001, PortalTI 9002,
// CentralSys 9003, Banco 9004, Taller-Bodega 9005).
// La credencial interna solo se usa para lo que ninguna persona hace desde un portal: poner el Banco
// y la Bodega en modo manual (y restaurarlos), responder como Banco, leer el historial del motor
// para las comprobaciones finales y borrar las instancias que deje a medias un escenario fallido.
// Uso: npm run portales
import {
  BANCO, BODEGA, CENTRAL, PORTAL_CLIENTE, PORTAL_TI,
  Session, borrarInstancias, esperar, esperarSistemas, interno, motor, typed, valoresDe
} from './comun.mjs';

const VIN_SIN_COBERTURA = 'WDB0000000000000B';
const VIN_EN_CAMPANA = 'CB0TEST0000000001';
const CODIGO_RECHAZO = 'R03-CUENTA-INVALIDA';
const TOTAL_NOMINA = 1000000;
const MONTO_COTIZACION = 850000;

const resultados = [];
const businessKeys = [];
let actual;

function check(condicion, mensaje) {
  if (!condicion) {
    throw new Error('CHECK FAILED: ' + mensaje);
  }
  actual.checks.push(mensaje);
}

async function escenario(nombre, pasos) {
  actual = { nombre, checks: [], ok: false };
  const inicio = Date.now();
  try {
    await pasos();
    actual.ok = true;
  } catch (error) {
    actual.error = error.message;
  }
  const segundos = ((Date.now() - inicio) / 1000).toFixed(1);
  resultados.push(actual);
  console.log(`${actual.ok ? 'OK  ' : 'FAIL'} ${nombre} (${segundos} s, ${actual.checks.length} verificaciones)`);
  if (!actual.ok) {
    console.log('     ' + actual.error);
  }
}

// ---------------------------------------------------------------- sesiones

// Una sesión por persona y portal, compartida entre escenarios (como una pestaña que queda abierta).
const sesiones = new Map();

function sesion(base, usuario) {
  const clave = base + '|' + usuario;
  if (!sesiones.has(clave)) {
    const pendiente = new Session(base).login(usuario);
    pendiente.catch(() => sesiones.delete(clave));
    sesiones.set(clave, pendiente);
  }
  return sesiones.get(clave);
}

/** Llama a la API del portal y exige un 200; devuelve el cuerpo. */
async function pedir(persona, ruta, opciones = {}) {
  const respuesta = await persona.api(ruta, opciones);
  if (respuesta.status !== 200) {
    const metodo = opciones.method || 'GET';
    const detalle = JSON.stringify(respuesta.body);
    throw new Error(`${metodo} /api/app${ruta} como ${persona.user} -> ${respuesta.status} ${detalle}`);
  }
  return respuesta.body;
}

// ---------------------------------------------------------------- tareas de empleado (CentralSys)

function camposEditables(componentes, acumulado = []) {
  for (const componente of componentes || []) {
    if (componente.key && !componente.disabled) {
      acumulado.push(componente);
    }
    camposEditables(componente.components, acumulado);
  }
  return acumulado;
}

function campo(formulario, clave) {
  return camposEditables(formulario.components).find((componente) => componente.key === clave);
}

/**
 * Hace una tarea como en la web: espera a que aparezca en la bandeja, la reclama, abre su formulario
 * y la completa. `variables` puede ser una función que recibe los valores que muestra el formulario.
 */
async function hacerTarea(usuario, bk, taskDefinitionKey, variables, revisar) {
  const persona = await sesion(CENTRAL, usuario);
  const tarea = await esperar(async () => {
    const bandeja = await pedir(persona, '/central/tasks');
    return bandeja.find((t) => t.businessKey === bk && t.taskDefinitionKey === taskDefinitionKey);
  }, `la tarea ${taskDefinitionKey} (${bk}) en la bandeja de ${usuario}`);
  const ruta = '/central/tasks/' + tarea.id;

  if (tarea.assignee !== usuario) {
    const reclamo = await pedir(persona, ruta + '/claim', { method: 'POST', body: {} });
    check(reclamo.assignee === usuario, `${taskDefinitionKey}: ${usuario} reclama la tarea de su bandeja`);
  }

  const formulario = await pedir(persona, ruta + '/deployed-form');
  check(Array.isArray(formulario.components) && formulario.components.length > 0,
    `${taskDefinitionKey}: el formulario desplegado trae componentes`);
  const valores = valoresDe(await pedir(persona, ruta + '/form-variables'));
  if (revisar) {
    revisar(valores, formulario);
  }

  const enviar = typeof variables === 'function' ? variables(valores) : variables;
  // El servidor descarta en silencio lo que no sea un campo editable: si faltara alguno, la tarea
  // se completaría sin el dato y el fallo aparecería mucho después.
  const faltantes = Object.keys(enviar).filter((nombre) => !campo(formulario, nombre));
  check(faltantes.length === 0,
    `${taskDefinitionKey}: el formulario tiene como editables ${Object.keys(enviar).join(', ')}`
    + (faltantes.length ? ` (faltan: ${faltantes.join(', ')})` : ''));

  const resultado = await pedir(persona, ruta + '/complete', {
    method: 'POST',
    body: { variables: typed(enviar) }
  });
  check(resultado.completed === true, `${taskDefinitionKey}: ${usuario} completa la tarea`);
  return { tarea, valores, formulario, resultado };
}

// ---------------------------------------------------------------- historial del motor (solo lectura)

async function esperarFin(bk, definicion, finEsperado) {
  const filtro = `processInstanceBusinessKey=${encodeURIComponent(bk)}&processDefinitionKey=${definicion}`;
  const instancia = await esperar(async () => {
    const historial = await motor('GET', '/history/process-instance?' + filtro);
    const raiz = historial[0];
    return raiz && raiz.state === 'COMPLETED' ? raiz : null;
  }, `el fin de ${definicion} (${bk})`);
  const actividades = await motor('GET', `/history/activity-instance?processInstanceId=${instancia.id}`);
  const fines = actividades.filter((actividad) => actividad.activityType === 'noneEndEvent');
  check(fines.some((fin) => fin.activityId === finEsperado),
    `${definicion} terminó en ${finEsperado} (fines: ${fines.map((fin) => fin.activityId).join(', ')})`);
  return instancia;
}

async function variableHistorica(processInstanceId, nombre) {
  const ruta = `/history/variable-instance?processInstanceId=${processInstanceId}&variableName=${nombre}`;
  const lista = await motor('GET', ruta);
  return lista.length ? lista[0].value : undefined;
}

// ---------------------------------------------------------------- Banco (credencial interna)

function dispersiones(bk) {
  return interno(BANCO, 'GET', '/api/dispersiones?businessKey=' + encodeURIComponent(bk));
}

/** Responde como Banco la dispersión pendiente del proceso (accion: aceptar | rechazar). */
async function bancoResponde(bk, accion, cuerpo) {
  return esperar(async () => {
    const pendiente = (await dispersiones(bk)).find((dispersion) => dispersion.estado === 'RECIBIDA');
    if (!pendiente) {
      return null;
    }
    try {
      return await interno(BANCO, 'POST', `/api/dispersiones/${pendiente.id}/${accion}`, cuerpo);
    } catch (error) {
      // 409: la dispersión llegó, pero el proceso todavía no espera la respuesta.
      if (error.status === 409) {
        return null;
      }
      throw error;
    }
  }, `que el Banco pueda ${accion} la dispersión de ${bk}`);
}

// ---------------------------------------------------------------- Incidentes TI

const SOLUCION = { rootCause: 'Versión de firmware con error', resolutionSummary: 'Actualización OTA aplicada' };

async function reportarIncidente(descripcion) {
  const usuario = await sesion(PORTAL_TI, 'USR-01');
  const reporte = await pedir(usuario, '/ti/reports', {
    method: 'POST',
    body: {
      systemName: 'Software del vehículo',
      description: descripcion,
      impact: 'Medio',
      urgency: 'Media',
      affectedTeams: 1,
      safetyImpact: false
    }
  });
  const bk = reporte.businessKey;
  check(/^TI-.+/.test(bk || ''), `USR-01 reporta el incidente y PortalTI genera la businessKey (${bk})`);
  businessKeys.push(bk);
  return bk;
}

async function esperarTicket(bk, condicion, que) {
  const usuario = await sesion(PORTAL_TI, 'USR-01');
  return esperar(async () => {
    const tickets = await pedir(usuario, '/ti/tickets');
    const ticket = tickets.find((t) => t.businessKey === bk);
    return ticket && condicion(ticket) ? ticket : null;
  }, `${que} (${bk}) en los tickets de USR-01`);
}

async function triaje(bk, descripcion) {
  await hacerTarea('mesa1', bk, 'triageIncident', { technicalDomain: 'catalogoNivel1' }, (visto) => {
    check(visto.priority === 'P3' && visto.targetDuration === 'PT24H',
      `triaje: mesa1 ve la prioridad y el plazo que calculó el motor (${visto.priority}, ${visto.targetDuration})`);
    check(String(visto.ticketId || '').startsWith('TI-') && visto.description === descripcion,
      'triaje: mesa1 ve el ticket creado en PortalTI y la descripción del reporte');
  });
}

async function resolver(bk, solucion) {
  await hacerTarea('soporte1', bk, 'resolveN1', solucion, (visto) => {
    check(visto.ticketId && visto.priority && visto.description,
      'resolución: soporte1 ve ticket, prioridad y descripción');
  });
}

async function confirmar(ticket, confirmed) {
  const usuario = await sesion(PORTAL_TI, 'USR-01');
  const ruta = '/ti/tickets/' + encodeURIComponent(ticket.ticketId) + '/confirm';
  const respuesta = await pedir(usuario, ruta, { method: 'POST', body: { confirmed } });
  check(respuesta.ticketId === ticket.ticketId && respuesta.confirmed === confirmed,
    `USR-01 responde confirmed:${confirmed} sobre ${ticket.ticketId}`);
}

async function cierreDelIncidente(bk) {
  await esperarFin(bk, 'CB_GestionIncidenteTI', 'incidentEnd');
  const cerrado = await esperarTicket(bk, (t) => t.status === 'CLOSED', 'el ticket CLOSED');
  check(cerrado.canConfirm === false, 'el ticket cerrado ya no pide confirmación');
  const usuario = await sesion(PORTAL_TI, 'USR-01');
  const repetida = await usuario.api('/ti/tickets/' + encodeURIComponent(cerrado.ticketId) + '/confirm', {
    method: 'POST',
    body: { confirmed: true }
  });
  check(repetida.status === 409, `confirmar de nuevo un ticket cerrado responde 409 (recibido ${repetida.status})`);
}

async function incidenteConfirmado() {
  const descripcion = 'La cámara de reversa no carga';
  const bk = await reportarIncidente(descripcion);
  await triaje(bk, descripcion);
  await resolver(bk, SOLUCION);
  const resuelto = await esperarTicket(bk, (t) => t.status === 'RESOLVED' && t.canConfirm === true,
    'el ticket RESOLVED con canConfirm');
  check(resuelto.resolutionSummary === SOLUCION.resolutionSummary && resuelto.rootCause === SOLUCION.rootCause,
    'USR-01 ve la causa y la solución que registró soporte1');
  await confirmar(resuelto, true);
  await cierreDelIncidente(bk);
}

async function incidenteRechazadoYLuegoConfirmado() {
  const descripcion = 'El tablero no muestra la presión de las llantas';
  const bk = await reportarIncidente(descripcion);
  await triaje(bk, descripcion);
  await resolver(bk, SOLUCION);
  const resuelto = await esperarTicket(bk, (t) => t.status === 'RESOLVED' && t.canConfirm === true,
    'el ticket RESOLVED con canConfirm');
  await confirmar(resuelto, false);

  const reabierto = await esperarTicket(bk, (t) => t.canConfirm === false, 'el ticket sin confirmación pendiente');
  check(reabierto.status !== 'CLOSED', `el rechazo no cierra el ticket (estado ${reabierto.status})`);

  const segundaSolucion = { rootCause: 'Sensor descalibrado', resolutionSummary: 'Sensor recalibrado en taller' };
  await resolver(bk, segundaSolucion);
  const resueltoDeNuevo = await esperarTicket(bk,
    (t) => t.status === 'RESOLVED' && t.canConfirm === true && t.resolutionSummary === segundaSolucion.resolutionSummary,
    'el ticket RESOLVED con la segunda solución');
  check(resueltoDeNuevo.ticketId === resuelto.ticketId, 'la segunda solución llega al mismo ticket');
  await confirmar(resueltoDeNuevo, true);
  await cierreDelIncidente(bk);
}

// ---------------------------------------------------------------- Postventa

const SINTOMA = 'La cámara de reversa no muestra imagen';
const REPARACION = 'Se reemplazó el módulo de cámara';

async function crearSolicitud(usuario, vin, mileageKm, serviceType) {
  const cliente = await sesion(PORTAL_CLIENTE, usuario);
  const creada = await pedir(cliente, '/client/requests', {
    method: 'POST',
    body: {
      customerName: 'Cliente ' + usuario,
      customerEmail: usuario + '@corocorabenz.test',
      vin,
      mileageKm,
      serviceType,
      symptom: SINTOMA
    }
  });
  const bk = creada.businessKey;
  check(/^PV-.+/.test(bk || ''), `${usuario} crea la solicitud y PortalCliente genera la businessKey (${bk})`);
  businessKeys.push(bk);
  return bk;
}

async function esperarSolicitud(usuario, bk, condicion, que) {
  const cliente = await sesion(PORTAL_CLIENTE, usuario);
  return esperar(async () => {
    const solicitudes = await pedir(cliente, '/client/requests');
    const solicitud = solicitudes.find((s) => s.businessKey === bk);
    if (!solicitud) {
      return null;
    }
    // El contrato no fija si `variables` llega plano o con el envoltorio de Camunda: se aceptan ambos.
    const vista = { ...solicitud, variables: valoresDe(solicitud.variables) };
    return condicion(vista) ? vista : null;
  }, `${que} (${bk}) en las solicitudes de ${usuario}`);
}

function termino(solicitud, activityId) {
  return (solicitud.activities || []).some((actividad) => actividad.activityId === activityId && actividad.endTime);
}

async function registrar(bk, vin) {
  await hacerTarea('asesor1', bk, 'registerRequest',
    { dealerId: 'TALLER-BOG-01', registrationNote: 'Cliente reporta falla en la cámara' },
    (visto) => {
      check(visto.vin === vin && visto.symptom === SINTOMA,
        'registro: asesor1 ve el VIN y el síntoma que escribió el cliente');
    });
}

async function responderCotizacion(usuario, bk, action) {
  const cotizada = await esperarSolicitud(usuario, bk, (s) => s.canReply === true, 'la cotización pendiente (canReply)');
  check(Number(cotizada.variables.quoteAmount) === MONTO_COTIZACION && cotizada.variables.quoteId,
    `${usuario} ve la cotización ${cotizada.variables.quoteId} por ${cotizada.variables.quoteAmount}`);
  check(cotizada.variables.coverageStatus === 'REQUIERE_COTIZACION' && cotizada.variables.dealerId === 'TALLER-BOG-01',
    `${usuario} ve la cobertura evaluada y el taller asignado`);
  const cliente = await sesion(PORTAL_CLIENTE, usuario);
  const ruta = '/client/requests/' + encodeURIComponent(bk) + '/answer';
  const respuesta = await pedir(cliente, ruta, { method: 'POST', body: { action } });
  check(respuesta.businessKey === bk && respuesta.action === action, `${usuario} responde "${action}" a la cotización`);
  await esperarSolicitud(usuario, bk, (s) => s.canReply === false, 'la cotización ya respondida');
  const repetida = await cliente.api(ruta, { method: 'POST', body: { action } });
  check(repetida.status === 409, `responder dos veces la cotización da 409 (recibido ${repetida.status})`);
}

async function agendar(bk) {
  const cita = { appointmentDate: '2026-11-20 09:00', serviceOrderId: 'OT-' + bk };
  await hacerTarea('coordinador1', bk, 'scheduleWorkshop', cita, (visto) => {
    check(visto.dealerId === 'TALLER-BOG-01', 'agenda: coordinador1 ve el taller que asignó el asesor');
  });
  return cita;
}

async function diagnosticar(bk, conRepuesto) {
  await hacerTarea('tecnico1', bk, 'diagnoseVehicle',
    (visto) => ({
      mileageKm: Number(visto.mileageKm) + 5,
      diagnosis: 'Falla en el módulo de cámara',
      additionalPartNeeded: conRepuesto,
      partCode: conRepuesto ? 'CAM-123' : ''
    }),
    (visto) => {
      check(visto.vin && visto.symptom === SINTOMA && visto.mileageKm != null,
        'diagnóstico: tecnico1 recibe VIN, síntoma y kilometraje');
    });
}

async function bodegaEntregaRepuesto(bk) {
  const bodega = await sesion(CENTRAL, 'bodega1');
  const pendiente = await esperar(async () => {
    const solicitudes = await pedir(bodega, '/bodega/solicitudes');
    return solicitudes.find((s) => s.businessKey === bk && s.estado === 'SOLICITADA');
  }, `la solicitud de repuesto de ${bk} en la bodega de CentralSys`);
  check(pendiente.partCode === 'CAM-123', 'bodega1 ve en CentralSys el repuesto que pidió el técnico (CAM-123)');

  await esperar(async () => {
    const entrega = await bodega.api(`/bodega/solicitudes/${pendiente.id}/entregar`, { method: 'POST' });
    // 409: el proceso todavía no espera el aviso de la bodega.
    if (entrega.status === 409) {
      return null;
    }
    if (entrega.status !== 200) {
      throw new Error(`POST /bodega/solicitudes/${pendiente.id}/entregar -> ${entrega.status}`);
    }
    return true;
  }, `que bodega1 pueda entregar el repuesto de ${bk}`);
  const solicitudes = await pedir(bodega, '/bodega/solicitudes');
  const entregada = solicitudes.find((s) => s.id === pendiente.id);
  check(entregada && entregada.estado === 'ENTREGADA', 'la bodega registra el repuesto como ENTREGADA');
}

async function repararInspeccionarYEntregar(bk, cita) {
  await hacerTarea('tecnico1', bk, 'repairVehicle', { repairNotes: REPARACION, serviceCost: 350000 }, (visto) => {
    check(visto.diagnosis === 'Falla en el módulo de cámara', 'reparación: tecnico1 ve su diagnóstico');
  });
  await hacerTarea('calidad1', bk, 'inspectRepair',
    { inspectionPassed: true, inspectionResults: 'Prueba de ruta OK' },
    (visto) => {
      check(visto.repairNotes === REPARACION, 'inspección: calidad1 ve el trabajo realizado');
    });
  await hacerTarea('asesor1', bk, 'deliverVehicle',
    { customerSatisfaction: 5, deliveryNote: 'Vehículo entregado al cliente' },
    (visto, formulario) => {
      check(Number(visto.serviceCost) === 350000 && visto.serviceOrderId === cita.serviceOrderId,
        'entrega: asesor1 ve el costo y la orden de taller');
      check(campo(formulario, 'customerSatisfaction')?.validate?.max === 5,
        'entrega: la satisfacción del cliente se limita a 5 (contrato, sección 8)');
    });
}

async function cierreDePostventa(usuario, bk, cita) {
  const instancia = await esperarFin(bk, 'CB_PostventaGarantias', 'closedEnd');
  check((await variableHistorica(instancia.id, 'customerUserId')) === usuario,
    `el proceso guarda a ${usuario} como dueño de la solicitud (customerUserId)`);
  const cerrada = await esperarSolicitud(usuario, bk, (s) => Boolean(s.endTime), 'la solicitud terminada');
  check(termino(cerrada, 'closedEnd'), `${usuario} ve closedEnd terminado`);
  check(cerrada.variables.repairNotes === REPARACION, `${usuario} ve el trabajo realizado (repairNotes)`);
  check(cerrada.variables.appointmentDate === cita.appointmentDate
    && cerrada.variables.serviceOrderId === cita.serviceOrderId, `${usuario} ve la cita y la orden de taller`);
  check(cerrada.canReply === false, 'una solicitud terminada no espera respuesta');
}

async function postventaConCotizacionAceptada() {
  const bk = await crearSolicitud('cliente1', VIN_SIN_COBERTURA, 80000, 'Reparación');
  await registrar(bk, VIN_SIN_COBERTURA);
  await responderCotizacion('cliente1', bk, 'aceptar');
  const cita = await agendar(bk);
  await diagnosticar(bk, true);
  await bodegaEntregaRepuesto(bk);
  await repararInspeccionarYEntregar(bk, cita);
  await cierreDePostventa('cliente1', bk, cita);
}

async function postventaEnGarantia() {
  const bk = await crearSolicitud('cliente2', VIN_EN_CAMPANA, 15000, 'Garantía');
  await registrar(bk, VIN_EN_CAMPANA);
  await hacerTarea('garantia1', bk, 'reviewWarranty',
    { coverageApproved: true, warrantyNote: 'Aplica campaña de cámara de reversa' },
    (visto) => {
      check(visto.coverageStatus === 'CAMPANA_ACTIVA' && visto.coverageReason,
        'garantía: garantia1 ve la cobertura CAMPANA_ACTIVA y su motivo');
    });
  const cita = await agendar(bk);
  await diagnosticar(bk, false);
  await repararInspeccionarYEntregar(bk, cita);
  await cierreDePostventa('cliente2', bk, cita);

  const bodega = await sesion(CENTRAL, 'bodega1');
  const reservas = await pedir(bodega, '/bodega/reservas');
  check(reservas.some((reserva) => reserva.businessKey === bk && reserva.estado === 'RESERVED'),
    'bodega1 ve en CentralSys la reserva de repuestos de la solicitud');
  const solicitudes = await pedir(bodega, '/bodega/solicitudes');
  check(!solicitudes.some((s) => s.businessKey === bk), 'sin repuesto adicional no hay solicitud a la bodega');

  const otroCliente = await sesion(PORTAL_CLIENTE, 'cliente1');
  const ajenas = await pedir(otroCliente, '/client/requests');
  check(!ajenas.some((s) => s.businessKey === bk), 'cliente1 no ve la solicitud de cliente2');
}

async function postventaConCotizacionRechazada() {
  const bk = await crearSolicitud('cliente1', VIN_SIN_COBERTURA, 80000, 'Reparación');
  await registrar(bk, VIN_SIN_COBERTURA);
  await responderCotizacion('cliente1', bk, 'rechazar');
  await esperarFin(bk, 'CB_PostventaGarantias', 'unauthorizedEnd');
  const cerrada = await esperarSolicitud('cliente1', bk, (s) => Boolean(s.endTime), 'la solicitud terminada');
  check(termino(cerrada, 'unauthorizedEnd') && termino(cerrada, 'quoteRejected'),
    'cliente1 ve la cotización rechazada y el caso cerrado sin reparación');
  check(!termino(cerrada, 'closedEnd') && !cerrada.variables.repairNotes, 'la solicitud rechazada no pasó por el taller');
  check(cerrada.canReply === false, 'una solicitud rechazada no espera más respuestas');
}

// ---------------------------------------------------------------- Nómina

async function iniciarNomina() {
  const analista = await sesion(CENTRAL, 'nomina1');
  const periodo = { payPeriodId: '2026-11-Q2', payCycle: 'QUINCENAL', cutoffDate: '2026-11-25' };
  const iniciada = await pedir(analista, '/central/nomina', { method: 'POST', body: periodo });
  const bk = iniciada.businessKey;
  check(/^NOM-2026-11-Q2-.{6}$/.test(bk || '') && iniciada.id,
    `nomina1 inicia el ciclo y CentralSys genera la businessKey (${bk})`);
  businessKeys.push(bk);
  return bk;
}

async function validarNovedades(bk, cantidad) {
  const empleados = new Set();
  for (let numero = 1; numero <= cantidad; numero += 1) {
    const { valores } = await hacerTarea('nomina1', bk, 'reviewChange',
      { changeValid: true, reviewComment: 'Soporte verificado' },
      (visto) => {
        check(visto.employeeId && visto.changeType && visto.changeValue != null,
          `novedad ${numero}: nomina1 ve empleado, tipo y valor (${visto.employeeId})`);
      });
    empleados.add(valores.employeeId);
  }
  check(empleados.size === cantidad, `se validaron ${cantidad} novedades de empleados distintos`);
}

async function liquidarYAprobar(bk) {
  await hacerTarea('nomina1', bk, 'reviewPayroll',
    { payrollConsistent: true, reviewComment: 'Liquidación revisada sin observaciones' },
    (visto) => {
      check(Number(visto.payrollTotal) === TOTAL_NOMINA && visto.payrollSummary,
        'liquidación: nomina1 ve el resumen y el total calculado');
    });
  await hacerTarea('jefatura1', bk, 'approvePayroll',
    { payrollApproved: true, approvalReason: 'Aprobada' },
    (visto) => {
      check(Number(visto.payrollTotal) === TOTAL_NOMINA, 'aprobación: jefatura1 ve el total de la liquidación');
    });
}

async function prepararLote(bk, bankAccount) {
  const lote = { paymentBatchId: 'LOTE-' + bk, bankAccount };
  await hacerTarea('tesoreria1', bk, 'prepareBankBatch', lote, (visto) => {
    check(Number(visto.payrollTotal) === TOTAL_NOMINA, 'lote: tesoreria1 ve el total aprobado');
  });
  return lote;
}

async function cierreDeNomina(bk, lote) {
  const aceptada = await bancoResponde(bk, 'aceptar');
  check(aceptada.estado === 'ACEPTADA' && aceptada.paymentBatchId === lote.paymentBatchId
    && Number(aceptada.payrollTotal) === TOTAL_NOMINA,
    'el Banco recibió el lote y el total, y aceptó la dispersión');
  const instancia = await esperarFin(bk, 'CB_GestionNomina', 'payrollEnd');
  const referencia = await variableHistorica(instancia.id, 'bankReference');
  check(String(referencia).startsWith('BANCO-'), `el proceso guarda la referencia del Banco (${referencia})`);
  return aceptada;
}

async function nominaAceptada() {
  const bk = await iniciarNomina();
  await validarNovedades(bk, 2);
  await liquidarYAprobar(bk);
  const lote = await prepararLote(bk, '000-123');
  const aceptada = await cierreDeNomina(bk, lote);
  check(aceptada.bankAccount === '000-123', 'la dispersión aceptada usa la cuenta 000-123');

  const analista = await sesion(CENTRAL, 'nomina1');
  const actividad = await pedir(analista, '/central/actividad');
  check(Array.isArray(actividad) && actividad.length > 0 && actividad.length <= 20,
    'nomina1 consulta su actividad reciente (máximo 20 acciones)');
  check(JSON.stringify(actividad).includes(bk), 'la actividad de nomina1 registra lo que hizo en este ciclo');
}

async function nominaConRechazoBancario() {
  const bk = await iniciarNomina();
  await validarNovedades(bk, 2);
  await liquidarYAprobar(bk);
  const lote = await prepararLote(bk, '123');

  const rechazada = await bancoResponde(bk, 'rechazar', { codigo: CODIGO_RECHAZO });
  check(rechazada.estado === 'RECHAZADA' && rechazada.bankAccount === '123',
    'el Banco rechaza la dispersión de la cuenta 123');

  await hacerTarea('tesoreria1', bk, 'fixBankBatch',
    { paymentBatchId: lote.paymentBatchId, bankAccount: '000-123', correction: 'Cuenta de origen corregida' },
    (visto, formulario) => {
      check(visto.bankResponseCode === CODIGO_RECHAZO, 'corrección: tesoreria1 ve el código de rechazo del Banco');
      check(campo(formulario, 'bankAccount')?.validate?.required === true,
        'corrección: el formulario pide la cuenta corregida como campo obligatorio (contrato, sección 8)');
    });

  await cierreDeNomina(bk, lote);
  const historial = await dispersiones(bk);
  const aceptadas = historial.filter((dispersion) => dispersion.estado === 'ACEPTADA');
  check(aceptadas.length === 1 && aceptadas[0].bankAccount === '000-123',
    'en el Banco, la dispersión aceptada tiene la cuenta corregida 000-123');
  check(historial.some((dispersion) => dispersion.estado === 'RECHAZADA' && dispersion.bankAccount === '123'),
    'el Banco conserva la dispersión rechazada con la cuenta original');
}

// ---------------------------------------------------------------- ejecución

async function instanciasActivas() {
  const activas = [];
  for (const bk of businessKeys) {
    const lista = await motor('GET', `/process-instance?businessKey=${encodeURIComponent(bk)}&active=true`);
    if (lista.length) {
      activas.push(bk);
    }
  }
  return activas;
}

await esperarSistemas();

// El Banco y la Bodega responden solos después de unos segundos; aquí se responde a mano.
const simulacionBanco = await interno(BANCO, 'GET', '/api/simulacion');
const simulacionBodega = await interno(BODEGA, 'GET', '/api/simulacion');
try {
  await interno(BANCO, 'PUT', '/api/simulacion', { respuestaAutomatica: false });
  await interno(BODEGA, 'PUT', '/api/simulacion', { respuestaAutomatica: false, repuestosDisponibles: true });

  await escenario('TI A: USR-01 reporta, mesa1 hace el triaje, soporte1 resuelve y USR-01 confirma',
    incidenteConfirmado);
  await escenario('TI B: USR-01 rechaza la solución, soporte1 resuelve de nuevo y USR-01 confirma',
    incidenteRechazadoYLuegoConfirmado);
  await escenario('Postventa C: cotización aceptada, repuesto adicional entregado por bodega1',
    postventaConCotizacionAceptada);
  await escenario('Postventa D: garantía por campaña aprobada, sin repuesto adicional',
    postventaEnGarantia);
  await escenario('Postventa E: el cliente rechaza la cotización',
    postventaConCotizacionRechazada);
  await escenario('Nómina F: novedades, liquidación, aprobación, lote y Banco acepta',
    nominaAceptada);
  await escenario('Nómina G: el Banco rechaza la cuenta, Tesorería la corrige y el Banco acepta',
    nominaConRechazoBancario);
} finally {
  await Promise.all([
    interno(BANCO, 'PUT', '/api/simulacion', simulacionBanco),
    interno(BODEGA, 'PUT', '/api/simulacion', simulacionBodega)
  ]);
}

const correctos = resultados.filter((resultado) => resultado.ok).length;
const verificaciones = resultados.reduce((suma, resultado) => suma + resultado.checks.length, 0);
const activas = await instanciasActivas();
console.log(`\nEscenarios correctos: ${correctos} de ${resultados.length}; verificaciones: ${verificaciones}`);
console.log(`Instancias de prueba que quedaron activas: ${activas.length}`);
// Solo queda algo activo si un escenario falló a medias: se borra para no ensuciar las bandejas.
for (const bk of activas) {
  try {
    await borrarInstancias(bk);
    console.log(`     ${bk}: instancia borrada`);
  } catch (error) {
    console.log(`     ${bk}: no se pudo borrar (${error.message})`);
  }
}
process.exit(correctos === resultados.length && activas.length === 0 ? 0 : 1);
