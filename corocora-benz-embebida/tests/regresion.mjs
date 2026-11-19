// Casos límite de las API internas: validaciones, transiciones de ticket, reintentos idempotentes
// y exigencia de la credencial interna (contrato, sección 2).
// Requisitos: PortalTI (9002), Banco (9004) y Taller-Bodega (9005). No necesita el motor.
// Uso: npm run regresion
import assert from 'node:assert/strict';

const urls = {
  ti: process.env.PORTAL_TI_URL || 'http://localhost:9002',
  bodega: process.env.TALLER_BODEGA_URL || 'http://localhost:9005',
  banco: process.env.BANCO_URL || 'http://localhost:9004'
};
const internal = process.env.COROCORA_INTERNAL_TOKEN || 'cb-local-interno-demo';
const id = 'REG-' + Date.now();

/** Con `data` hace POST; sin él, GET. Siempre con la credencial interna. */
async function request(base, path, data) {
  const opciones = data === undefined
    ? { headers: { 'X-CB-Internal': internal } }
    : {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CB-Internal': internal },
      body: JSON.stringify(data)
    };
  const respuesta = await fetch(base + path, opciones);
  const text = await respuesta.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    body = text;
  }
  return { status: respuesta.status, body };
}

// ---------------------------------------------------------------- credencial interna
const sinCredencial = [
  ['Portal TI', urls.ti + '/api/tickets'],
  ['Bodega', urls.bodega + '/api/vehiculos/X?km=10'],
  ['Banco', urls.banco + '/api/dispersiones']
];
for (const [sistema, url] of sinCredencial) {
  const respuesta = await fetch(url);
  assert.equal(respuesta.status, 401, `${sistema} exige la credencial interna`);
  assert.deepEqual(await respuesta.json(), { error: 'Credencial interna requerida' }, `cuerpo del 401 de ${sistema}`);
}
const credencialMala = await fetch(urls.banco + '/api/dispersiones', { headers: { 'X-CB-Internal': internal + '-otra' } });
assert.equal(credencialMala.status, 401, 'una credencial incorrecta se rechaza');
const conBearer = await fetch(urls.banco + '/api/estado', { headers: { Authorization: 'Bearer ' + internal } });
assert.equal(conBearer.status, 200, 'la credencial también se acepta como Authorization: Bearer');

// ---------------------------------------------------------------- tickets de Portal TI
const ticket = {
  businessKey: id,
  requesterId: id,
  systemName: 'ERP',
  description: 'No inicia sesión',
  impact: 'Medio',
  urgency: 'Media',
  safetyImpact: false,
  affectedTeams: 1
};
assert.equal((await request(urls.ti, '/api/tickets', { businessKey: id })).status, 400, 'ticket incompleto');
const created = await request(urls.ti, '/api/tickets', ticket);
assert.equal(created.status, 200, 'ticket creado');
const duplicateTicket = await request(urls.ti, '/api/tickets', ticket);
assert.equal(duplicateTicket.body.ticketId, created.body.ticketId, 'reintento de ticket');
const incompatible = await request(urls.ti, '/api/tickets', { ...ticket, description: 'Otro' });
assert.equal(incompatible.status, 409, 'clave de ticket con datos incompatibles');

const tid = created.body.ticketId;
const cerrado = await request(urls.ti, `/api/tickets/${tid}/cerrar`, {});
assert.equal(cerrado.body.ticketStatus, 'CLOSED', 'ticket cerrado');
const solucionTardia = await request(urls.ti, `/api/tickets/${tid}/solucion`, {
  rootCause: 'Causa',
  resolutionSummary: 'Solución'
});
assert.equal(solucionTardia.status, 409, 'no modificar ticket cerrado');
assert.equal((await request(urls.ti, '/api/tickets/TI-incorrecto/cerrar', {})).status, 404, 'ticket inexistente');

// ---------------------------------------------------------------- vehículos y cotizaciones de Bodega
assert.equal((await request(urls.bodega, '/api/vehiculos/X?km=10')).status, 400, 'VIN corto');
assert.equal((await request(urls.bodega, '/api/vehiculos/WDB0000000000000B?km=-1')).status, 400, 'kilometraje negativo');
const unknown = await request(urls.bodega, '/api/vehiculos/WDB0000000000000Z?km=0');
assert.equal(unknown.body.registrado, false, 'VIN fuera del catálogo');
assert.equal(unknown.body.warrantyEligible, false, 'VIN fuera del catálogo sin garantía');

assert.equal((await request(urls.bodega, '/api/cotizaciones', {})).status, 400, 'cotización incompleta');
const quote = { businessKey: id, vin: 'WDB0000000000000B', customerName: 'Prueba', symptom: 'Ruido' };
const q1 = await request(urls.bodega, '/api/cotizaciones', quote);
const q2 = await request(urls.bodega, '/api/cotizaciones', quote);
assert.equal(q1.body.quoteId, q2.body.quoteId, 'reintento de cotización');
const otraCotizacion = await request(urls.bodega, '/api/cotizaciones', { ...quote, symptom: 'Otro' });
assert.equal(otraCotizacion.status, 409, 'contenido incompatible');

// ---------------------------------------------------------------- dispersiones del Banco
const batch = { businessKey: id, paymentBatchId: id, bankAccount: '000-123', payrollTotal: 1000000, payPeriodId: 'TEST' };
const b1 = await request(urls.banco, '/api/dispersiones', batch);
const b2 = await request(urls.banco, '/api/dispersiones', batch);
assert.equal(b1.body.dispersionId, b2.body.dispersionId, 'reintento de dispersión');

console.log('OK: credencial interna, validación, transición de ticket y reintentos de cotización y dispersión');
