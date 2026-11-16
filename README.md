# Corocora Benz

Tres procesos de negocio hechos en Camunda 7 (embebido en Spring Boot) con workers en JavaScript y API en Swagger.

## Integrantes

- [1] [Sander Farid Sierra Reyes - 160005033]: Gestión de Incidentes TI. Archivos: `bpmn/principales/CB_GestionIncidenteTI.bpmn`, `bpmn/reutilizables/CB_ResolverIncidenteTI.bpmn`, `dmn/CB_ClasificarIncidenteTI.dmn`, `form/ti-*.form`
- [2] [Miguel Ángel Rincón Morales - 160005038]: Postventa y Garantías. Archivos: `bpmn/principales/CB_PostventaGarantias.bpmn`, `bpmn/reutilizables/CB_AtenderOrdenTaller.bpmn`, `dmn/CB_EvaluarCoberturaGarantia.dmn`, `form/postventa-*.form`, `form/taller-diagnostico.form`
- [3] [Nicolas Alejandro Cabezas Daza - 160005006]: Gestión de Nómina. Archivos: `bpmn/principales/CB_GestionNomina.bpmn`, `bpmn/reutilizables/CB_ValidarNovedadEmpleado.bpmn`, `dmn/CB_ClasificarExcepcionNomina.dmn`, `form/nomina-*.form`

Los workers los hicieron las personas 1 y 2. Toda la maquina embebida junto a la documentación de Swagger la hizo la persona 3.

## Arquitectura

Seguimos el modelo del caso MSGF: un motor Camunda que orquesta 5 sistemas de información. El plan completo está en [`docs/PLAN-MICROSERVICIOS.md`](docs/PLAN-MICROSERVICIOS.md).

| Módulo | Puerto | Qué es |
|---|---|---|
| `cb-bpm-engine` | 9000 | Motor Camunda 7 embebido (procesos, DMN, forms, REST API, Cockpit, Tasklist) |
| `cb-portal-cliente` | 9001 | Portal web del cliente de postventa |
| `cb-portal-ti` | 9002 | Portal web de autoservicio TI y API de tickets |
| `cb-central-sys` | 9003 | Intranet de los empleados |
| `cb-banco` | 9004 | API del banco (dispersión de nómina) |
| `cb-taller-bodega` | 9005 | API de vehículos, cotizaciones y repuestos |

Todo es un proyecto Maven multi-módulo en `corocora-benz-embebida/`.

## Cómo correrlo

Se necesita Java 17+, Node 18+ y PostgreSQL.

1. Bases de datos (una sola vez): `psql -U postgres -f corocora-benz-embebida/crear-bases.sql`. Si tu usuario o clave de Postgres no son `postgres`/`postgres`, define las variables de entorno `DB_USER` y `DB_PASSWORD` (y `DB_PORT` si no es 5432).
2. Compilar todo, desde `corocora-benz-embebida`: `.\mvnw.cmd package -DskipTests`.
3. Motor: en IntelliJ corre `com.corocorabenz.engine.Application` (módulo `cb-bpm-engine`), o `java -jar cb-bpm-engine\target\cb-bpm-engine-1.0.0-SNAPSHOT.jar`. Queda en http://localhost:9000 y despliega solo todos los procesos, DMN y forms.
   Sin PostgreSQL: agrega `--spring.profiles.active=h2` (base en memoria, se borra al reiniciar). Sirve igual para los otros módulos.
4. Los demás sistemas se corren igual, cada uno con su `*Application` o su jar. Para Nómina y Postventa hacen falta **CB-Banco** (9004) y **CB-Taller-Bodega** (9005): el motor los llama con HTTP Connector. Si uno está apagado, la tarea queda como incidente en Cockpit; al encenderlo se reintenta con "Retry".
   - El banco responde solo cada dispersión 10 s después de recibirla: acepta si la cuenta tiene el formato `000-123` y rechaza si no. La bodega entrega sola el repuesto pedido 10 s después. Para responder a mano: `PUT /api/simulacion` con `{"respuestaAutomatica": false}` y luego `POST /api/dispersiones/{id}/aceptar|rechazar` o `POST /api/solicitudes-repuesto/{id}/entregar`.
   - Vehículos de prueba en la bodega: `CB0TEST0000000001` (con campaña RECALL-CAMARA-2026), `WDB0000000000000B`, `WDB0000000000000C` y `WDB0000000000000D`.
5. Worker de TI (mientras se reemplaza por Connectors en la Fase 4): abre `corocora-benz-embebida/workers/iniciar-workers.bat` y deja la ventana abierta. Lo demás ya lo hace el motor: nómina y notificaciones con JavaDelegates, banco y taller con HTTP Connector.
   Correos: por defecto solo se escriben en el log del motor. Para enviarlos de verdad, corre un SMTP local como [Mailpit](https://mailpit.axllent.org/) (bandeja en http://localhost:8025) y arranca el motor con la variable `CORREO_HABILITADO=true`.
6. Entra a:
   - Swagger: http://localhost:9000/swagger-ui.html
   - Tasklist: http://localhost:9000/camunda/app/tasklist/ (usuario `demo`, clave `demo`)
   - Cockpit: http://localhost:9000/camunda/app/cockpit/

Para confirmar la solución en el proceso de TI entra a Tasklist con `USR-01` / `USR-01` (se crea solo).

Si repites una `businessKey`, usa otra o borra la instancia; si no, los eventos de Swagger dan error 500.

## Cómo iniciar cada proceso

Los nombres que salen en Tasklist (Start process):

- **Corocora Benz - Gestión de Incidentes TI**: desde Tasklist o Swagger `POST /api/demo/processes/ti`
- **Corocora Benz - Gestión de Nómina**: desde Tasklist o Swagger `POST /api/demo/processes/nomina`
- **Corocora Benz - Postventa y Garantías**: desde Swagger `POST /api/demo/processes/postventa` (arranca con el mensaje `SolicitudServicio`)

En Swagger los datos de ejemplo ya vienen puestos: solo *Try it out* y *Execute*. Las tareas de personas se hacen en Tasklist: abrir la tarea, **Claim**, llenar, **Complete**. Las respuestas externas (cliente, bodega, banco) se mandan desde Swagger, en "Eventos externos", con la misma `businessKey`.

## Qué hace cada proceso y qué llenar

**Gestión de Incidentes TI.** Se reporta un incidente, una tabla DMN calcula prioridad y plazo, la mesa de servicio lo asigna, soporte lo resuelve (subproceso Resolución Técnica TI) y el usuario confirma. Si se vence el plazo pasa a revisión; si el usuario no responde en 24 h se cierra solo.
Para llenar: Usuario `USR-01`, sistema ERP, impacto Medio, urgencia Media, equipos `1`. En el triaje elegir Soporte TI Nivel 1. Diagnóstico: cualquier texto. Confirmar como `USR-01` marcando la casilla.

**Postventa y Garantías.** Llega una solicitud del cliente, se consulta el VIN, una tabla DMN decide si cubre campaña, garantía o si hay que cotizar. Si el cliente acepta, se reservan repuestos y se agenda cita en paralelo, el taller repara (subproceso Orden de Taller) y se entrega el vehículo. Si el cliente rechaza o no responde en 48 h, se cierra sin reparar.
Para llenar: taller Bogotá Norte. En revisar cobertura dejar sin marcar la aprobación y poner cualquier justificación. Enviar `cotizacion-aceptada/POST-DEMO-001` desde Swagger. Cita `2026-11-20 09:00`, orden `OT-001`. Diagnóstico y trabajo: cualquier texto, costo `350000`. Si marcas "Requiere repuesto adicional", CB-Taller-Bodega lo entrega solo a los 10 s. Marcar inspección aprobada. Satisfacción `5`.

**Gestión de Nómina.** Se abre el periodo, se recogen las novedades de los empleados, una tabla DMN revisa periodo, soportes y fecha de corte, cada novedad se valida (subproceso Validación de Novedad), se calcula y aprueba la liquidación, se envía el lote al banco y se espera su respuesta.
Para llenar: periodo `2026-11`, ciclo MENSUAL, corte 30/11/2026. Marcar "Novedad válida" en las dos novedades, "Liquidación consistente" y "Aprobar liquidación" (motivo `Aprobada`). Lote `LOTE-001`, cuenta `000-123`. CB-Banco recibe el lote y a los 10 s responde solo con la dispersión aceptada; con una cuenta mal escrita (por ejemplo `123`) la rechaza y vuelve a Tesorería.

## Pruebas

Con CB-BPM-Engine, CB-Banco, CB-Taller-Bodega y el worker de TI corriendo, desde `corocora-benz-embebida/workers` (la prueba apaga la respuesta automática del banco y la bodega, responde por sus APIs y al final la vuelve a encender):

```
npm run e2e
```

Debe salir `OK` en los 11 escenarios.

En `docs/` están los PDF de los diagramas y los formularios para facilidad de lectura.
