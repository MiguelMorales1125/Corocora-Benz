// Utilidades compartidas por permisos.mjs y portales.mjs.
// Las pruebas hablan con los portales como lo hace el navegador (sesión con cookie y token CSRF)
// y usan la credencial interna solo para lo que una persona no puede hacer desde un portal.

export const MOTOR_REST = process.env.CAMUNDA_REST_URL
  || (process.env.ENGINE_URL || 'http://localhost:9000') + '/engine-rest';
export const PORTAL_CLIENTE = process.env.PORTAL_CLIENTE_URL || 'http://localhost:9001';
export const PORTAL_TI = process.env.PORTAL_TI_URL || 'http://localhost:9002';
export const CENTRAL = process.env.CENTRAL_URL || 'http://localhost:9003';
export const BANCO = process.env.BANCO_URL || 'http://localhost:9004';
export const BODEGA = process.env.TALLER_BODEGA_URL || 'http://localhost:9005';

export const TOKEN_INTERNO = process.env.COROCORA_INTERNAL_TOKEN || 'cb-local-interno-demo';
const CONTRASENA_DEMO = process.env.COROCORA_DEMO_PASSWORD || 'demo';

export const COOKIE_CLIENTE = 'CBSESSION_CLIENTE';
export const COOKIE_TI = 'CBSESSION_TI';
export const COOKIE_CENTRAL = 'CBSESSION_CENTRAL';

const TIEMPO_MAXIMO_PETICION_MS = 30000;

export const sleep = (ms) => new Promise((resolver) => setTimeout(resolver, ms));

/** Todas las cuentas de demostración comparten contraseña, salvo USR-01, que usa su propio identificador. */
export function contrasenaDe(usuario) {
  return usuario === 'USR-01' ? 'USR-01' : CONTRASENA_DEMO;
}

async function leerCuerpo(respuesta) {
  const texto = await respuesta.text();
  if (!texto) {
    return null;
  }
  try {
    return JSON.parse(texto);
  } catch {
    return texto;
  }
}

/** Petición sin sesión ni credencial: devuelve el código y el cuerpo sin lanzar error. */
export async function anonimo(url, opciones = {}) {
  const respuesta = await fetch(url, {
    ...opciones,
    redirect: 'manual',
    signal: AbortSignal.timeout(TIEMPO_MAXIMO_PETICION_MS)
  });
  return { status: respuesta.status, body: await leerCuerpo(respuesta), headers: respuesta.headers };
}

/** Petición de sistema a sistema (credencial interna). Lanza un error con `status` si no es 2xx. */
export async function interno(base, metodo, ruta, cuerpo) {
  const respuesta = await fetch(base + ruta, {
    method: metodo,
    headers: { 'Content-Type': 'application/json', 'X-CB-Internal': TOKEN_INTERNO },
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    signal: AbortSignal.timeout(TIEMPO_MAXIMO_PETICION_MS)
  });
  const datos = await leerCuerpo(respuesta);
  if (!respuesta.ok) {
    const detalle = typeof datos === 'string' ? datos : JSON.stringify(datos);
    const error = new Error(`${metodo} ${base}${ruta} -> ${respuesta.status} ${detalle}`);
    error.status = respuesta.status;
    throw error;
  }
  return datos;
}

/** REST de Camunda con la credencial interna. */
export function motor(metodo, ruta, cuerpo) {
  return interno(MOTOR_REST, metodo, ruta, cuerpo);
}

/** Convierte {nombre: valor} al formato de variables de Camunda {nombre: {value, type}}. */
export function typed(valores) {
  const variables = {};
  for (const [nombre, valor] of Object.entries(valores)) {
    if (valor === null || valor === undefined) {
      variables[nombre] = { value: null, type: 'Null' };
    } else if (typeof valor === 'boolean') {
      variables[nombre] = { value: valor, type: 'Boolean' };
    } else if (typeof valor === 'number') {
      variables[nombre] = { value: valor, type: Number.isInteger(valor) ? 'Long' : 'Double' };
    } else {
      variables[nombre] = { value: String(valor), type: 'String' };
    }
  }
  return variables;
}

/** Quita el envoltorio {value, type} de las variables de Camunda; un valor plano se deja igual. */
export function valoresDe(variables) {
  const valores = {};
  for (const [nombre, variable] of Object.entries(variables || {})) {
    const envuelta = variable !== null && typeof variable === 'object' && 'value' in variable;
    valores[nombre] = envuelta ? variable.value : variable;
  }
  return valores;
}

/**
 * Sondea `intento` hasta que devuelva algo verdadero. Los conectores y los mensajes corren en
 * segundo plano, así que el resultado de un paso puede tardar unos segundos en verse.
 * Un error dentro de `intento` no corta la espera: se reintenta y se informa si se agota el tiempo.
 */
export async function esperar(intento, que, tiempoMaximoMs = 45000) {
  const inicio = Date.now();
  let ultimoError;
  while (Date.now() - inicio < tiempoMaximoMs) {
    try {
      const resultado = await intento();
      if (resultado) {
        return resultado;
      }
    } catch (error) {
      ultimoError = error;
    }
    await sleep(400);
  }
  const causa = ultimoError ? ` (${ultimoError.message})` : '';
  throw new Error(`Tiempo agotado esperando ${que}${causa}${await incidentesDelMotor()}`);
}

// Un incidente en el motor suele ser la causa real de que un paso nunca llegue.
async function incidentesDelMotor() {
  try {
    const incidentes = await motor('GET', '/incident');
    if (!incidentes.length) {
      return '';
    }
    const resumen = incidentes.map((incidente) => `${incidente.activityId}: ${incidente.incidentMessage}`);
    return `; incidentes en el motor: ${resumen.join(' | ')}`;
  } catch {
    return '';
  }
}

async function esperarSistema(nombre, comprobar, tiempoMaximoMs) {
  const inicio = Date.now();
  let ultimo = 'sin respuesta';
  while (Date.now() - inicio < tiempoMaximoMs) {
    try {
      const respuesta = await comprobar();
      if (respuesta.ok) {
        return;
      }
      ultimo = `HTTP ${respuesta.status}`;
    } catch (error) {
      ultimo = error.message;
    }
    await sleep(500);
  }
  throw new Error(`${nombre} no está listo después de ${tiempoMaximoMs / 1000} s: ${ultimo}`);
}

/** Espera a que respondan los seis sistemas (el motor tarda en desplegar sus modelos). */
export async function esperarSistemas(tiempoMaximoMs = 60000) {
  const conCredencial = (url) => () => fetch(url, {
    headers: { 'X-CB-Internal': TOKEN_INTERNO },
    signal: AbortSignal.timeout(3000)
  });
  const sinCredencial = (url) => () => fetch(url, { signal: AbortSignal.timeout(3000) });
  await Promise.all([
    esperarSistema('Motor', conCredencial(MOTOR_REST + '/version'), tiempoMaximoMs),
    esperarSistema('PortalCliente', sinCredencial(PORTAL_CLIENTE + '/api/app/csrf'), tiempoMaximoMs),
    esperarSistema('PortalTI', sinCredencial(PORTAL_TI + '/api/app/csrf'), tiempoMaximoMs),
    esperarSistema('CentralSys', sinCredencial(CENTRAL + '/api/app/csrf'), tiempoMaximoMs),
    esperarSistema('Banco', conCredencial(BANCO + '/api/estado'), tiempoMaximoMs),
    esperarSistema('Taller-Bodega', conCredencial(BODEGA + '/api/estado'), tiempoMaximoMs)
  ]);
}

/**
 * Sesión de una persona en UN portal: guarda sus cookies y pide el token CSRF antes de cada
 * escritura, igual que auth.js. Cada portal necesita su propia instancia.
 */
export class Session {
  constructor(base) {
    this.base = base;
    this.cookies = new Map();
    this.user = null;
  }

  recordar(respuesta) {
    for (const cabecera of respuesta.headers.getSetCookie()) {
      const partes = cabecera.split(';');
      const par = partes[0];
      const igual = par.indexOf('=');
      if (igual <= 0) {
        continue;
      }
      const nombre = par.slice(0, igual).trim();
      const valor = par.slice(igual + 1).trim();
      const caducada = partes.some((parte) => /^\s*max-age\s*=\s*0\s*$/i.test(parte));
      if (caducada || valor === '') {
        this.cookies.delete(nombre);
      } else {
        this.cookies.set(nombre, valor);
      }
    }
  }

  async call(ruta, opciones = {}) {
    const headers = new Headers(opciones.headers || {});
    if (this.cookies.size) {
      const pares = [...this.cookies].map(([nombre, valor]) => `${nombre}=${valor}`);
      headers.set('Cookie', pares.join('; '));
    }
    const respuesta = await fetch(this.base + ruta, {
      ...opciones,
      headers,
      redirect: 'manual',
      signal: AbortSignal.timeout(TIEMPO_MAXIMO_PETICION_MS)
    });
    this.recordar(respuesta);
    return respuesta;
  }

  async csrf() {
    const respuesta = await this.call('/api/app/csrf');
    if (respuesta.status !== 200) {
      throw new Error(`GET ${this.base}/api/app/csrf -> ${respuesta.status}`);
    }
    return respuesta.json();
  }

  /** Intenta iniciar sesión y devuelve el código y el cuerpo, sin lanzar error si se rechaza. */
  async intentarLogin(usuario, contrasena = contrasenaDe(usuario), { conCsrf = true } = {}) {
    const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    if (conCsrf) {
      const csrf = await this.csrf();
      headers[csrf.header] = csrf.token;
    }
    const respuesta = await this.call('/api/app/login', {
      method: 'POST',
      headers,
      body: new URLSearchParams({ username: usuario, password: contrasena })
    });
    if (respuesta.status === 204) {
      this.user = usuario;
    }
    return { status: respuesta.status, body: await leerCuerpo(respuesta) };
  }

  async login(usuario, contrasena = contrasenaDe(usuario)) {
    const resultado = await this.intentarLogin(usuario, contrasena);
    if (resultado.status !== 204) {
      const detalle = JSON.stringify(resultado.body);
      throw new Error(`Inicio de sesión de ${usuario} en ${this.base} -> ${resultado.status} ${detalle}`);
    }
    return this;
  }

  /** Llama a /api/app + ruta. Con `conCsrf: false` omite el token (para comprobar que se exige). */
  async api(ruta, { method = 'GET', body, conCsrf = true } = {}) {
    const headers = {};
    if (method !== 'GET' && conCsrf) {
      const csrf = await this.csrf();
      headers[csrf.header] = csrf.token;
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const respuesta = await this.call('/api/app' + ruta, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    return { status: respuesta.status, body: await leerCuerpo(respuesta) };
  }

  async logout() {
    const csrf = await this.csrf();
    const respuesta = await this.call('/api/app/logout', {
      method: 'POST',
      headers: { [csrf.header]: csrf.token }
    });
    return { status: respuesta.status, texto: await respuesta.text() };
  }

  /** Las mismas cookies contra otro portal: el navegador comparte las de localhost entre puertos. */
  mismasCookiesEn(base) {
    const otra = new Session(base);
    otra.cookies = new Map(this.cookies);
    return otra;
  }
}

/** Borra las instancias activas con esa businessKey (la raíz arrastra a sus subprocesos). */
export async function borrarInstancias(businessKey) {
  const ruta = '/process-instance?businessKey=' + encodeURIComponent(businessKey);
  const instancias = await motor('GET', ruta);
  let borradas = 0;
  for (const instancia of instancias) {
    try {
      await motor('DELETE', '/process-instance/' + instancia.id + '?skipCustomListeners=true');
      borradas += 1;
    } catch (error) {
      // 404: era un subproceso y ya cayó al borrar su instancia raíz.
      if (error.status !== 404) {
        throw error;
      }
    }
  }
  return borradas;
}
