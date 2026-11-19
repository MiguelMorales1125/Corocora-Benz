# CB-PortalTI (`cb-portal-ti`)

Mesa de ayuda de TI: portal web donde un empleado reporta incidentes, sigue sus tickets y confirma la solución, y API interna de tickets que el motor usa durante el proceso (Spring Boot 3.5.6, Java 17).

> **Este repositorio es un componente de Corocora Benz. El hub integrador, con la guía general, está en https://github.com/MiguelMorales1125/Corocora-Benz.**

Repositorio de este componente: https://github.com/MiguelMorales1125/CorocoraBenz-PortalTI

## Propósito

Participa en el proceso **Gestión de Incidentes TI** (`CB_GestionIncidenteTI`) y en su subproceso `CB_ResolverIncidenteTI`, con dos papeles:

**1. Portal del carril "Usuario Interno Solicitante".** El backend llama a la REST de Camunda (`ENGINE_REST_URL`) con la credencial interna `X-CB-Internal`, a través de `MotorClient` de `cb-comun`; el navegador nunca llama al motor.

| Elemento del BPMN | Qué hace el portal |
|---|---|
| Evento de inicio `startIncident` ("Incidente de TI reportado") | `POST /engine-rest/process-definition/key/CB_GestionIncidenteTI/start` con una `businessKey` `TI-` más 8 caracteres y `requesterId` igual al usuario de la sesión |
| Tarea de usuario `confirmSolution` ("Confirmar solución del incidente", asignada a `${requesterId}`) | `POST /engine-rest/task/{id}/complete` con la variable `userConfirmed` |

También consulta `GET /engine-rest/task` (para saber qué tickets tienen confirmación pendiente y para detectar que soporte retomó un ticket ya resuelto) y valida el inicio de sesión con `POST /engine-rest/identity/verify` y `GET /engine-rest/group?member=`.

**2. Sistema de tickets que el motor invoca con conectores HTTP** (carril "Mesa de Servicio" y subproceso de soporte):

| Tarea de servicio del BPMN | Llamada del motor |
|---|---|
| `createTicket` ("Registrar ticket del incidente") | `POST /api/tickets` |
| `recordResolution` ("Registrar solución en la base de conocimiento", en `CB_ResolverIncidenteTI`) | `POST /api/tickets/{ticketId}/solucion` |
| `closeTicket` ("Cerrar ticket verificado") y `closeAutoTicket` ("Cerrar ticket por falta de respuesta") | `POST /api/tickets/{ticketId}/cerrar` |

Este portal no envía mensajes de correlación. Las tareas de mesa de servicio, soporte y responsable de TI (`triageIncident`, `resolveN1`, `resolveSpecialist`, `reviewSla`) se atienden en CentralSys.

## Puerto, pantallas y API

Puerto **9002**. Página única en http://localhost:9002/ con dos secciones:

- **Reportar incidente:** sistema afectado, descripción, impacto, urgencia, equipos afectados, si afecta la seguridad y un correo de notificación opcional.
- **Mis tickets:** tickets del usuario con su estado, causa y solución; cuando el proceso espera su respuesta aparecen los botones para confirmar o rechazar la solución.

**Quién entra:** cualquier empleado y el administrador; una cuenta del grupo `clientes` recibe 403. Cuentas de demostración: `USR-01` (contraseña `USR-01`) para reportar incidentes; también `mesa1`, `soporte1`, `especialista1`, `responsable1` y `demo` (contraseña `demo`). Cada persona ve solo sus tickets.

| Método y ruta | Quién la llama | Qué hace |
|---|---|---|
| `GET /` | Navegador | Página de la mesa de ayuda |
| `GET /api/app/csrf`, `POST /api/app/login`, `POST /api/app/logout`, `GET /api/app/me` | Navegador | Sesión común de los portales (la aporta `cb-comun`) |
| `POST /api/app/ti/reports` | Navegador (empleado) | Inicia el proceso; responde `{"businessKey":"TI-XXXXXXXX"}` |
| `GET /api/app/ti/tickets` | Navegador (empleado) | Tickets del usuario, con `canConfirm` |
| `POST /api/app/ti/tickets/{ticketId}/confirm` | Navegador (empleado) | `{"confirmed":true}` o `false`; 404 si no es suyo, 409 si no hay confirmación pendiente |
| `POST /api/tickets` | Motor (conector HTTP) | Crea el ticket de una `businessKey`; idempotente. Responde `ticketId` y `ticketStatus` |
| `POST /api/tickets/{ticketId}/solucion` | Motor (conector HTTP) | Guarda `rootCause` y `resolutionSummary`; responde `knowledgeArticleId` |
| `POST /api/tickets/{ticketId}/cerrar` | Motor (conector HTTP) | Cierra el ticket |
| `POST /api/tickets/{ticketId}/reabrir` | Uso interno (con credencial) | Devuelve el ticket a abierto; 409 si ya está cerrado |
| `GET /api/tickets?requesterId=` | Uso interno (con credencial) | Tickets de un usuario |

`/api/tickets` y `/api/tickets/*` exigen la credencial interna (401 sin ella) y no usan sesión. `/api/app/**` exige sesión y, en las escrituras, el encabezado CSRF. Validaciones y formatos: secciones 2, 3 y 5.2 del [contrato de integración](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CONTRATO-INTEGRACION.md).

## Datos

Tabla propia `ti_ticket` (prefijo `ti_`) en la base `corocora`: `business_key` única, usuario que reporta, datos del incidente, estado (`OPEN`, `RESOLVED`, `CLOSED`), causa raíz y resumen de la solución. El identificador visible es `TI-<id>`. La tabla la crea JPA al arrancar (`ddl-auto: update`). Más detalle en el [modelo de datos](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/MODELO-DE-DATOS.md).

## Configuración

| Variable de entorno | Para qué | Valor por defecto |
|---|---|---|
| `SERVER_ADDRESS` | Interfaz en la que escucha | `127.0.0.1` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Servidor PostgreSQL y base de negocio | `localhost`, `5432`, `corocora` |
| `DB_USER`, `DB_PASSWORD` | Usuario y contraseña de PostgreSQL | `postgres` y la contraseña local definida en `application.yaml` |
| `COROCORA_INTERNAL_TOKEN` | Credencial interna: la envía al motor y la exige en `/api/tickets`; la misma en los seis sistemas | `cb-local-interno-demo` |
| `ENGINE_REST_URL` | REST de Camunda en el motor | `http://localhost:9000/engine-rest` |

Perfil `h2` (`--spring.profiles.active=h2`): usa una base H2 en memoria (`jdbc:h2:mem:cb-portal-ti`) que se borra al detener el portal. La cookie de sesión se llama `CBSESSION_TI`.

## Compilar y ejecutar

Este repositorio no compila clonado por separado: su `pom.xml` hereda del pom padre del hub (`com.corocorabenz:corocora-benz:1.0.0-SNAPSHOT`) y usa la librería `cb-comun`. Se compila desde el hub, con Java 17 o superior (Maven no hace falta: el hub trae su wrapper).

```powershell
git clone https://github.com/MiguelMorales1125/Corocora-Benz.git
cd Corocora-Benz/corocora-benz-embebida
.\mvnw.cmd -pl cb-portal-ti -am package -DskipTests
java -jar cb-portal-ti/target/cb-portal-ti-1.0.0-SNAPSHOT.jar --spring.profiles.active=h2
```

En Linux o macOS se usa `./mvnw` en lugar de `.\mvnw.cmd`; lo demás es igual. Sin `--spring.profiles.active=h2` usa PostgreSQL y necesita la base `corocora` (se crea con `psql -U postgres -f crear-bases.sql`, script del hub).

- **Orden de arranque:** primero el motor (`cb-bpm-engine`, puerto 9000); sin él no se puede iniciar sesión ni reportar (la API responde 502). Para que un incidente avance hace falta también CentralSys (9003), donde mesa de servicio y soporte atienden sus tareas.
- **Comprobar que arrancó:** http://localhost:9002/ debe mostrar el formulario de inicio de sesión, y `curl "http://localhost:9002/api/tickets?requesterId=USR-01"` debe responder 401 (200 si se agrega `-H "X-CB-Internal: cb-local-interno-demo"`). En PowerShell, `curl.exe`.

## Pruebas

```powershell
.\mvnw.cmd -pl cb-portal-ti -am test
```

Son 21 pruebas en `PortalTiTest`, con H2 y el motor simulado, sin ningún otro sistema arrancado (`-am` ejecuta además las de `cb-comun`). Cubren:

- Sesión y permisos: 401 sin sesión, 403 para clientes, 403 sin token CSRF.
- Reporte: el proceso arranca a nombre de quien reporta, validación del cuerpo (400) y 502 si el motor falla.
- Tickets: cada persona ve solo los suyos y sabe cuál puede confirmar; sin motor se listan sin opción de confirmar.
- Confirmación: completa la tarea con la respuesta, 404 para tickets ajenos y 409 si no hay tarea pendiente.
- API interna: exige la credencial, crear ticket es idempotente por `businessKey` y un ticket cerrado no admite solución ni reapertura.

Los casos de prueba de los procesos completos se documentan en [CASOS-DE-PRUEBA.md](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CASOS-DE-PRUEBA.md).

## Estructura del código

Paquete base `com.corocorabenz.portalti` (`src/main/java`):

- `PortalTiApplication`: arranque de Spring Boot.
- `app`: API del navegador. `MesaAyudaController` (`/api/app/ti`), `MesaAyudaService` (reportar, listar, confirmar), `SoloEmpleadosInterceptor` y `MesaAyudaWebConfig` (403 a los clientes), y los registros `ReporteIncidente`, `Confirmacion` y `TicketVista`.
- `ticket`: API interna. `TicketController` (`/api/tickets`), la entidad `Ticket` con su repositorio y `EstadoTicketService` (reabre un ticket resuelto cuando el proceso vuelve a soporte).
- `web`: `HomeController` (página).
- `src/main/resources`: `application.yaml`, `templates/index.html` y `static/app.js`. `auth.js` y `portal.css` vienen de `cb-comun`.
