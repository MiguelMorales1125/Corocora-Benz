# Contrato de integración entre los sistemas

Este documento es la fuente de verdad de cómo se hablan los 6 sistemas, cómo inician sesión las personas y qué expone cada API. Si el código y este documento no coinciden, es un error de uno de los dos.

## 1. Vista general

```
Navegador ──sesión──► PortalCliente (9001) ─┐
Navegador ──sesión──► PortalTI      (9002) ─┼─ REST de Camunda + X-CB-Internal ─► Motor (9000)
Navegador ──sesión──► CentralSys    (9003) ─┘                                        │
                                                        HTTP Connector + X-CB-Internal │
        Banco (9004) ◄────────────────────────────────────────────────────────────────┤
        Taller-Bodega (9005) ◄─────────────────────────────────────────────────────────┤
        PortalTI /api/tickets (9002) ◄──────────────────────────────────────────────────┘
        Banco y Taller-Bodega ── mensajes (REST de Camunda + X-CB-Internal) ──► Motor
```

- El navegador **nunca** llama al motor ni a Banco/Bodega: solo habla con el backend de su propio portal (mismo origen), en `/api/app/**`.
- Cada portal valida las credenciales contra el servicio de identidad de Camunda (`/engine-rest/identity/verify` y `/engine-rest/group?member=`).
- Toda llamada entre sistemas lleva la credencial interna.

Hay un módulo de librería compartida, `cb-comun` (no es un sistema, no se ejecuta), con el filtro de la credencial interna, el cliente del motor, la seguridad de sesión de los portales y los recursos web comunes (`auth.js`, `portal.css`).

## 2. Credencial interna (sistema a sistema)

- Encabezado: `X-CB-Internal: <token>`. También se acepta `Authorization: Bearer <token>` (para Camunda Modeler).
- Propiedad: `corocora.internal-token`, valor `${COROCORA_INTERNAL_TOKEN:cb-local-interno-demo}` en todos los `application.yaml`.
- Sin credencial o con una incorrecta: **401** con cuerpo JSON `{"error":"Credencial interna requerida"}`. La comparación es en tiempo constante.
- Rutas protegidas por sistema (propiedad `corocora.rutas-internas`, lista separada por comas de patrones de servlet):

| Sistema | Rutas protegidas |
|---|---|
| Motor | `/engine-rest/*`, `/api/demo/*` |
| PortalTI | `/api/tickets/*`, `/api/tickets` |
| Banco | `/api/*` |
| Taller-Bodega | `/api/*` |

- No se protegen con esta credencial: las webapps de Camunda (`/camunda/**`, tienen su propio login), Swagger UI y `/v3/api-docs` del motor, ni los recursos estáticos.
- Los HTTP Connector de los BPMN envían el encabezado con `${sistemas.token}`.
- El motor ya no expone CORS: ningún navegador lo llama directamente.

## 3. Sesión en los portales (personas)

Igual en los tres portales, bajo `/api/app`. Implementado con Spring Security en `cb-comun`.

| Método y ruta | Sin sesión | Respuesta |
|---|---|---|
| `GET /api/app/csrf` | permitido | 200 `{"header":"X-CSRF-TOKEN","token":"..."}` |
| `POST /api/app/login` (form-urlencoded `username`, `password`, con el encabezado CSRF) | permitido | 204 si es correcto; 401 `{"error":"Usuario o contraseña incorrectos"}` |
| `POST /api/app/logout` (con CSRF) | — | 204, cuerpo vacío |
| `GET /api/app/me` | 401 | 200 `{"user":"mesa1","groups":["mesa-servicio"],"cliente":false,"admin":false}` |
| cualquier otra `/api/app/**` | 401 `{"error":"Inicia sesión"}` | según el endpoint |

- Sin permiso suficiente: **403** `{"error":"..."}`. Nunca se redirige a una página de login: la API responde con códigos.
- Toda petición que no sea GET necesita el encabezado CSRF. El token cambia al iniciar sesión, así que el cliente pide uno nuevo antes de cada escritura.
- Errores de negocio y validación: JSON `{"error":"mensaje en español"}` con 400, 404, 409 o 502 (si el motor u otro sistema no responde).
- Cada portal usa un nombre de cookie de sesión distinto (las cookies de `localhost` se comparten entre puertos): `CBSESSION_CLIENTE`, `CBSESSION_TI`, `CBSESSION_CENTRAL`.
- Perfiles de usuario:
  - `cliente`: pertenece al grupo `clientes`.
  - `admin`: pertenece al grupo `camunda-admin` (la cuenta `demo`). Puede hacer todo lo de un empleado en CentralSys y PortalTI.
  - empleado: cualquier otro usuario.

### Cuentas de demostración (las crea el motor al arrancar)

Contraseña `demo` para todas salvo `USR-01` (contraseña `USR-01`). Se desactivan con `COROCORA_DEMO_IDENTIDADES=false`; la contraseña se cambia con `COROCORA_DEMO_PASSWORD`.

| Cuenta | Grupo |
|---|---|
| `demo` | `camunda-admin` (administrador) |
| `mesa1` | `mesa-servicio` |
| `soporte1`, `especialista1` | `soporte-ti-n1`, `soporte-ti-especializado` |
| `responsable1` | `responsables-ti` |
| `nomina1`, `talento1`, `jefatura1`, `tesoreria1` | `analistas-nomina`, `gestion-talento-humano`, `jefatura-nomina`, `tesoreria` |
| `asesor1`, `garantia1`, `coordinador1` | `asesores-servicio`, `analistas-garantia`, `coordinadores-postventa` |
| `tecnico1`, `calidad1` | `tecnicos-taller`, `inspectores-calidad` |
| `bodega1` | `bodega` |
| `cliente1`, `cliente2` | `clientes` |
| `USR-01` | (empleado sin grupo; reporta incidentes) |

## 4. `auth.js` (recurso común, servido en `/auth.js` por cada portal)

Expone el objeto global `CB`:

- `CB.request(path)`: `GET /api/app` + `path`. Devuelve el JSON. Lanza `Error(mensaje)` si la respuesta no es 2xx (usa el campo `error` del cuerpo).
- `CB.post(path, body)` y `CB.put(path, body)`: piden el token CSRF y envían JSON.
- `CB.user`: el objeto de `/api/app/me` o `null`.
- Al cargar la página: consulta `/me`. Con sesión, pinta la barra de sesión (usuario, grupos, botón "Cerrar sesión") y dispara el evento `cb-auth-ready` en `window` con `detail = CB.user`. Sin sesión, muestra el formulario de inicio de sesión; tras un login correcto dispara el mismo evento. Un 401 en cualquier llamada vuelve a mostrar el login.
- Cada página declara antes de cargarlo `window.CB_PORTAL = { nombre: "...", perfil: "cliente" | "empleado", cuentas: "texto de ayuda con cuentas de demostración" }`. Si el usuario no corresponde al perfil del portal, `auth.js` muestra un aviso y el botón de cerrar sesión, y **no** dispara `cb-auth-ready`.

## 5. API de cada portal (`/api/app/...`, con sesión)

### 5.1 PortalCliente (9001) — solo perfil `cliente`; cualquier otro: 403

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `POST /client/requests` | `{customerName, customerEmail?, vin, mileageKm, serviceType, symptom}` | 200 `{"businessKey":"PV-XXXXXXXX"}` |
| `GET /client/requests` | — | 200 lista (ver abajo), solo las del usuario |
| `POST /client/requests/{businessKey}/answer` | `{"action":"aceptar" \| "rechazar"}` | 200 `{businessKey, action}`; 404 si no es suya; 409 si el proceso no espera respuesta |

- Validación: `customerName` 1–255; `customerEmail` opcional con formato de correo; `vin` `[A-HJ-NPR-Z0-9]{17}`; `mileageKm` entero ≥ 0; `serviceType` en `Garantía`, `Mantenimiento`, `Reparación`; `symptom` 1–255.
- Al crear: guarda la solicitud en su tabla `pc_solicitud` (con el usuario dueño) y envía al motor el mensaje `SolicitudServicio` con esa `businessKey` y las variables `vin`, `customerName`, `mileageKm` (Long), `serviceType`, `symptom`, `customerUserId` y, si viene, `customerEmail`. Si el motor falla, la solicitud no queda guardada y responde 502.
- Cada elemento de la lista: `{id, businessKey, creadaEn, endTime, canReply, variables, activities}`.
  - `id`: id de la instancia del proceso `CB_PostventaGarantias` (historial). Las solicitudes cuya instancia ya no existe en el motor no se listan.
  - `variables`: solo `vin`, `customerName`, `serviceType`, `mileageKm`, `symptom`, `coverageStatus`, `dealerId`, `appointmentDate`, `serviceOrderId`, `repairNotes`, `serviceCost`, `quoteId`, `quoteAmount`.
  - `coverageStatus` es siempre el resultado de la regla de cobertura. Si el analista no aprueba la garantía el proceso no lo cambia: llega `CUBIERTO_POR_GARANTIA` o `CAMPANA_ACTIVA` junto con `quoteId`, y la página lo muestra como «garantía no aprobada; requiere cotización».
  - `activities`: `[{activityId, endTime}]` del historial de la instancia raíz.
  - `canReply`: la instancia tiene una suscripción activa al mensaje `CotizacionAceptada`.
- `answer` envía el mensaje `CotizacionAceptada` o `CotizacionRechazada`.

### 5.2 PortalTI (9002) — empleados y admin; perfil `cliente`: 403

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `POST /ti/reports` | `{systemName, description, impact, urgency, affectedTeams, safetyImpact, notificationEmail?}` | 200 `{"businessKey":"TI-XXXXXXXX"}` |
| `GET /ti/tickets` | — | 200 lista de tickets del usuario |
| `POST /ti/tickets/{ticketId}/confirm` | `{"confirmed":true \| false}` | 200 `{ticketId, confirmed}`; 404 si no es suyo; 409 si no hay confirmación pendiente |

- Validación: `systemName` en `Software del vehículo`, `ERP`, `Portal de clientes`, `Correo`; `description` 1–4000; `impact` en `Bajo`, `Medio`, `Alto`; `urgency` en `Baja`, `Media`, `Alta`; `affectedTeams` entero ≥ 1; `safetyImpact` booleano; `notificationEmail` opcional con formato de correo.
- Al reportar: inicia `CB_GestionIncidenteTI` con `requesterId` = usuario de la sesión. El ticket lo crea después el motor con su HTTP Connector.
- Cada ticket: `{ticketId, businessKey, systemName, description, status, rootCause, resolutionSummary, canConfirm}`. `canConfirm` es verdadero si existe la tarea `confirmSolution` de esa `businessKey` asignada al usuario.
- `confirm` completa esa tarea con `userConfirmed`.

API interna (credencial interna), ya existente: `POST /api/tickets`, `GET /api/tickets?requesterId=`, `POST /api/tickets/{ticketId}/solucion`, `/cerrar`, `/reabrir`. El motor usa las de crear, solución y cerrar; `/reabrir` queda disponible, pero ningún proceso lo llama: la reapertura se refleja al consultar los tickets.

### 5.3 CentralSys (9003) — empleados y admin; perfil `cliente`: 403

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `GET /central/tasks` | — | 200 bandeja del usuario |
| `POST /central/tasks/{id}/claim` | `{}` | 200 `{id, assignee}`; 403 sin permiso; 409 si otra persona ya la tiene |
| `POST /central/tasks/{id}/unclaim` | `{}` | 200; 403 si no es quien la tiene |
| `GET /central/tasks/{id}/deployed-form` | — | 200 esquema del formulario; 403 sin permiso; 404 |
| `GET /central/tasks/{id}/form-variables` | — | 200 variables del formulario (formato Camunda `{nombre:{value,type}}`) |
| `GET /central/tasks/{id}/variables` | — | 200 variables visibles de la tarea (mismo formato) |
| `POST /central/tasks/{id}/complete` | `{"variables":{nombre:{value,type}}}` | 200 `{completed:true, assigned}`; 403 si no es quien la tiene; 400 si falta un campo obligatorio |
| `POST /central/nomina` | `{payPeriodId, payCycle, cutoffDate, simulatePeriodClosed?, simulateMissingSupports?, simulateLateChanges?}` | 200 `{businessKey, id}`; 403 si no es `analistas-nomina` ni admin |
| `GET /central/actividad` | — | 200 últimas 20 acciones del usuario |
| `GET /bodega/reservas`, `GET /bodega/solicitudes`, `GET /bodega/simulacion` | — | 200; 403 si no es `bodega` ni admin |
| `POST /bodega/reservas/{id}/disponibles`, `POST /bodega/solicitudes/{id}/entregar`, `PUT /bodega/simulacion` | — / `{respuestaAutomatica?, retardoSegundos?, repuestosDisponibles?}` | 200; 403; 409 si el proceso aún no espera el aviso |

- Bandeja: tareas sin asignar cuyo grupo candidato es uno de los grupos del usuario, más las tareas asignadas al usuario. El admin ve todas. Cada tarea: `{id, name, taskDefinitionKey, assignee, created, businessKey, processDefinitionKey, processName, candidateGroups}`.
- "Con permiso" sobre una tarea = es quien la tiene asignada, o está sin asignar y pertenece a uno de sus grupos candidatos, o es admin.
- `complete`:
  - Solo quien la tiene asignada (o admin).
  - El servidor lee el formulario desplegado y **solo acepta** las variables de sus campos editables (no `disabled`); el resto se descarta.
  - El servidor fija el tipo según el campo: `number` → Long (o Double si tiene decimales), `checkbox` → Boolean, lo demás → String.
  - Tras completar `reviewSla` con `newOwner`: si ese usuario existe y no es cliente, se le asigna la siguiente tarea de soporte de esa `businessKey` (`resolveN1` o `resolveSpecialist`) y `assigned` es `true`; si no se pudo, `false`. En cualquier otra tarea `assigned` es `null`.
- `nomina`: `payPeriodId` 1–40 caracteres `[A-Za-z0-9-]`; `payCycle` en `QUINCENAL`, `MENSUAL`; `cutoffDate` `yyyy-MM-dd`. Inicia `CB_GestionNomina` con `businessKey` `NOM-<payPeriodId>-<6 caracteres>`.
  - `simulatePeriodClosed`, `simulateMissingSupports` y `simulateLateChanges` son booleanos opcionales, solo para la demostración (casillas "Simular una excepción" de la página). Cada uno se envía al proceso como variable Boolean **solo si llega en `true`**; sin ellos el proceso recibe únicamente las tres variables del periodo.
  - `simulatePeriodClosed` (periodo cerrado) y `simulateMissingSupports` (soportes incompletos) abren la tarea `correctChanges` de `gestion-talento-humano`; `simulateLateChanges` (novedades fuera de corte) abre `approveLateChanges` de `jefatura-nomina`. Si se marcan varias gana la primera en ese orden, como en la tabla `CB_ClasificarExcepcionNomina`.
- Cada acción (reclamar, completar, iniciar nómina, acciones de bodega) se registra en la tabla `cs_actividad` (usuario, acción, tarea, `businessKey`, fecha).
- Las rutas `/bodega/**` reenvían a Taller-Bodega con la credencial interna.

## 6. Motor (9000)

- Identidades de demostración en cualquier perfil (propiedad `corocora.demo.identidades`, por defecto `true`), incluidos los grupos `clientes` y `bodega` y un correo `<id>@corocorabenz.test` por cuenta.
- Toda instancia raíz que arranque **sin** `businessKey` (por ejemplo desde Tasklist) recibe una automática: `<prefijo>-<8 caracteres>`, con prefijo `TI`, `NOM` o `PV` según el proceso. Los sistemas externos responden por `businessKey`, así que nunca puede faltar.
- Swagger UI permite fijar la credencial interna con el botón "Authorize".

## 7. Banco (9004) y Taller-Bodega (9005)

Toda la API de los dos simuladores exige la credencial interna (sección 2). Los errores llevan el cuerpo `{"error":"mensaje"}`, salvo los 404 del Banco, que no tienen cuerpo.

### 7.1 Banco (9004)

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `GET /api/estado` | — | 200 `{"sistema":"CB-Banco","estado":"OK"}` |
| `POST /api/dispersiones` | `{businessKey, paymentBatchId, bankAccount, payrollTotal, payPeriodId}` | 201 `{dispersionId, estado, recibida:true}`; 400 si falta `businessKey` |
| `GET /api/dispersiones` (`?businessKey=` opcional) | — | 200 lista de dispersiones, la más reciente primero |
| `GET /api/dispersiones/{id}` | — | 200 la dispersión; 404 |
| `POST /api/dispersiones/{id}/aceptar` | `{referencia?}` (cuerpo opcional) | 200 la dispersión `ACEPTADA`; 404; 409; 502 |
| `POST /api/dispersiones/{id}/rechazar` | `{codigo?}` (cuerpo opcional) | 200 la dispersión `RECHAZADA`; 404; 409; 502 |
| `GET /api/simulacion` | — | 200 `{respuestaAutomatica, retardoSegundos}` |
| `PUT /api/simulacion` | `{respuestaAutomatica?, retardoSegundos?}` | 200 la simulación resultante; solo cambian los campos enviados |

- `POST /api/dispersiones` lo llama el motor ("Enviar dispersión al Banco") y es idempotente: el mismo lote, mientras siga pendiente o aceptado, devuelve la dispersión que ya existe. Un lote distinto del mismo proceso deja la anterior sin responder en `REEMPLAZADA`.
- Cada dispersión: `{id, businessKey, paymentBatchId, bankAccount, payrollTotal, payPeriodId, estado, codigoRespuesta, referenciaBancaria, intentosRespuesta, recibidaEn, respondidaEn}`. `estado` es `RECIBIDA`, `ACEPTADA`, `RECHAZADA`, `REEMPLAZADA` o `SIN_PROCESO`.
- `aceptar` envía al motor el mensaje `DispersionAceptada` con `bankResponseCode` `00-APROBADA` y `bankReference` (la `referencia` recibida o `BANCO-<id>`). `rechazar` envía `DispersionRechazada` con `bankResponseCode` (el `codigo` recibido o `R03-CUENTA-INVALIDA`).
- 409 en `aceptar` y `rechazar`: la dispersión ya fue respondida (no está `RECIBIDA`), el proceso todavía no espera la respuesta del banco (se puede reintentar) o el proceso ya no existe (la dispersión queda `SIN_PROCESO`). 502 si el motor no responde.
- Con `respuestaAutomatica` en `true` (valor inicial, variable `BANCO_RESPUESTA_AUTOMATICA`), el Banco responde solo cada dispersión `RECIBIDA` pasados `retardoSegundos` (10): acepta las cuentas con formato `000-123` (tres dígitos, guion y al menos tres dígitos) y rechaza las demás. Con `false` espera la respuesta manual por `aceptar` o `rechazar`.

### 7.2 Taller-Bodega (9005)

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `GET /api/estado` | — | 200 `{"sistema":"CB-Taller-Bodega","estado":"OK"}` |
| `GET /api/vehiculos/{vin}` (`?km=`, por defecto 0) | — | 200 `{vin, registrado, modelo, historyRecordId, warrantyEligible, campaignActive, campaignId}`; 400 si el VIN está mal formado o `km` es negativo |
| `POST /api/cotizaciones` | `{businessKey, vin, customerName, symptom}` | 201 `{quoteId, quoteAmount}`; 200 con la misma cotización si se repite la petición; 400; 409 si esa `businessKey` ya tiene una cotización con otros datos |
| `GET /api/cotizaciones` (`?businessKey=` opcional) | — | 200 lista de cotizaciones, la más reciente primero |
| `POST /api/reservas` | `{businessKey, vin}` | 201 `{reservationId, partsReservationStatus}`; 200 con la misma reserva si se repite la petición; 400; 409 si la reserva vigente es de otro vehículo |
| `GET /api/reservas` | — | 200 lista de reservas, la más reciente primero |
| `POST /api/reservas/{id}/disponibles` | — | 200 la reserva; 404; 409; 502 |
| `POST /api/solicitudes-repuesto` | `{businessKey, vin?, partCode?}` | 201 `{partRequestId, estado}`; 400 |
| `GET /api/solicitudes-repuesto` (`?businessKey=` opcional) | — | 200 lista de solicitudes, la más reciente primero |
| `POST /api/solicitudes-repuesto/{id}/entregar` | — | 200 la solicitud `ENTREGADA`; 404; 409; 502 |
| `GET /api/simulacion` | — | 200 `{respuestaAutomatica, retardoSegundos, repuestosDisponibles}` |
| `PUT /api/simulacion` | `{respuestaAutomatica?, retardoSegundos?, repuestosDisponibles?}` | 200 la simulación resultante; solo cambian los campos enviados |

- Los `POST` de cotizaciones, reservas y solicitudes y el `GET` de vehículos los llama el motor con sus HTTP Connector; el resto es para el panel de bodega de CentralSys, las pruebas y la respuesta manual.
- Vehículos: un VIN bien formado pero desconocido responde 200 con `registrado` `false` y sin garantía. `warrantyEligible` exige vehículo registrado y `km` ≤ 60 000; `campaignActive` indica si tiene campaña.
- Cotizaciones: `businessKey` 1–255; `vin` `[A-HJ-NPR-Z0-9]{17}`; `customerName` 1–255; `symptom` 1–255. El valor es siempre 850 000 (simulado). Cada cotización: `{quoteId, id, businessKey, vin, customerName, symptom, monto, estado, creadaEn}`, con `quoteId` `COT-<id>`. `estado` es `ENVIADA`, `ACEPTADA`, `RECHAZADA` o `VENCIDA`; se pone al día con el historial del motor al consultar la lista.
- Reservas: `businessKey` y `vin` 1–255. `partsReservationStatus` es `RESERVED` si `repuestosDisponibles` está en `true` y `PENDIENTE` si no. Cada reserva: `{id, businessKey, vin, estado, creadaEn}`, con `estado` `RESERVED`, `PENDIENTE` o `SIN_PROCESO`.
- `disponibles` envía al motor el mensaje `RepuestosDisponibles` con `partsReservationStatus` `RESERVED`. Repetirlo sobre una reserva ya `RESERVED` responde 200 sin enviar otro mensaje.
- Solicitudes de repuesto: `businessKey` 1–255; `vin` y `partCode` opcionales, máximo 255; sin `partCode` se registra `SIN-CODIGO`. Cada solicitud: `{partRequestId, id, businessKey, vin, partCode, estado, remision, intentosRespuesta, solicitadaEn, entregadaEn}`, con `partRequestId` `REQ-<id>` y `estado` `SOLICITADA`, `ENTREGADA` o `SIN_PROCESO`.
- `entregar` envía al motor el mensaje `RepuestoRecibido` con `partReceivedRef` `REM-<id>`.
- 409 en `disponibles` y `entregar`: el proceso todavía no espera el aviso (se puede reintentar), el proceso ya no existe (el registro queda `SIN_PROCESO`) o el registro ya no está pendiente. 502 si el motor no responde.
- Con `respuestaAutomatica` en `true` (valor inicial, variable `BODEGA_RESPUESTA_AUTOMATICA`), la bodega entrega sola cada solicitud `SOLICITADA` pasados `retardoSegundos` (10). Con `false` espera la entrega manual por `entregar` o por el panel de bodega de CentralSys. Las reservas `PENDIENTE` nunca se avisan solas: siempre requieren `disponibles`.

### 7.3 Respuesta asíncrona al motor

- Respuesta asíncrona al motor: si el motor no acepta el mensaje, se reintenta en el siguiente ciclo **mientras exista una instancia activa** con esa `businessKey`. Si ya no existe, el registro pasa a `SIN_PROCESO` y no se reintenta más. Si el motor no responde, se reintenta.

## 8. Formularios

- `generated-fix-bank-batch.form` incluye el campo editable `bankAccount` ("Cuenta de origen corregida", obligatorio).
- `generated-deliver-vehicle.form`: `customerSatisfaction` entre 1 y 5.
- `generated-review-sla.form`: `newOwner` sin valor por defecto.
- `postventa-solicitud.form`: `customerName` y `symptom` admiten máximo 255 caracteres (`maxLength`), el mismo límite del portal y de Taller-Bodega. Lo valida Tasklist; la REST de Camunda no valida los formularios.
- `nomina-periodo.form`: tres casillas sin marcar por defecto (`simulatePeriodClosed`, `simulateMissingSupports`, `simulateLateChanges`), las mismas de la sección 5.3.
- Campos de solo lectura **sin** valor por defecto, porque muestran un dato que solo existe después de una devolución: `approvalReason` en `nomina-revisar-liquidacion.form` (motivo de Jefatura), `inspectionResults` en `generated-repair-vehicle.form` (última inspección) y `previousRootCause` y `previousResolution` en `ti-diagnostico.form`. Estos dos últimos los llena la call activity `resolveIncident`, que pasa `rootCause` y `resolutionSummary` del intento anterior con esos nombres para no pisar los campos editables.
- CentralSys muestra los formularios tal como están desplegados, **con** sus valores por defecto.
- Todo `.form`, `.bpmn` y `.dmn` vive en dos copias idénticas: `form/`, `bpmn/` y `dmn/` en la raíz, y `cb-bpm-engine/src/main/resources/static.form`, `static.bpmn` y `static.dmn`.

## 9. Pruebas (`corocora-benz-embebida/tests`)

| Script | Qué comprueba | Sistemas necesarios |
|---|---|---|
| `e2e.mjs` | 12 escenarios de los 3 procesos por la REST de Camunda | Motor, PortalTI, Banco, Taller-Bodega |
| `identidades.mjs` | Cuentas, grupos y motor protegido | Motor |
| `regresion.mjs` | Validaciones, reintentos idempotentes y APIs protegidas | PortalTI, Banco, Taller-Bodega |
| `permisos.mjs` | Sesión, permisos por grupo, aislamiento entre clientes | Los 6 |
| `portales.mjs` | Los 3 procesos de punta a punta **por las APIs de los portales** | Los 6 |
