# Plan: Corocora Benz por microservicios

Basado en la arquitectura del caso MSGF (`09 MSGF Architecture.pdf`): un motor Camunda embebido en Spring Boot que orquesta varios sistemas de información. Cada sistema sale de un pool o actor de nuestros BPMN.

Este documento es el plan con el que se hizo la migración. El detalle vigente de sesión, credencial interna y APIs está en [CONTRATO-INTEGRACION.md](CONTRATO-INTEGRACION.md), que es la fuente de verdad.

## Los 6 sistemas

| # | Módulo | Puerto | Tipo | Equivale en MSGF | Responsable |
|---|---|---|---|---|---|
| 1 | `cb-bpm-engine` | 9000 | Camunda 7 embebido + Postgres + correo | BPM-Engine | Persona 3 |
| 2 | `cb-portal-cliente` | 9001 | Web (cliente externo) | CreditRequest | Persona 2 |
| 3 | `cb-portal-ti` | 9002 | Web (empleado que reporta) + API de tickets | CreditRequest | Persona 1 |
| 4 | `cb-central-sys` | 9003 | Web (empleados; bandeja según los grupos de la sesión) | CentralSys | Los 3, cada uno su sección |
| 5 | `cb-banco` | 9004 | Solo API REST | Treasury | Persona 3 |
| 6 | `cb-taller-bodega` | 9005 | Solo API REST | Treasury | Persona 2 |

Por qué 6: 1 motor + 1 intranet para todos los lanes internos + 1 portal por cada actor que inicia un proceso desde fuera (Cliente, Usuario solicitante de TI) + 1 API por cada participante externo que el motor llama (Banco, Bodega de Repuestos).

## Comunicación

```
PortalCliente (9001) ──Camunda REST──┐
PortalTI      (9002) ──Camunda REST──┤
CentralSys    (9003) ──Camunda REST──┼──► BPM-Engine (9000) ──correo──► Mailpit / log
                                     │         │
Banco         (9004) ◄──HTTP Connector─────────┤  (y responde con mensaje por Camunda REST)
Taller-Bodega (9005) ◄──HTTP Connector─────────┤  (y responde con mensaje por Camunda REST)
PortalTI API  (9002) ◄──HTTP Connector─────────┘  (tickets y base de conocimiento)
```

- Webs → motor: REST API de Camunda en `http://localhost:9000/engine-rest` (iniciar procesos, enviar mensajes, listar y completar tareas), desde el backend de cada portal y con la credencial interna; el navegador no llama al motor.
- Motor → sistemas: `camunda:connector` con `http-connector`, o `JavaDelegate` para lógica interna.
- Sistemas → motor (respuestas asíncronas): `POST /engine-rest/message` con la `businessKey`.

## Bases de datos (PostgreSQL local)

| Base | La usa | Contenido |
|---|---|---|
| `corocora_camunda` | Engine | Tablas de Camunda (las crea solo) |
| `corocora` | Portales, CentralSys, Banco, Taller-Bodega | Tablas de negocio, con un prefijo por sistema (`ti_*`, `pc_*`, `cs_*`, `banco_*`, `tb_*`) |

Credenciales por variables de entorno: `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` (por defecto `localhost:5432`, `postgres`). Para correr sin Postgres: perfil `h2` (`--spring.profiles.active=h2`).

## Qué pasa con cada tarea automática (antes workers JS)

| Topic anterior | Proceso | Nueva implementación | Dónde |
|---|---|---|---|
| `ti.crearTicket` | TI | HTTP Connector `POST /api/tickets` | PortalTI |
| `ti.registrarSolucion` | TI | HTTP Connector `POST /api/tickets/{id}/solucion` | PortalTI |
| `ti.cerrarTicket` | TI | HTTP Connector `POST /api/tickets/{id}/cerrar` | PortalTI |
| `postventa.consultarVehiculo` | Postventa | HTTP Connector `GET /api/vehiculos/{vin}` | Taller-Bodega |
| `postventa.enviarCotizacion` | Postventa | HTTP Connector `POST /api/cotizaciones` | Taller-Bodega (el cliente la ve en PortalCliente) |
| `postventa.reservarRepuestos` | Postventa | HTTP Connector `POST /api/reservas` | Taller-Bodega |
| `postventa.solicitarRepuesto` | Taller | HTTP Connector `POST /api/solicitudes-repuesto`; después la bodega envía `RepuestoRecibido` | Taller-Bodega |
| `nomina.obtenerNovedades` | Nómina | JavaDelegate | Engine |
| `nomina.calcularLiquidacion` | Nómina | JavaDelegate | Engine |
| `nomina.generarComprobantes` | Nómina | JavaDelegate | Engine |
| `nomina.enviarDispersion` | Nómina | HTTP Connector `POST /api/dispersiones`; después el banco envía `DispersionAceptada` o `DispersionRechazada` | Banco |
| `nomina.registrarPago` | Nómina | JavaDelegate | Engine |
| `comun.enviarNotificacion` | Todos | JavaDelegate que envía correo | Engine |

Los mensajes que hoy se simulan en Swagger los mandan sistemas reales:

| Mensaje | Lo envía |
|---|---|
| `SolicitudServicio` | PortalCliente (el cliente crea la solicitud) |
| `CotizacionAceptada` / `CotizacionRechazada` | PortalCliente (el cliente responde la cotización) |
| `RepuestoRecibido` | Taller-Bodega |
| `RepuestosDisponibles` | Taller-Bodega (llegaron los repuestos de una reserva pendiente) |
| `DispersionAceptada` / `DispersionRechazada` | Banco |

## Vistas por sistema

- **PortalCliente:** crear solicitud de servicio (VIN, cliente, tipo, km, síntoma), ver mis solicitudes y su estado, aceptar o rechazar la cotización.
- **PortalTI:** reportar incidente (los campos de `ti-reporte`), ver mis tickets, confirmar la solución (tarea `confirmSolution`).
- **CentralSys:** bandeja de tareas según los grupos del usuario que inició sesión (`candidateGroup`), reclamar, ver y completar la tarea con su formulario (form-js con los `.form` del motor), iniciar el ciclo de nómina.
- **Banco y Taller-Bodega:** sin pantallas, solo API y log en consola (como Treasury).

## Fases

- [x] **F1. Estructura:** Maven multi-módulo con los 6 módulos; `backend` pasa a `cb-bpm-engine` en el puerto 9000 con Postgres (o perfil `h2`). El CORS abierto de esta fase se retiró después: las webs llaman al motor desde su backend.
- [x] **F2. Motor:** JavaDelegates de nómina y notificaciones; correo real (Mailpit) o log.
- [x] **F3. APIs externas:** Banco y Taller-Bodega con sus tablas y respuestas asíncronas; cambiar sus topics en los BPMN a `http-connector`.
- [x] **F4. PortalTI:** API de tickets + vistas; cambiar los topics `ti.*` a `http-connector`.
- [x] **F5. PortalCliente:** vistas y mensajes.
- [x] **F6. CentralSys:** bandejas por grupo con form-js; convertir los 12 formularios generados (dentro de los BPMN) a `.form`.
- [x] **F7. Cierre:** quitar los workers JS, adaptar `e2e.mjs`, actualizar README y diagrama de arquitectura.

Regla para los modelos: todo cambio a un `.bpmn`, `.dmn` o `.form` se hace en la carpeta raíz (`bpmn/`, `dmn/`, `form/`) **y** en `cb-bpm-engine/src/main/resources/static.*`. Las dos copias deben quedar idénticas.
