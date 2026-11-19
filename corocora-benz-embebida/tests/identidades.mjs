// Comprueba las cuentas de demostración que el motor crea al arrancar, en cualquier perfil
// (contrato, secciones 3 y 6): contraseña, grupos y correo de cada una; y que la REST de Camunda y
// /api/demo exigen la credencial interna (sección 2).
// La autorización por tarea y por solicitud se comprueba en tests/permisos.mjs.
// Requisitos: Motor (9000).
// Uso: npm run identidades
import assert from 'node:assert/strict';

const base = process.env.CAMUNDA_REST_URL || 'http://localhost:9000/engine-rest';
const motor = base.replace(/\/engine-rest\/?$/, '');
const internal = process.env.COROCORA_INTERNAL_TOKEN || 'cb-local-interno-demo';
const headers = { 'Content-Type': 'application/json', 'X-CB-Internal': internal };
const password = process.env.COROCORA_DEMO_PASSWORD || 'demo';
const sleep = (ms) => new Promise((resolver) => setTimeout(resolver, ms));

// Cuenta -> grupos exactos que debe tener.
const empleados = {
  mesa1: ['mesa-servicio'],
  soporte1: ['soporte-ti-n1'],
  especialista1: ['soporte-ti-especializado'],
  responsable1: ['responsables-ti'],
  nomina1: ['analistas-nomina'],
  talento1: ['gestion-talento-humano'],
  jefatura1: ['jefatura-nomina'],
  tesoreria1: ['tesoreria'],
  asesor1: ['asesores-servicio'],
  garantia1: ['analistas-garantia'],
  coordinador1: ['coordinadores-postventa'],
  tecnico1: ['tecnicos-taller'],
  calidad1: ['inspectores-calidad'],
  bodega1: ['bodega']
};
const clientes = {
  cliente1: ['clientes'],
  cliente2: ['clientes']
};

async function verify(username, clave) {
  const respuesta = await fetch(base + '/identity/verify', {
    method: 'POST',
    headers,
    body: JSON.stringify({ username, password: clave })
  });
  assert.equal(respuesta.status, 200, `identity/verify de ${username}`);
  return (await respuesta.json()).authenticated;
}

async function gruposDe(usuario) {
  const respuesta = await fetch(base + '/group?member=' + encodeURIComponent(usuario), { headers });
  assert.equal(respuesta.status, 200, `grupos de ${usuario}`);
  const grupos = await respuesta.json();
  return grupos.map((grupo) => grupo.id).sort();
}

async function correoDe(usuario) {
  const respuesta = await fetch(base + '/user?id=' + encodeURIComponent(usuario), { headers });
  assert.equal(respuesta.status, 200, `datos de ${usuario}`);
  const usuarios = await respuesta.json();
  assert.equal(usuarios.length, 1, `la cuenta ${usuario} existe`);
  return usuarios[0].email;
}

// Empleados y clientes: contraseña común, grupos exactos y correo de demostración.
for (const [usuario, grupos] of Object.entries({ ...empleados, ...clientes })) {
  assert.equal(await verify(usuario, password), true, `credencial de ${usuario}`);
  assert.deepEqual(await gruposDe(usuario), grupos, `grupos de ${usuario}`);
  assert.equal(await correoDe(usuario), `${usuario}@corocorabenz.test`, `correo de ${usuario}`);
}

// USR-01: empleado sin grupo que reporta incidentes; su contraseña es su propio identificador.
assert.equal(await verify('USR-01', 'USR-01'), true, 'credencial de USR-01');
assert.deepEqual(await gruposDe('USR-01'), [], 'USR-01 no pertenece a ningún grupo');
assert.equal(await correoDe('USR-01'), 'USR-01@corocorabenz.test', 'correo de USR-01');

// demo: administrador (puede tener además otros grupos propios de Camunda).
assert.equal(await verify('demo', password), true, 'credencial de demo');
assert.ok((await gruposDe('demo')).includes('camunda-admin'), 'demo pertenece a camunda-admin');

// Contraseña incorrecta. Camunda bloquea la cuenta unos segundos después de un fallo y cuenta los
// fallos seguidos: se espera a que vuelva a autenticar para dejar el contador en cero.
assert.equal(await verify('cliente2', password + '-incorrecta'), false, 'rechazo de contraseña incorrecta');
let recuperada = false;
for (let intento = 0; intento < 40 && !recuperada; intento += 1) {
  await sleep(500);
  recuperada = await verify('cliente2', password);
}
assert.equal(recuperada, true, 'cliente2 vuelve a autenticar tras el bloqueo temporal por el intento fallido');

// La REST de Camunda y /api/demo exigen la credencial interna.
const sinCredencial = await fetch(base + '/group');
assert.equal(sinCredencial.status, 401, 'la API de Camunda exige la credencial interna');
assert.deepEqual(await sinCredencial.json(), { error: 'Credencial interna requerida' }, 'cuerpo del 401');
const credencialMala = await fetch(base + '/group', { headers: { 'X-CB-Internal': internal + '-otra' } });
assert.equal(credencialMala.status, 401, 'una credencial incorrecta se rechaza');
const conBearer = await fetch(base + '/group', { headers: { Authorization: 'Bearer ' + internal } });
assert.equal(conBearer.status, 200, 'la credencial también se acepta como Authorization: Bearer');
const demoSinCredencial = await fetch(motor + '/api/demo/tasks');
assert.equal(demoSinCredencial.status, 401, '/api/demo exige la credencial interna');

// Lo que el contrato deja abierto: la descripción OpenAPI que usa Swagger UI.
const apiDocs = await fetch(motor + '/v3/api-docs');
assert.equal(apiDocs.status, 200, '/v3/api-docs no pide la credencial interna');

// El motor ya no expone CORS: ningún navegador lo llama directamente.
const conOrigen = await fetch(base + '/version', { headers: { ...headers, Origin: 'http://localhost:9003' } });
assert.equal(conOrigen.headers.get('access-control-allow-origin'), null, 'el motor no responde con cabeceras CORS');

const total = Object.keys(empleados).length + Object.keys(clientes).length + 2;
console.log(`OK: ${total} cuentas de demostración (${Object.keys(empleados).length} empleados con su grupo, `
  + `${Object.keys(clientes).length} clientes, USR-01 y demo) y API de Camunda protegida`);
