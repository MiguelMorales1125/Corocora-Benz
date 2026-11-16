// Casos límite ejecutados sobre los servicios locales con perfil H2.
import assert from 'node:assert/strict';
const urls = { ti: process.env.PORTAL_TI_URL || 'http://localhost:9002', bodega: process.env.TALLER_BODEGA_URL || 'http://localhost:9005', banco: process.env.BANCO_URL || 'http://localhost:9004' };
const internal = process.env.COROCORA_INTERNAL_TOKEN || 'cb-local-interno-demo';
const id = 'REG-' + Date.now();
async function request(base, path, data) {
  const r = await fetch(base + path, data === undefined ? {headers:{'X-CB-Internal':internal}} : {method:'POST', headers:{'Content-Type':'application/json','X-CB-Internal':internal}, body:JSON.stringify(data)});
  const text = await r.text(); let body; try { body = JSON.parse(text); } catch { body = text; }
  return {status:r.status, body};
}
const ticket = {businessKey:id, requesterId:id, systemName:'ERP', description:'No inicia sesión', impact:'Medio', urgency:'Media', safetyImpact:false, affectedTeams:1};
assert.equal((await fetch(urls.ti + '/api/tickets')).status, 401, 'Portal TI exige la credencial interna');
assert.equal((await fetch(urls.bodega + '/api/vehiculos/X?km=10')).status, 401, 'Bodega exige la credencial interna');
assert.equal((await fetch(urls.banco + '/api/dispersiones')).status, 401, 'Banco exige la credencial interna');
assert.equal((await request(urls.ti, '/api/tickets', {businessKey:id})).status, 400, 'ticket incompleto');
const created = await request(urls.ti, '/api/tickets', ticket); assert.equal(created.status, 200);
const duplicateTicket = await request(urls.ti, '/api/tickets', ticket); assert.equal(duplicateTicket.body.ticketId, created.body.ticketId);
assert.equal((await request(urls.ti, '/api/tickets', {...ticket, description:'Otro'})).status, 409, 'clave de ticket con datos incompatibles');
const tid = created.body.ticketId;
assert.equal((await request(urls.ti, `/api/tickets/${tid}/cerrar`, {})).body.ticketStatus, 'CLOSED');
assert.equal((await request(urls.ti, `/api/tickets/${tid}/solucion`, {rootCause:'Causa',resolutionSummary:'Solución'})).status, 409, 'no modificar ticket cerrado');
assert.equal((await request(urls.ti, '/api/tickets/TI-incorrecto/cerrar', {})).status, 404);
assert.equal((await request(urls.bodega, '/api/vehiculos/X?km=10')).status, 400, 'VIN corto');
assert.equal((await request(urls.bodega, '/api/vehiculos/WDB0000000000000B?km=-1')).status, 400, 'kilometraje negativo');
const unknown = await request(urls.bodega, '/api/vehiculos/WDB0000000000000Z?km=0'); assert.equal(unknown.body.registrado, false); assert.equal(unknown.body.warrantyEligible, false);
assert.equal((await request(urls.bodega, '/api/cotizaciones', {})).status, 400, 'cotización incompleta');
const quote = {businessKey:id,vin:'WDB0000000000000B',customerName:'Prueba',symptom:'Ruido'};
const q1 = await request(urls.bodega,'/api/cotizaciones',quote), q2 = await request(urls.bodega,'/api/cotizaciones',quote);
assert.equal(q1.body.quoteId,q2.body.quoteId,'reintento de cotización');
assert.equal((await request(urls.bodega,'/api/cotizaciones',{...quote,symptom:'Otro'})).status,409,'contenido incompatible');
const batch = {businessKey:id,paymentBatchId:id,bankAccount:'000-123',payrollTotal:1000000,payPeriodId:'TEST'};
const b1 = await request(urls.banco,'/api/dispersiones',batch), b2 = await request(urls.banco,'/api/dispersiones',batch);
assert.equal(b1.body.dispersionId,b2.body.dispersionId,'reintento de dispersión');
console.log('OK: validación, transición de ticket y reintentos de cotización y dispersión');
