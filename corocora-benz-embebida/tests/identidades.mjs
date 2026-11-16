// Comprueba las identidades de ejemplo del perfil H2 y la protección de la API de Camunda.
// La autorización por tarea y por solicitud se comprueba en tests/permisos.mjs.
import assert from 'node:assert/strict';
const base = process.env.CAMUNDA_REST_URL || 'http://localhost:9000/engine-rest';
const internal = process.env.COROCORA_INTERNAL_TOKEN || 'cb-local-interno-demo';
const headers = {'Content-Type':'application/json', 'X-CB-Internal':internal};
const password = process.env.COROCORA_DEMO_PASSWORD || 'demo';
const grupos = {
  mesa1: 'mesa-servicio', soporte1: 'soporte-ti-n1', especialista1: 'soporte-ti-especializado',
  responsable1: 'responsables-ti', nomina1: 'analistas-nomina', talento1: 'gestion-talento-humano',
  jefatura1: 'jefatura-nomina', tesoreria1: 'tesoreria', asesor1: 'asesores-servicio',
  garantia1: 'analistas-garantia', coordinador1: 'coordinadores-postventa',
  tecnico1: 'tecnicos-taller', calidad1: 'inspectores-calidad'
};
async function verify(username, password) {
  const r = await fetch(base + '/identity/verify', {method:'POST', headers, body:JSON.stringify({username,password})});
  assert.equal(r.status, 200); return (await r.json()).authenticated;
}
for (const [user, group] of Object.entries(grupos)) {
  assert.equal(await verify(user, password), true, `credencial de ${user}`);
  const r = await fetch(base + '/group?member=' + encodeURIComponent(user), {headers});
  assert.equal(r.status, 200);
  const actual = (await r.json()).map(g => g.id);
  assert.deepEqual(actual, [group], `grupo de ${user}`);
}
for (const user of ['cliente1', 'cliente2']) assert.equal(await verify(user, password), true);
assert.equal(await verify('cliente2', password + '-incorrecta'), false, 'rechazo de contraseña incorrecta');
const unprotected = await fetch(base + '/group');
assert.equal(unprotected.status, 401, 'la API de Camunda exige la credencial interna');
console.log('OK: 15 identidades de ejemplo, pertenencia de 13 empleados y API de Camunda protegida');
