// Comprueba autenticación y autorización de la API de aplicación (misma origen que los portales).
// Requisitos: CB-BPM-Engine, CB-PortalTI y CB-Taller-Bodega en H2.
import assert from 'node:assert/strict';
const ENGINE = process.env.ENGINE_URL || 'http://localhost:9000';
const TI = process.env.PORTAL_TI_URL || 'http://localhost:9002';
const BODEGA = process.env.TALLER_BODEGA_URL || 'http://localhost:9005';
const INTERNAL = process.env.COROCORA_INTERNAL_TOKEN || 'cb-local-interno-demo';
const PASSWORD = process.env.COROCORA_DEMO_PASSWORD || 'demo';
const RUN = 'AUTH-' + Date.now().toString(36);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function internal(path, method = 'GET', body) {
  const r = await fetch(ENGINE + '/engine-rest' + path, {
    method,
    headers: {'Content-Type':'application/json', 'X-CB-Internal':INTERNAL},
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await r.text();
  let data; try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  assert.ok(r.ok, `${method} ${path} -> ${r.status} ${text}`);
  return data;
}

function typed(obj) {
  return Object.fromEntries(Object.entries(obj).map(([k, v]) => [k, {
    value: v, type: typeof v === 'boolean' ? 'Boolean' : Number.isInteger(v) ? 'Long' : 'String'
  }]));
}

class Session {
  constructor() { this.cookies = new Map(); }
  remember(response) {
    const list = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : (response.headers.get('set-cookie') ? [response.headers.get('set-cookie')] : []);
    for (const raw of list) {
      const pair = raw.split(';', 1)[0];
      const at = pair.indexOf('=');
      if (at > 0) this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
  }
  async call(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (this.cookies.size) headers.set('Cookie', [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '));
    const response = await fetch(ENGINE + path, {...options, headers, redirect:'manual'});
    this.remember(response);
    return response;
  }
  async csrf() {
    const r = await this.call('/api/app/csrf');
    assert.equal(r.status, 200, 'csrf disponible');
    return r.json();
  }
  async login(user) {
    const c = await this.csrf();
    const r = await this.call('/api/app/login', {
      method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded', [c.header]:c.token},
      body:new URLSearchParams({username:user, password:PASSWORD})
    });
    assert.equal(r.status, 204, `inicio de sesión de ${user}`);
    return this;
  }
  async api(path, {method='GET', body} = {}) {
    const headers = {};
    if (method !== 'GET') {
      const c = await this.csrf();
      headers[c.header] = c.token;
      headers['Content-Type'] = 'application/json';
    }
    const r = await this.call('/api/app' + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
    const text = await r.text();
    let data; try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    return {status:r.status, body:data};
  }
  async logout() {
    const c = await this.csrf();
    return this.call('/api/app/logout', {method:'POST', headers:{[c.header]:c.token}});
  }
}

async function waitFor(fn, what, timeoutMs = 20000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const found = await fn();
    if (found) return found;
    await sleep(350);
  }
  throw new Error('Timeout esperando ' + what);
}

async function cancelProcesses(businessKeyLike) {
  const list = await internal('/process-instance?businessKeyLike=' + encodeURIComponent(businessKeyLike));
  for (const pi of list) await internal('/process-instance/' + pi.id, 'DELETE');
}

const createdIncidents = [];
const createdRequests = [];
try {
  // La API de aplicación no responde sin sesión.
  assert.equal((await new Session().api('/central/tasks')).status, 401, 'sin sesión no hay bandeja');

  // Empleado de Mesa de Servicio: grupos reales y tareas de su grupo.
  const mesa = await new Session().login('mesa1');
  const me = await mesa.api('/me');
  assert.equal(me.status, 200);
  assert.equal(me.body.user, 'mesa1');
  assert.ok(me.body.groups.includes('mesa-servicio'), 'mesa1 pertenece a mesa-servicio');
  assert.equal(me.body.cliente, false);

  const incidentKey = `TI-${RUN}`;
  createdIncidents.push(incidentKey);
  await internal('/process-definition/key/CB_GestionIncidenteTI/submit-form', 'POST', {
    businessKey: incidentKey,
    variables: typed({requesterId:`cliente-ti-${RUN}`, systemName:'ERP', description:'No carga la bandeja',
      impact:'Medio', urgency:'Media', safetyImpact:false, affectedTeams:1})
  });
  const triage = await waitFor(async () => {
    const r = await mesa.api('/central/tasks');
    assert.equal(r.status, 200);
    return r.body.find(t => t.businessKey === incidentKey && t.taskDefinitionKey === 'triageIncident');
  }, 'la tarea de triaje en la bandeja de mesa1');

  // Otro grupo no puede reclamar ni abrir la tarea.
  const responsable = await new Session().login('responsable1');
  assert.equal((await responsable.api('/central/tasks/' + triage.id + '/claim', {method:'POST', body:{}})).status, 403,
    'responsable1 no puede reclamar una tarea de mesa-servicio');
  assert.equal((await responsable.api('/central/tasks/' + triage.id + '/deployed-form')).status, 403,
    'responsable1 no puede leer el formulario de una tarea ajena');

  // El grupo correcto sí puede reclamarla y completarla.
  assert.equal((await mesa.api('/central/tasks/' + triage.id + '/claim', {method:'POST', body:{}})).status, 200,
    'mesa1 reclama la tarea de su grupo');
  const form = await mesa.api('/central/tasks/' + triage.id + '/deployed-form');
  assert.equal(form.status, 200);
  assert.ok(JSON.stringify(form.body).includes('technicalDomain'), 'el formulario de triaje está desplegado');
  assert.equal((await mesa.api('/central/tasks/' + triage.id + '/complete', {
    method:'POST', body:{variables:{technicalDomain:{value:'catalogoNivel1', type:'String'}}}
  })).status, 200, 'mesa1 completa la tarea');

  // Un cliente no puede usar la bandeja de empleados.
  const cliente1 = await new Session().login('cliente1');
  assert.equal((await cliente1.api('/central/tasks')).status, 403, 'cliente1 no accede a la bandeja interna');
  // Un empleado no puede abrir solicitudes de cliente.
  assert.equal((await mesa.api('/client/requests')).status, 403, 'mesa1 no accede al portal de cliente');

  // Identidad de cliente: cada solicitud pertenece a quien la creó.
  const requestKey = `PV-${RUN}`;
  const started = await cliente1.api('/client/requests', {method:'POST', body:{
    customerName:'Cliente autenticado', customerEmail:'cliente1@example.invalid', vin:'WDB0000000000000B',
    mileageKm:15000, serviceType:'Garantía', symptom:'Ruido en la suspensión'
  }});
  assert.equal(started.status, 200, 'cliente1 crea una solicitud');
  const clientBk = started.body.businessKey;
  createdRequests.push(clientBk);
  const mine = await cliente1.api('/client/requests');
  assert.equal(mine.status, 200);
  assert.ok(mine.body.some(x => x.businessKey === clientBk), 'cliente1 ve su propia solicitud');
  assert.ok(mine.body.every(x => x.id && x.variables), 'las solicitudes no exponen datos de otros clientes');

  const cliente2 = await new Session().login('cliente2');
  const other = await cliente2.api('/client/requests');
  assert.ok(!other.body.some(x => x.businessKey === clientBk), 'cliente2 no ve la solicitud de cliente1');
  assert.equal((await cliente2.api('/client/requests/' + encodeURIComponent(clientBk) + '/answer', {
    method:'POST', body:{action:'aceptar'}
  })).status, 404, 'cliente2 no puede responder por cliente1');

  // Permisos por grupo en la API de Bodega.
  const bodega = await new Session().login('bodega1');
  assert.equal((await bodega.api('/bodega/reservas')).status, 200, 'bodega1 consulta reservas');
  const asesor = await new Session().login('asesor1');
  assert.equal((await asesor.api('/bodega/reservas')).status, 403, 'asesor1 no consulta reservas');

  // Cierre de sesión.
  assert.equal((await (await mesa.logout()).text()).length, 0);
  assert.equal((await mesa.api('/central/tasks')).status, 401, 'al cerrar sesión se pierde el acceso');

  // Las API internas rechazan llamadas sin credencial.
  assert.equal((await fetch(ENGINE + '/engine-rest/group')).status, 401, 'motor protegido');
  assert.equal((await fetch(TI + '/api/tickets?requesterId=x')).status, 401, 'Portal TI protegido');
  assert.equal((await fetch(BODEGA + '/api/reservas')).status, 401, 'Bodega protegida');

  console.log('OK: inicio de sesión, grupos, tareas por permiso, aislamiento de clientes y APIs internas protegidas');
} finally {
  await cancelProcesses(`%${RUN}%`).catch(() => {});
  for (const bk of createdRequests) await cancelProcesses(bk).catch(() => {});
}
