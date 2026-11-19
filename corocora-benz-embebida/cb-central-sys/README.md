# CB-CentralSys (`cb-central-sys`)

Intranet de los empleados de Corocora Benz: bandeja de tareas de los tres procesos, inicio de la nómina y panel de bodega (Spring Boot 3.5.6, Java 17).

> **Este repositorio es un componente de Corocora Benz. El hub integrador, con la guía general, está en https://github.com/MiguelMorales1125/Corocora-Benz.**

Repositorio de este componente: https://github.com/MiguelMorales1125/CorocoraBenz-CentralSys

## Propósito

Es donde los empleados atienden las **tareas de usuario** de los carriles internos de los tres procesos. Cada persona ve las tareas libres de sus grupos candidatos y las que tiene asignadas; las reclama, las completa con el formulario desplegado en el motor y puede soltarlas.

| Proceso | Carril (grupo candidato) | Tareas de usuario que se atienden aquí |
|---|---|---|
| `CB_GestionIncidenteTI` | Mesa de Servicio (`mesa-servicio`) | `triageIncident` |
| | Responsable del Servicio TI (`responsables-ti`) | `reviewSla` |
| `CB_ResolverIncidenteTI` | Soporte TI Nivel 1 (`soporte-ti-n1`), Soporte Especializado (`soporte-ti-especializado`) | `resolveN1`, `resolveSpecialist` |
| `CB_GestionNomina` | Analista de Nómina (`analistas-nomina`) | inicio del ciclo (`startPayroll`), `reviewPayroll` |
| | Gestión de Talento Humano (`gestion-talento-humano`) | `correctChanges` |
| | Jefatura de Nómina (`jefatura-nomina`) | `approveLateChanges`, `approvePayroll` |
| | Tesorería (`tesoreria`) | `prepareBankBatch`, `fixBankBatch` |
| `CB_ValidarNovedadEmpleado` | Analista de Nómina, Gestión de Talento Humano | `reviewChange`, `correctChange` |
| `CB_PostventaGarantias` | Asesor de Servicio (`asesores-servicio`) | `registerRequest`, `deliverVehicle` |
| | Analista de Garantías (`analistas-garantia`) | `reviewWarranty` |
| | Coordinador de Postventa (`coordinadores-postventa`) | `scheduleWorkshop` |
| `CB_AtenderOrdenTaller` | Técnico de Taller (`tecnicos-taller`), Inspector de Calidad (`inspectores-calidad`) | `diagnoseVehicle`, `repairVehicle`, `inspectRepair` |

La tarea `confirmSolution` del solicitante se atiende en el Portal TI, y las respuestas del cliente, en el Portal Cliente.

**Comunicación con el motor.** El navegador nunca llama al motor: habla solo con `/api/app/**` de este backend, que usa la REST de Camunda (`ENGINE_REST_URL`) con la credencial interna `X-CB-Internal`, a través de `MotorClient` de `cb-comun`:

- Bandeja y permisos: `GET /engine-rest/task`, `/task/{id}`, `/task/{id}/identity-links`, `/process-instance` y `/process-definition/{id}`.
- Acciones: `POST /engine-rest/task/{id}/claim`, `/unclaim` y `/complete`; formulario y variables con `GET /task/{id}/deployed-form`, `/form-variables` y `/variables`.
- Nómina: `POST /engine-rest/process-definition/key/CB_GestionNomina/start` con `businessKey` `NOM-<periodo>-<6 caracteres>`.
- Reasignación tras `reviewSla`: `POST /engine-rest/task/{id}/assignee` (comprueba antes al nuevo responsable con `/user` y `/group`).
- Inicio de sesión: `POST /engine-rest/identity/verify` y `GET /engine-rest/group?member=`.

No envía mensajes de correlación y el motor no lo invoca con conectores. El **panel de bodega** no habla con el motor: reenvía cada acción a Taller-Bodega (`TALLER_BODEGA_URL`) con la credencial interna, y es Taller-Bodega quien envía al motor `RepuestosDisponibles` o `RepuestoRecibido`.

## Puerto, pantallas y API

Puerto **9003**. Página única en http://localhost:9003/ con estas secciones:

- **Mis tareas** y panel de la tarea: bandeja, formulario (form-js, servido localmente), reclamar, completar y soltar.
- **Iniciar nómina** (solo `analistas-nomina` y administrador): ciclo, periodo, fecha de corte y tres casillas "Simular una excepción (demostración)".
- **Bodega** (solo `bodega` y administrador): reservas pendientes de repuestos, repuestos solicitados por el taller y simulación de la bodega.
- **Mi actividad reciente:** últimas 20 acciones del usuario.

**Quién entra:** empleados y administrador; una cuenta del grupo `clientes` recibe 403. Cuentas de demostración (contraseña `demo`): `mesa1`, `soporte1`, `especialista1`, `responsable1`, `nomina1`, `talento1`, `jefatura1`, `tesoreria1`, `asesor1`, `garantia1`, `coordinador1`, `tecnico1`, `calidad1`, `bodega1` y `demo` (administrador, ve todas las tareas). El grupo de cada cuenta está en el README del hub.

Todas las rutas de la tabla están bajo `/api/app` y las llama el navegador con sesión (más `/csrf`, `/login`, `/logout` y `/me`, que aporta `cb-comun`):

| Método y ruta | Qué hace |
|---|---|
| `GET /central/tasks` | Bandeja del usuario |
| `POST /central/tasks/{id}/claim`, `POST /central/tasks/{id}/unclaim` | Reclama o suelta la tarea; 403 sin permiso, 409 si otra persona ya la tiene |
| `GET /central/tasks/{id}/deployed-form`, `/form-variables`, `/variables` | Formulario desplegado y variables de la tarea |
| `POST /central/tasks/{id}/complete` | Completa la tarea; solo acepta los campos editables del formulario y fija su tipo en el servidor |
| `POST /central/nomina` | Inicia `CB_GestionNomina`; responde `{businessKey, id}` |
| `GET /central/actividad` | Últimas 20 acciones del usuario |
| `GET /bodega/reservas`, `GET /bodega/solicitudes`, `GET /bodega/simulacion` | Consultas reenviadas a Taller-Bodega |
| `POST /bodega/reservas/{id}/disponibles`, `POST /bodega/solicitudes/{id}/entregar`, `PUT /bodega/simulacion` | Acciones reenviadas a Taller-Bodega; 409 si el proceso aún no espera el aviso |

Reglas de permiso, validaciones y formatos: sección 5.3 del [contrato de integración](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CONTRATO-INTEGRACION.md).

## Datos

Tabla propia `cs_actividad` (prefijo `cs_`) en la base `corocora`: usuario, acción (`RECLAMAR`, `SOLTAR`, `COMPLETAR`, `INICIAR_NOMINA`, `AVISAR_DISPONIBILIDAD`, `ENTREGAR_REPUESTO`, `CAMBIAR_SIMULACION`), tarea, `business_key`, detalle y fecha. Las tareas no se copian: se leen del motor en cada consulta. La tabla la crea JPA al arrancar (`ddl-auto: update`). Más detalle en el [modelo de datos](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/MODELO-DE-DATOS.md).

## Configuración

| Variable de entorno | Para qué | Valor por defecto |
|---|---|---|
| `SERVER_ADDRESS` | Interfaz en la que escucha | `127.0.0.1` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Servidor PostgreSQL y base de negocio | `localhost`, `5432`, `corocora` |
| `DB_USER`, `DB_PASSWORD` | Usuario y contraseña de PostgreSQL | `postgres` y la contraseña local definida en `application.yaml` |
| `COROCORA_INTERNAL_TOKEN` | Credencial interna ante el motor y Taller-Bodega; la misma en los seis sistemas | `cb-local-interno-demo` |
| `ENGINE_REST_URL` | REST de Camunda en el motor | `http://localhost:9000/engine-rest` |
| `TALLER_BODEGA_URL` | Destino del panel de bodega | `http://localhost:9005` |

Perfil `h2` (`--spring.profiles.active=h2`): usa una base H2 en memoria (`jdbc:h2:mem:cb-central-sys`) que se borra al detener el sistema. La cookie de sesión se llama `CBSESSION_CENTRAL`.

## Compilar y ejecutar

Este repositorio no compila clonado por separado: su `pom.xml` hereda del pom padre del hub (`com.corocorabenz:corocora-benz:1.0.0-SNAPSHOT`) y usa la librería `cb-comun`. Se compila desde el hub, con Java 17 o superior (Maven no hace falta: el hub trae su wrapper).

```powershell
git clone https://github.com/MiguelMorales1125/Corocora-Benz.git
cd Corocora-Benz/corocora-benz-embebida
.\mvnw.cmd -pl cb-central-sys -am package -DskipTests
java -jar cb-central-sys/target/cb-central-sys-1.0.0-SNAPSHOT.jar --spring.profiles.active=h2
```

En Linux o macOS se usa `./mvnw` en lugar de `.\mvnw.cmd`; lo demás es igual. Sin `--spring.profiles.active=h2` usa PostgreSQL y necesita la base `corocora` (se crea con `psql -U postgres -f crear-bases.sql`, script del hub).

- **Orden de arranque:** primero el motor (`cb-bpm-engine`, puerto 9000); sin él no hay inicio de sesión ni tareas (la API responde 502). El panel de bodega necesita además Taller-Bodega (9005). Para que haya tareas en la bandeja, algún proceso debe estar en curso: se inician desde el Portal Cliente (9001), el Portal TI (9002) o la sección "Iniciar nómina".
- **Comprobar que arrancó:** http://localhost:9003/ debe mostrar el formulario de inicio de sesión; entra con `mesa1` / `demo`.

## Pruebas

```powershell
.\mvnw.cmd -pl cb-central-sys -am test
```

Son 51 pruebas con H2, sin ningún otro sistema arrancado (`-am` ejecuta además las de `cb-comun`):

- `CentralSysApiTest` (37), con el motor y la bodega simulados: sesión y CSRF, bandeja por grupos, permisos para reclamar, soltar, leer y completar, campos aceptados al completar, reasignación tras `reviewSla`, inicio y validación de la nómina con sus casillas de simulación, panel de bodega y registro de actividad.
- `FormularioTareaTest` (7): qué campos del formulario se aceptan, con qué tipo, y las validaciones de obligatorios, números, límites y opciones.
- `ClienteBodegaTest` (7): rutas de Taller-Bodega, credencial interna y traducción de sus errores (404 y 409 se conservan; el resto es 502).

Los casos de prueba de los procesos completos se documentan en [CASOS-DE-PRUEBA.md](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CASOS-DE-PRUEBA.md).

## Estructura del código

Paquete base `com.corocorabenz.centralsys` (`src/main/java`):

- `CentralSysApplication`: arranque de Spring Boot.
- `tareas`: `TareasController` (`/api/app/central/tasks`), `BandejaService`, `TareasService`, `AccesoTareas` y `PermisoTarea` (regla de permiso), `FormularioTarea` (campos aceptados y tipos), `Reasignacion` y `MotorTareas` (lecturas del motor).
- `nomina`: `NominaController`, inicio del ciclo de nómina.
- `bodega`: `BodegaController` y `ClienteBodega`, panel que reenvía a Taller-Bodega.
- `actividad`: entidad `Actividad`, `RegistroActividad` y `ActividadController`.
- `web`: `HomeController` (página).
- `src/main/resources`: `application.yaml`, `templates/index.html`, `static/app.js`, `static/central.css` y `static/vendor/form-js/` (form-js con su licencia). `auth.js` y `portal.css` vienen de `cb-comun`.
