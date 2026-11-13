# Corocora Benz

Tres procesos de negocio hechos en Camunda 7 (embebido en Spring Boot) con workers en JavaScript y API en Swagger.

## Integrantes

- [1] [Sander Farid Sierra Reyes - 160005033]: Gestión de Incidentes TI. Archivos: `bpmn/principales/CB_GestionIncidenteTI.bpmn`, `bpmn/reutilizables/CB_ResolverIncidenteTI.bpmn`, `dmn/CB_ClasificarIncidenteTI.dmn`, `form/ti-*.form`
- [2] [Miguel Ángel Rincón Morales - 160005038]: Postventa y Garantías. Archivos: `bpmn/principales/CB_PostventaGarantias.bpmn`, `bpmn/reutilizables/CB_AtenderOrdenTaller.bpmn`, `dmn/CB_EvaluarCoberturaGarantia.dmn`, `form/postventa-*.form`, `form/taller-diagnostico.form`
- [3] [Nicolas Alejandro Cabezas Daza - 160005006]: Gestión de Nómina. Archivos: `bpmn/principales/CB_GestionNomina.bpmn`, `bpmn/reutilizables/CB_ValidarNovedadEmpleado.bpmn`, `dmn/CB_ClasificarExcepcionNomina.dmn`, `form/nomina-*.form`

Los workers los hicieron las personas 1 y 2. Toda la maquina embebida junto a la documentación de Swagger la hizo la persona 3.

## Cómo correrlo

Se necesita Java 17+ y Node 18+.

1. Backend: en IntelliJ abre la carpeta `corocora-benz-embebida/backend` y corre `software.Application` (o desde esa carpeta: `.\mvnw.cmd spring-boot:run`). Queda en http://localhost:8080. Al iniciar despliega solo todos los procesos, DMN y forms.
2. Workers: abre `corocora-benz-embebida/workers/iniciar-workers.bat`. Abre 4 ventanas, déjalas abiertas.
3. Entra a:
   - Swagger: http://localhost:8080/swagger-ui.html
   - Tasklist: http://localhost:8080/camunda/app/tasklist/ (usuario `demo`, clave `demo`)
   - Cockpit: http://localhost:8080/camunda/app/cockpit/

Para confirmar la solución en el proceso de TI entra a Tasklist con `USR-01` / `USR-01` (se crea solo).

La base es en memoria: al reiniciar el backend se borra todo. Si repites una `businessKey`, reinicia o usa otra, si no los eventos de Swagger dan error 500.

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
Para llenar: taller Bogotá Norte. En revisar cobertura dejar sin marcar la aprobación y poner cualquier justificación. Enviar `cotizacion-aceptada/POST-DEMO-001` desde Swagger. Cita `2026-11-20 09:00`, orden `OT-001`. Diagnóstico y trabajo: cualquier texto, costo `350000`. Marcar inspección aprobada. Satisfacción `5`.

**Gestión de Nómina.** Se abre el periodo, se recogen las novedades de los empleados, una tabla DMN revisa periodo, soportes y fecha de corte, cada novedad se valida (subproceso Validación de Novedad), se calcula y aprueba la liquidación, se envía el lote al banco y se espera su respuesta.
Para llenar: periodo `2026-11`, ciclo MENSUAL, corte 30/11/2026. Marcar "Novedad válida" en las dos novedades, "Liquidación consistente" y "Aprobar liquidación" (motivo `Aprobada`). Lote `LOTE-001`, cuenta `000-123`. Enviar `dispersion-aceptada/NOM-DEMO-001` desde Swagger.

## Pruebas

Con backend y workers corriendo, desde `corocora-benz-embebida/workers`:

```
npm run e2e
```

Debe salir `OK` en los 11 escenarios.

En `docs/` están los PDF de los diagramas y los formularios para facilidad de lectura.
