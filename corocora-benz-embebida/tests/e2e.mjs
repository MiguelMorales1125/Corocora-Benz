// Pruebas punta a punta contra Camunda REST (CB-BPM-Engine).
// Requisitos: CB-BPM-Engine (9000), CB-PortalTI (9002), CB-Banco (9004) y
// CB-Taller-Bodega (9005) corriendo. Las tareas humanas se completan por REST con las variables
// de sus Camunda Forms. El Banco y la Bodega responden por sus propias APIs
// (la prueba apaga su respuesta automática y responde a mano); Cliente y usuario TI se simulan
// correlacionando mensajes, y los temporizadores se disparan ejecutando su job.
// Uso: npm run e2e
const B = process.env.CAMUNDA_REST_URL || 'http://localhost:9000/engine-rest';
const BANCO = process.env.BANCO_URL || 'http://localhost:9004';
const BODEGA = process.env.TALLER_BODEGA_URL || 'http://localhost:9005';
const TI = process.env.PORTAL_TI_URL || 'http://localhost:9002';
// Credencial de integración: las API internas rechazan llamadas sin este encabezado.
const INTERNAL = process.env.COROCORA_INTERNAL_TOKEN || 'cb-local-interno-demo';
const RUN = process.env.RUN_ID || Date.now().toString(36);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const formsSeen = { camunda: new Set() };
let current;

async function api(method, path, body, base = B) {
  const r = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-CB-Internal': INTERNAL },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const t = await r.text();
  if (!r.ok) {
    const e = new Error(`${method} ${path} -> ${r.status} ${t}`);
    e.status = r.status;
    throw e;
  }
  if (!t) return null;
  try { return JSON.parse(t); } catch { return t; }
}

// Los procesos pueden tardar varios segundos en desplegar el motor y sus modelos.
async function waitForService(base, path, name, timeoutMs = 60000) {
  const start = Date.now();
  let last;
  while (Date.now() - start < timeoutMs) {
    try {
      const response = await fetch(base + path, {
        headers: { 'X-CB-Internal': INTERNAL },
        signal: AbortSignal.timeout(3000)
      });
      if (response.ok) return;
      last = `HTTP ${response.status}`;
    } catch (error) { last = error.message; }
    await sleep(500);
  }
  throw new Error(`${name} no está listo después de ${timeoutMs / 1000} s: ${last}`);
}

await Promise.all([
  waitForService(B, '/version', 'Motor Camunda'),
  waitForService(TI, '/api/tickets?requesterId=__test__', 'Portal TI'),
  waitForService(BANCO, '/api/estado', 'Banco'),
  waitForService(BODEGA, '/api/estado', 'Taller-Bodega')
]);

function typed(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === null || v === undefined) out[k] = { value: null, type: 'Null' };
    else if (typeof v === 'boolean') out[k] = { value: v, type: 'Boolean' };
    else if (typeof v === 'number') out[k] = Number.isInteger(v) ? { value: v, type: 'Long' } : { value: v, type: 'Double' };
    else if (typeof v === 'object' && v.__typed) out[k] = v.__typed;
    else out[k] = { value: String(v), type: 'String' };
  }
  return out;
}

function check(cond, msg) {
  if (!cond) throw new Error('CHECK FAILED: ' + msg);
  current.checks.push(msg);
}

async function until(fn, what, timeoutMs = 45000) {
  const t0 = Date.now();
  let last;
  while (Date.now() - t0 < timeoutMs) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) { last = e; }
    await sleep(400);
  }
  const incidents = await api('GET', '/incident');
  throw new Error(`Timeout esperando ${what}${last ? ' (' + last.message + ')' : ''}; incidentes: ${JSON.stringify(incidents)}`);
}

async function tasks(bk, key) {
  return api('GET', `/task?processInstanceBusinessKey=${encodeURIComponent(bk)}&taskDefinitionKey=${key}`);
}

// Espera una tarea, verifica que su formulario resuelva en el deployment y la devuelve con sus form-variables.
async function waitTask(bk, key, pick) {
  const t = await until(async () => {
    const list = await tasks(bk, key);
    if (!list.length) return null;
    if (!pick) return list[0];
    for (const x of list) {
      const vars = await api('GET', `/task/${x.id}/form-variables`);
      if (pick(vars)) return x;
    }
    return null;
  }, `tarea ${key} (${bk})`);
  let form;
  if (t.camundaFormRef) {
    const f = await api('GET', `/task/${t.id}/deployed-form`);
    check(f && f.id === t.camundaFormRef.key, `${key}: Camunda Form '${t.camundaFormRef.key}' resuelto en el deployment`);
    form = 'camunda-form:' + f.id;
    formsSeen.camunda.add(f.id);
  } else {
    throw new Error(`${key}: falta un Camunda Form desplegado`);
  }
  const fv = await api('GET', `/task/${t.id}/form-variables`);
  const v = Object.fromEntries(Object.entries(fv).map(([k, x]) => [k, x.value]));
  current.steps.push(`tarea ${key} [${form}]`);
  return { ...t, v };
}

async function complete(task, vars) {
  await api('POST', `/task/${task.id}/complete`, { variables: typed(vars) });
}

async function correlate(name, bk, vars = {}) {
  const r = await until(async () => {
    try {
      return await api('POST', '/message', { messageName: name, businessKey: bk, processVariables: typed(vars), resultEnabled: true });
    } catch (e) {
      if (e.status === 400 || e.status === 500) return null; // aún no hay suscripción esperando
      throw e;
    }
  }, `suscripción al mensaje ${name} (${bk})`);
  current.steps.push(`mensaje ${name}`);
  return r;
}

// Responde por la API de CB-Banco la dispersión pendiente del proceso (accion: aceptar | rechazar).
// Reintenta mientras la dispersión no haya llegado o el proceso aún no espere la respuesta (409).
async function bancoResponde(bk, accion, body) {
  const d = await until(async () => {
    const lista = await api('GET', `/api/dispersiones?businessKey=${encodeURIComponent(bk)}`, undefined, BANCO);
    const pendiente = lista.find((x) => x.estado === 'RECIBIDA');
    if (!pendiente) return null;
    try {
      return await api('POST', `/api/dispersiones/${pendiente.id}/${accion}`, body, BANCO);
    } catch (e) {
      if (e.status === 409) return null;
      throw e;
    }
  }, `dispersión por ${accion} en CB-Banco (${bk})`);
  current.steps.push(`CB-Banco ${accion} dispersión ${d.id}`);
  return d;
}

// Entrega por la API de CB-Taller-Bodega el repuesto solicitado por el proceso.
async function bodegaEntrega(bk) {
  const s = await until(async () => {
    const lista = await api('GET', `/api/solicitudes-repuesto?businessKey=${encodeURIComponent(bk)}`, undefined, BODEGA);
    const pendiente = lista.find((x) => x.estado === 'SOLICITADA');
    if (!pendiente) return null;
    try {
      return await api('POST', `/api/solicitudes-repuesto/${pendiente.id}/entregar`, undefined, BODEGA);
    } catch (e) {
      if (e.status === 409) return null;
      throw e;
    }
  }, `solicitud de repuesto en CB-Taller-Bodega (${bk})`);
  current.steps.push(`CB-Taller-Bodega entrega ${s.partRequestId}`);
  return s;
}

async function instances(bk) {
  return api('GET', `/process-instance?businessKey=${encodeURIComponent(bk)}`);
}

async function timerJob(bk, activityId) {
  return until(async () => {
    for (const pi of await instances(bk)) {
      const jobs = await api('GET', `/job?processInstanceId=${pi.id}&activityId=${activityId}&timers=true`);
      if (jobs.length) return jobs[0];
    }
    return null;
  }, `temporizador ${activityId} (${bk})`);
}

async function fireTimer(bk, activityId) {
  const job = await timerJob(bk, activityId);
  await api('POST', `/job/${job.id}/execute`);
  current.steps.push(`timer ${activityId} disparado`);
}

function hoursUntil(job) {
  return Math.round((new Date(job.dueDate) - Date.now()) / 36e5);
}

async function rootInstance(bk, defKey) {
  const list = await api('GET', `/history/process-instance?processInstanceBusinessKey=${encodeURIComponent(bk)}&processDefinitionKey=${defKey}`);
  return list[0];
}

async function variable(piId, name) {
  const list = await api('GET', `/history/variable-instance?processInstanceId=${piId}&variableName=${name}`);
  return list.length ? list[0].value : undefined;
}

async function waitEnd(bk, defKey, endId) {
  const pi = await until(async () => {
    const h = await rootInstance(bk, defKey);
    return h && h.state === 'COMPLETED' ? h : null;
  }, `fin de ${defKey} (${bk})`);
  const ends = await api('GET', `/history/activity-instance?processInstanceId=${pi.id}&activityType=noneEndEvent`);
  check(ends.some((e) => e.activityId === endId), `${defKey} terminó en ${endId}`);
  const children = await api('GET', `/history/process-instance?superProcessInstanceId=${pi.id}`);
  const done = children.filter((c) => c.state === 'COMPLETED').length;
  const cut = children.filter((c) => c.state === 'INTERNALLY_TERMINATED').length;
  check(done + cut === children.length, `${children.length} instancia(s) hija(s): ${done} completadas, ${cut} interrumpidas por timer`);
  current.cut = cut;
  current.steps.push(`FIN ${endId}`);
  return pi;
}

async function scenario(name, fn) {
  current = { name, steps: [], checks: [], ok: false };
  const t0 = Date.now();
  try {
    await fn();
    current.ok = true;
  } catch (e) {
    current.error = e.message;
  }
  current.ms = Date.now() - t0;
  results.push(current);
  console.log(`${current.ok ? 'OK  ' : 'FAIL'} ${name} (${(current.ms / 1000).toFixed(1)} s, ${current.checks.length} verificaciones)`);
  if (!current.ok) console.log('     ' + current.error);
}

// El Banco y la Bodega responden solos después de unos segundos; en la prueba se responde a mano.
const estadoBanco = await api('GET', '/api/simulacion', undefined, BANCO);
const estadoBodega = await api('GET', '/api/simulacion', undefined, BODEGA);
const simulacionManual = { respuestaAutomatica: false };
try {
await api('PUT', '/api/simulacion', simulacionManual, BANCO);
await api('PUT', '/api/simulacion', { ...simulacionManual, repuestosDisponibles: true }, BODEGA);

// ---------------------------------------------------------------- Postventa
async function startPostventa(bk, vin, mileageKm) {
  await api('POST', '/message', {
    messageName: 'SolicitudServicio',
    businessKey: bk,
    processVariables: typed({
      vin, customerName: 'Cliente de prueba', mileageKm, serviceType: 'Garantía',
      symptom: 'La cámara de reversa no muestra imagen', evidenceUrl: 'https://example.invalid/evidencia.jpg'
    })
  });
  current.steps.push('mensaje SolicitudServicio (inicio)');
  const r = await waitTask(bk, 'registerRequest');
  check(r.v.vin === vin && r.v.symptom, 'registrar solicitud: el asesor ve VIN y síntoma del portal');
  await complete(r, { dealerId: 'TALLER-BOG-01', registrationNote: '' });
}

async function workshop(bk, { partNeeded = false, inspections = [true] } = {}) {
  const sched = await waitTask(bk, 'scheduleWorkshop');
  check(sched.v.dealerId === 'TALLER-BOG-01', 'agendar cita: muestra el taller asignado en el registro');
  await complete(sched, { appointmentDate: '2026-11-20 09:00', serviceOrderId: `OT-${bk}` });
  const dx = await waitTask(bk, 'diagnoseVehicle');
  check(dx.v.vin && dx.v.symptom && dx.v.mileageKm != null, 'taller recibe VIN, síntoma y kilometraje por la Call Activity');
  await complete(dx, { mileageKm: Number(dx.v.mileageKm) + 5, diagnosis: 'Falla en el módulo de cámara', additionalPartNeeded: partNeeded, partCode: partNeeded ? 'CAM-123' : '' });
  if (partNeeded) {
    const s = await bodegaEntrega(bk);
    check(s.partCode === 'CAM-123' && s.estado === 'ENTREGADA', 'CB-Taller-Bodega recibió el repuesto pedido y avisó RepuestoRecibido');
  }
  for (const ok of inspections) {
    const rep = await waitTask(bk, 'repairVehicle');
    check(rep.v.diagnosis === 'Falla en el módulo de cámara', 'reparación muestra el diagnóstico');
    await complete(rep, { repairNotes: 'Se reemplazó el módulo', serviceCost: 350000 });
    const ins = await waitTask(bk, 'inspectRepair');
    await complete(ins, { inspectionPassed: ok, inspectionResults: ok ? 'Prueba OK' : 'Imagen intermitente' });
  }
  const del = await waitTask(bk, 'deliverVehicle');
  check(del.v.serviceCost === 350000 && del.v.repairNotes, 'entrega muestra trabajo y costo devueltos por el taller');
  await complete(del, { customerSatisfaction: 5, deliveryNote: 'Entregado' });
}

await scenario('Postventa A: recall activo, garantía aprobada, AND, taller con una inspección fallida', async () => {
  const bk = `PV-A-${RUN}`;
  await startPostventa(bk, 'CB0TEST0000000001', 15000);
  const rw = await waitTask(bk, 'reviewWarranty');
  check(rw.v.coverageStatus === 'CAMPANA_ACTIVA' && rw.v.coverageReason, 'DMN (Outputs) = CAMPANA_ACTIVA visible en el formulario');
  await complete(rw, { coverageApproved: true, warrantyNote: 'Recall de cámara' });
  await workshop(bk, { inspections: [false, true] });
  const pi = await waitEnd(bk, 'CB_PostventaGarantias', 'closedEnd');
  check((await variable(pi.id, 'customerSatisfaction')) === 5, 'satisfacción del cliente registrada al cerrar');
  check((await variable(pi.id, 'campaignId')) === 'RECALL-CAMARA-2026', 'campaña consultada en CB-Taller-Bodega por HTTP Connector');
});

await scenario('Postventa B: fuera de garantía, cotización rechazada', async () => {
  const bk = `PV-B-${RUN}`;
  await startPostventa(bk, 'WDB0000000000000B', 80000);
  await correlate('CotizacionRechazada', bk);
  const pi = await waitEnd(bk, 'CB_PostventaGarantias', 'unauthorizedEnd');
  check((await variable(pi.id, 'coverageStatus')) === 'REQUIERE_COTIZACION', 'DMN = REQUIERE_COTIZACION (80.000 km)');
  const cotizaciones = await api('GET', `/api/cotizaciones?businessKey=${encodeURIComponent(bk)}`, undefined, BODEGA);
  check(cotizaciones[0]?.estado === 'RECHAZADA', 'Bodega refleja la cotización rechazada');
  const quoteId = await variable(pi.id, 'quoteId');
  check(String(quoteId).startsWith('COT-') && (await variable(pi.id, 'quoteAmount')) === 850000, `cotización ${quoteId} creada en CB-Taller-Bodega`);
});

await scenario('Postventa C: garantía no aprobada, cotización aceptada, repuesto adicional', async () => {
  const bk = `PV-C-${RUN}`;
  await startPostventa(bk, 'WDB0000000000000C', 20000);
  const rw = await waitTask(bk, 'reviewWarranty');
  check(rw.v.coverageStatus === 'CUBIERTO_POR_GARANTIA', 'DMN = CUBIERTO_POR_GARANTIA');
  await complete(rw, { coverageApproved: false, warrantyNote: 'Daño por mal uso' });
  await correlate('CotizacionAceptada', bk);
  const aceptadas = await api('GET', `/api/cotizaciones?businessKey=${encodeURIComponent(bk)}`, undefined, BODEGA);
  check(aceptadas[0]?.estado === 'ACEPTADA', 'Bodega refleja la cotización aceptada');
  await workshop(bk, { partNeeded: true });
  await waitEnd(bk, 'CB_PostventaGarantias', 'closedEnd');
});

await scenario('Postventa D: cotización sin respuesta, vence el timer de 48 h', async () => {
  const bk = `PV-D-${RUN}`;
  await startPostventa(bk, 'WDB0000000000000D', 70000);
  check(hoursUntil(await timerJob(bk, 'quoteExpired')) === 48, 'timer de cotización a 48 h');
  await fireTimer(bk, 'quoteExpired');
  await waitEnd(bk, 'CB_PostventaGarantias', 'unauthorizedEnd');
  const vencidas = await api('GET', `/api/cotizaciones?businessKey=${encodeURIComponent(bk)}`, undefined, BODEGA);
  check(vencidas[0]?.estado === 'VENCIDA', 'Bodega refleja la cotización vencida');
});

await scenario('Postventa E: Bodega avisa cuando el repuesto vuelve a estar disponible', async () => {
  const bk = `PV-E-${RUN}`;
  await api('PUT', '/api/simulacion', {repuestosDisponibles:false}, BODEGA);
  try {
    await startPostventa(bk, 'CB0TEST0000000001', 15000);
    const rw = await waitTask(bk, 'reviewWarranty');
    await complete(rw, { coverageApproved: true, warrantyNote: 'Campaña' });
    const pending = await until(async () => {
      const list = await api('GET', '/api/reservas', undefined, BODEGA);
      const r = list.find(x => x.businessKey === bk);
      return r && r.estado === 'PENDIENTE' ? r : null;
    }, `reserva pendiente (${bk})`);
    await until(async () => {
      const root = (await instances(bk))[0];
      if (!root) return null;
      const subscriptions = await api('GET', `/execution?processInstanceId=${root.id}&messageEventSubscriptionName=RepuestosDisponibles`);
      return subscriptions.length ? subscriptions[0] : null;
    }, `mensaje RepuestosDisponibles (${bk})`);
    check((await tasks(bk, 'diagnoseVehicle')).length === 0, 'el taller no inicia mientras falta el repuesto');
    const delivered = await api('POST', `/api/reservas/${pending.id}/disponibles`, undefined, BODEGA);
    check(delivered.estado === 'RESERVED', 'Bodega correlacionó el aviso y actualizó la reserva');
    const duplicate = await api('POST', `/api/reservas/${pending.id}/disponibles`, undefined, BODEGA);
    check(duplicate.estado === 'RESERVED', 'el aviso repetido es idempotente');
    await workshop(bk);
    await waitEnd(bk, 'CB_PostventaGarantias', 'closedEnd');
  } finally {
    await api('PUT', '/api/simulacion', {repuestosDisponibles:true}, BODEGA);
  }
});

// ---------------------------------------------------------------- TI
async function startTI(bk, v) {
  await api('POST', '/process-definition/key/CB_GestionIncidenteTI/submit-form', {
    businessKey: bk,
    variables: typed({ requesterId: 'usuario.prueba', systemName: 'Software del vehículo', description: 'La cámara de reversa no carga', ...v })
  });
  current.steps.push('formulario de inicio ti-reporte');
}

async function resolve(bk, key) {
  const t = await waitTask(bk, key);
  check(t.v.ticketId && t.v.priority && t.v.description, `${key}: el técnico ve ticket, prioridad (${t.v.priority}) y descripción`);
  await complete(t, { rootCause: 'Versión de firmware con error', resolutionSummary: 'Actualización OTA aplicada' });
}

await scenario('TI A: P3, Nivel 1, usuario confirma', async () => {
  const bk = `TI-A-${RUN}`;
  await startTI(bk, { impact: 'Medio', urgency: 'Media', safetyImpact: false, affectedTeams: 1 });
  const tr = await waitTask(bk, 'triageIncident');
  check(tr.v.priority === 'P3' && tr.v.targetDuration === 'PT24H', 'triaje ve P3/PT24H calculado por el DMN');
  await complete(tr, { technicalDomain: 'catalogoNivel1' });
  check(hoursUntil(await timerJob(bk, 'slaTimer')) === 24, 'timer de resolución a 24 h');
  await resolve(bk, 'resolveN1');
  const confirmation = await waitTask(bk, 'confirmSolution');
  const tickets = await api('GET', '/api/tickets?requesterId=usuario.prueba', undefined, TI);
  const ticket = tickets.find(t => t.businessKey === bk);
  check(ticket && ticket.status === 'RESOLVED' && ticket.resolutionSummary === 'Actualización OTA aplicada', 'Portal TI guarda la solución');
  await complete(confirmation, { userConfirmed: true });
  const pi = await waitEnd(bk, 'CB_GestionIncidenteTI', 'incidentEnd');
  check((await variable(pi.id, 'ticketStatus')) === 'CLOSED', 'ticket cerrado');
  const cerrados = await api('GET', '/api/tickets?requesterId=usuario.prueba', undefined, TI);
  check(cerrados.find(t => t.businessKey === bk)?.status === 'CLOSED', 'Portal TI registra el cierre');
});

await scenario('TI B: P1 por seguridad, vence el plazo, usuario rechaza y luego no responde', async () => {
  const bk = `TI-B-${RUN}`;
  await startTI(bk, { impact: 'Bajo', urgency: 'Baja', safetyImpact: true, affectedTeams: 1 });
  const tr = await waitTask(bk, 'triageIncident');
  check(tr.v.priority === 'P1', 'DMN: seguridad => P1');
  await complete(tr, { technicalDomain: 'especializado' });
  check(hoursUntil(await timerJob(bk, 'slaTimer')) === 2, 'timer de resolución a 2 h');
  await waitTask(bk, 'resolveSpecialist');
  await fireTimer(bk, 'slaTimer');
  const rs = await waitTask(bk, 'reviewSla');
  check((await tasks(bk, 'resolveSpecialist')).length === 0, 'timer interruptivo cancela la resolución en curso');
  check(rs.v.ticketId && rs.v.priority === 'P1', 'responsable ve ticket y prioridad');
  await complete(rs, { recoveryPlan: 'Escalar al fabricante de la cámara', newOwner: 'especialista.2' });
  await resolve(bk, 'resolveSpecialist');
  const rejected = await waitTask(bk, 'confirmSolution');
  await complete(rejected, { userConfirmed: false });
  await waitTask(bk, 'resolveSpecialist');
  const reabiertos = await api('GET', '/api/tickets?requesterId=usuario.prueba', undefined, TI);
  check(reabiertos.find(t => t.businessKey === bk)?.status === 'OPEN', 'Portal TI refleja la reapertura');
  await resolve(bk, 'resolveSpecialist');
  check(hoursUntil(await timerJob(bk, 'noResponse')) === 24, 'espera de confirmación a 24 h');
  await fireTimer(bk, 'noResponse');
  await waitEnd(bk, 'CB_GestionIncidenteTI', 'autoCloseEnd');
  check(current.cut === 1, 'exactamente una resolución interrumpida por el plazo vencido');
});

// ---------------------------------------------------------------- Nómina
async function startNomina(bk, extra = {}) {
  await api('POST', '/process-definition/key/CB_GestionNomina/submit-form', {
    businessKey: bk,
    variables: typed({ payPeriodId: '2026-11-Q2', payCycle: 'QUINCENAL', cutoffDate: '2026-11-25', ...extra })
  });
  current.steps.push('formulario de inicio nomina-periodo');
}

async function validateChanges(bk, n, rejectEmployee) {
  const seen = new Set();
  let rejected = false;
  while (seen.size < n) {
    const t = await waitTask(bk, 'reviewChange', (v) => !seen.has(v.employeeId.value));
    const emp = t.v.employeeId;
    check(t.v.changeType && t.v.changeValue != null, `novedad ${emp}: datos mapeados desde employeeChange`);
    if (emp === rejectEmployee && !rejected) {
      rejected = true;
      await complete(t, { changeValid: false, reviewComment: 'Falta incapacidad firmada' });
      const c = await waitTask(bk, 'correctChange');
      check(c.v.reviewComment === 'Falta incapacidad firmada' && c.v.employeeId === emp, 'Talento Humano ve empleado y observación');
      await complete(c, { changeValue: 1, supportAttached: true, changeCorrection: 'Se adjuntó soporte' });
      continue;
    }
    await complete(t, { changeValid: true, reviewComment: '' });
    seen.add(emp);
  }
}

async function payAndClose(bk, { loops = false } = {}) {
  let rv = await waitTask(bk, 'reviewPayroll');
  check(rv.v.payrollSummary && rv.v.payrollTotal === 1000000, 'analista ve resumen y total calculado');
  if (loops) {
    await complete(rv, { payrollConsistent: false, reviewComment: 'Horas extra mal liquidadas' });
    rv = await waitTask(bk, 'reviewPayroll');
  }
  await complete(rv, { payrollConsistent: true, reviewComment: '' });
  let ap = await waitTask(bk, 'approvePayroll');
  check(ap.v.payrollTotal === 1000000 && ap.v.payrollSummary, 'Jefatura ve resumen y total (solo lectura)');
  if (loops) {
    await complete(ap, { payrollApproved: false, approvalReason: 'Falta descuento' });
    rv = await waitTask(bk, 'reviewPayroll');
    await complete(rv, { payrollConsistent: true, reviewComment: 'Descuento aplicado' });
    ap = await waitTask(bk, 'approvePayroll');
  }
  await complete(ap, { payrollApproved: true, approvalReason: 'Aprobada' });
  const pb = await waitTask(bk, 'prepareBankBatch');
  check(pb.v.payrollTotal === 1000000, 'Tesorería ve el total aprobado');
  await complete(pb, { paymentBatchId: `LOTE-${bk}`, bankAccount: loops ? 'cuenta-invalida' : '000-123' });
  if (loops) {
    check(hoursUntil(await timerJob(bk, 'bankTimeout')) === 2, 'timer bancario a 2 h');
    await fireTimer(bk, 'bankTimeout');
    let fx = await waitTask(bk, 'fixBankBatch');
    await complete(fx, { paymentBatchId: `LOTE-${bk}`, correction: 'Se consultó al banco' });
    await bancoResponde(bk, 'rechazar', { codigo: 'R03-CUENTA-INVALIDA' });
    fx = await waitTask(bk, 'fixBankBatch');
    check(fx.v.bankResponseCode === 'R03-CUENTA-INVALIDA', 'Tesorería ve el código de rechazo de CB-Banco');
    await complete(fx, { paymentBatchId: `LOTE-${bk}`, bankAccount: '000-123', correction: 'Cuenta corregida' });
  }
  const d = await bancoResponde(bk, 'aceptar');
  check(d.paymentBatchId === `LOTE-${bk}` && d.payrollTotal === 1000000 && d.bankAccount === '000-123', 'CB-Banco recibió el lote, el total y la cuenta corregida por HTTP Connector');
  const pi = await waitEnd(bk, 'CB_GestionNomina', 'payrollEnd');
  check(String(await variable(pi.id, 'bankReference')).startsWith('BANCO-'), 'referencia bancaria devuelta por CB-Banco');
  return pi;
}

await scenario('Nómina A: CONTINUAR, novedad corregida, recálculo, devolución de Jefatura, timeout y rechazo bancario', async () => {
  const bk = `NOM-A-${RUN}`;
  await startNomina(bk);
  await validateChanges(bk, 2, 'EMP-002');
  await payAndClose(bk, { loops: true });
});

for (const [label, extra, setup] of [
  ['Nómina B: periodo cerrado y reapertura', { simulatePeriodClosed: true }, async (bk) => {
    const c = await waitTask(bk, 'correctChanges');
    check(c.v.reasonCode === 'REQUIERE_REAPERTURA', 'Talento Humano ve REQUIERE_REAPERTURA (Outputs del DMN)');
    await complete(c, { correctionAction: 'REQUEST_REOPEN', correctionNotes: 'Reapertura aprobada' });
    return 2;
  }],
  ['Nómina C: soportes incompletos', { simulateMissingSupports: true }, async (bk) => {
    const c = await waitTask(bk, 'correctChanges');
    check(c.v.reasonCode === 'SOPORTE_INCOMPLETO', 'Talento Humano ve SOPORTE_INCOMPLETO');
    await complete(c, { correctionAction: 'FIX_RECORDS', correctionNotes: 'Soportes cargados' });
    return 2;
  }],
  ['Nómina D: novedad fuera de corte rechazada por Jefatura', { simulateLateChanges: true }, async (bk) => {
    const a = await waitTask(bk, 'approveLateChanges');
    check(a.v.lateChangesSummary && a.v.lateChangesSummary.includes('EMP-003'), 'Jefatura ve la novedad fuera de corte');
    await complete(a, { lateChangesApproved: false, lateDecisionReason: 'Sin autorización del área' });
    const c = await waitTask(bk, 'correctChanges');
    await complete(c, { correctionAction: 'FIX_RECORDS', correctionNotes: 'Se retiró la novedad tardía' });
    return 2;
  }],
  ['Nómina E: novedad fuera de corte aprobada (3 novedades en multiinstancia)', { simulateLateChanges: true }, async (bk) => {
    const a = await waitTask(bk, 'approveLateChanges');
    await complete(a, { lateChangesApproved: true, lateDecisionReason: 'Autorizada por gerencia' });
    return 3;
  }]
]) {
  await scenario(label, async () => {
    const bk = `NOM-${label[7]}-${RUN}`;
    await startNomina(bk, extra);
    const n = await setup(bk);
    await validateChanges(bk, n);
    const pi = await payAndClose(bk);
    const kids = await api('GET', `/history/process-instance?superProcessInstanceId=${pi.id}`);
    check(kids.length === n, `multiinstancia creó ${n} validaciones de novedad`);
  });
}

// ---------------------------------------------------------------- resumen
} finally {
  await Promise.all([
    api('PUT', '/api/simulacion', estadoBanco, BANCO),
    api('PUT', '/api/simulacion', estadoBodega, BODEGA)
  ]);
}
const incidents = await api('GET', '/incident');
const open = await api('GET', `/process-instance?businessKeyLike=%25${RUN}`);
const totalChecks = results.reduce((s, r) => s + r.checks.length, 0);
console.log(`\nVerificaciones: ${totalChecks}; formularios Camunda usados: ${[...formsSeen.camunda].sort().join(', ')}`);
console.log(`Incidentes en el engine: ${incidents.length}; instancias de prueba activas: ${open.length}`);
process.exit(results.every((r) => r.ok) && !incidents.length && !open.length ? 0 : 1);
