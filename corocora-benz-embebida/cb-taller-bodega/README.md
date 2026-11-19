# CB-Taller-Bodega (`cb-taller-bodega`)

Simulador del sistema de taller y bodega: historial y campañas por VIN, cotizaciones, reserva de repuestos y solicitudes de repuesto adicional para el proceso de postventa (Spring Boot 3.5.6, Java 17). No tiene pantallas propias: su panel se ve en CentralSys.

> **Este repositorio es un componente de Corocora Benz. El hub integrador, con la guía general, está en https://github.com/MiguelMorales1125/Corocora-Benz.**

Repositorio de este componente: https://github.com/MiguelMorales1125/CorocoraBenz-TallerBodega

## Propósito

Atiende las tareas de servicio del proceso **Postventa y Garantías** (`CB_PostventaGarantias`) y de su subproceso `CB_AtenderOrdenTaller`, y representa al participante externo **Bodega de repuestos** del BPMN.

| Elemento del BPMN (carril) | Sentido | Qué ocurre |
|---|---|---|
| `vehicleLookup` "Consultar historial y campañas del VIN" (Asesor de Servicio) | Motor → Taller-Bodega | `GET /api/vehiculos/{vin}?km=`; el resultado alimenta la decisión `CB_EvaluarCoberturaGarantia` |
| `prepareQuote` "Enviar cotización al cliente" (Asesor de Servicio) | Motor → Taller-Bodega | `POST /api/cotizaciones`; devuelve `quoteId` y `quoteAmount` |
| `reserveParts` "Reservar repuestos" (Coordinador de Postventa) | Motor → Taller-Bodega | `POST /api/reservas`; devuelve `partsReservationStatus` (`RESERVED` o `PENDIENTE`) |
| `waitParts` "Esperar aviso de repuestos disponibles" | Taller-Bodega → Motor | Mensaje `RepuestosDisponibles` con `partsReservationStatus` `RESERVED` |
| `requestPart` "Solicitar repuesto a bodega" (Técnico de Taller, en `CB_AtenderOrdenTaller`) | Motor → Taller-Bodega | `POST /api/solicitudes-repuesto` |
| `receivePart` "Repuesto recibido" (en `CB_AtenderOrdenTaller`) | Taller-Bodega → Motor | Mensaje `RepuestoRecibido` con `partReceivedRef` `REM-<id>` |

**Comunicación con el motor.** En los dos sentidos se usa la credencial interna `X-CB-Internal`:

- *Entrada:* el motor invoca esta API con sus conectores HTTP (`${sistemas.tallerBodega}/api/...`).
- *Salida:* `MotorClient` (de `cb-comun`) envía `POST /engine-rest/message` con la `businessKey` del proceso. Si el proceso aún no espera el aviso, comprueba con `GET /engine-rest/process-instance` que siga activo y lo deja pendiente para reintentar; si ya no existe, el registro queda `SIN_PROCESO`.
- *Consulta:* para poner al día el estado de las cotizaciones (`ENVIADA`, `ACEPTADA`, `RECHAZADA`, `VENCIDA`) lee `GET /engine-rest/history/process-instance` y `/history/activity-instance`, porque la respuesta del cliente llega al motor por el Portal Cliente y no a este sistema.

**Simulación.** Con la respuesta automática activa (valor inicial), la bodega entrega sola cada solicitud de repuesto pasados 10 segundos. Con ella apagada espera `entregar`, que también se puede lanzar desde el panel de bodega de CentralSys. `repuestosDisponibles` (por defecto `true`) decide si una reserva nace `RESERVED` o `PENDIENTE`; las reservas pendientes nunca se avisan solas: requieren `disponibles`. La cotización vale siempre 850 000 y la garantía cubre a los vehículos registrados hasta 60 000 km.

## Puerto y API

Puerto **9005**. Toda la API (`/api/*`) exige la credencial interna; sin ella responde 401 `{"error":"Credencial interna requerida"}`.

| Método y ruta | Quién la llama | Qué hace |
|---|---|---|
| `GET /api/estado` | Comprobación de arranque | Responde `{"sistema":"CB-Taller-Bodega","estado":"OK"}` |
| `GET /api/vehiculos/{vin}` (`?km=`) | Motor (`vehicleLookup`) | `registrado`, `modelo`, `historyRecordId`, `warrantyEligible`, `campaignActive`, `campaignId`; 400 si el VIN está mal formado |
| `POST /api/cotizaciones` | Motor (`prepareQuote`) | Crea la cotización (201); repetir la petición devuelve la misma (200); 409 si la `businessKey` ya tiene otra |
| `GET /api/cotizaciones` (`?businessKey=`) | Pruebas, consulta manual | Lista de cotizaciones con su estado al día |
| `POST /api/reservas` | Motor (`reserveParts`) | Crea la reserva (201); repetir la petición devuelve la misma (200) |
| `GET /api/reservas` | CentralSys (panel de bodega), pruebas | Lista de reservas |
| `POST /api/reservas/{id}/disponibles` | CentralSys (panel de bodega), pruebas | Envía `RepuestosDisponibles`; 404, 409 si el proceso no lo espera, 502 si el motor no responde |
| `POST /api/solicitudes-repuesto` | Motor (`requestPart`) | Registra la solicitud (201) y devuelve `partRequestId` |
| `GET /api/solicitudes-repuesto` (`?businessKey=`) | CentralSys (panel de bodega), pruebas | Lista de solicitudes |
| `POST /api/solicitudes-repuesto/{id}/entregar` | CentralSys (panel de bodega), pruebas | Envía `RepuestoRecibido`; mismos códigos de error |
| `GET /api/simulacion`, `PUT /api/simulacion` | CentralSys (panel de bodega), pruebas | Consulta o cambia `respuestaAutomatica`, `retardoSegundos` y `repuestosDisponibles` |

Campos de cada respuesta y validaciones: sección 7.2 del [contrato de integración](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CONTRATO-INTEGRACION.md).

## Datos

Tablas propias con prefijo `tb_` en la base `corocora`, creadas por JPA al arrancar (`ddl-auto: update`): `tb_vehiculo`, `tb_cotizacion`, `tb_reserva` y `tb_solicitud_repuesto`. La primera vez que arranca carga cuatro vehículos de prueba: `CB0TEST0000000001` (con campaña activa) y `WDB0000000000000B`, `WDB0000000000000C`, `WDB0000000000000D` (sin campaña). La configuración de la simulación vive en memoria. Más detalle en el [modelo de datos](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/MODELO-DE-DATOS.md).

## Configuración

| Variable de entorno | Para qué | Valor por defecto |
|---|---|---|
| `SERVER_ADDRESS` | Interfaz en la que escucha | `127.0.0.1` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Servidor PostgreSQL y base de negocio | `localhost`, `5432`, `corocora` |
| `DB_USER`, `DB_PASSWORD` | Usuario y contraseña de PostgreSQL | `postgres` y la contraseña local definida en `application.yaml` |
| `COROCORA_INTERNAL_TOKEN` | Credencial interna: la exige en `/api/*` y la envía al motor; la misma en los seis sistemas | `cb-local-interno-demo` |
| `ENGINE_REST_URL` | REST de Camunda en el motor | `http://localhost:9000/engine-rest` |
| `BODEGA_RESPUESTA_AUTOMATICA` | `false` para que la bodega no entregue sola los repuestos solicitados | `true` |

Perfil `h2` (`--spring.profiles.active=h2`): usa una base H2 en memoria (`jdbc:h2:mem:cb-taller-bodega`) que se borra al detener el sistema.

## Compilar y ejecutar

Este repositorio no compila clonado por separado: su `pom.xml` hereda del pom padre del hub (`com.corocorabenz:corocora-benz:1.0.0-SNAPSHOT`) y usa la librería `cb-comun`. Se compila desde el hub, con Java 17 o superior (Maven no hace falta: el hub trae su wrapper).

```powershell
git clone https://github.com/MiguelMorales1125/Corocora-Benz.git
cd Corocora-Benz/corocora-benz-embebida
.\mvnw.cmd -pl cb-taller-bodega -am package -DskipTests
java -jar cb-taller-bodega/target/cb-taller-bodega-1.0.0-SNAPSHOT.jar --spring.profiles.active=h2
```

En Linux o macOS se usa `./mvnw` en lugar de `.\mvnw.cmd`; lo demás es igual. Sin `--spring.profiles.active=h2` usa PostgreSQL y necesita la base `corocora` (se crea con `psql -U postgres -f crear-bases.sql`, script del hub).

- **Orden de arranque:** primero el motor (`cb-bpm-engine`, puerto 9000), que es quien llama a esta API y recibe los avisos. Para que haya actividad debe existir una solicitud de postventa, que nace en el Portal Cliente (9001) y avanza en CentralSys (9003), donde además está el panel de bodega.
- **Comprobar que arrancó:** `curl -H "X-CB-Internal: cb-local-interno-demo" http://localhost:9005/api/estado` debe responder `{"sistema":"CB-Taller-Bodega","estado":"OK"}` (en PowerShell, `curl.exe`). Sin el encabezado responde 401.

## Pruebas

```powershell
.\mvnw.cmd -pl cb-taller-bodega -am test
```

Son 30 pruebas con H2 y el motor simulado, sin ningún otro sistema arrancado (`-am` ejecuta además las de `cb-comun`):

- `TallerBodegaApiTest` (28): credencial interna (encabezado y `Bearer`); vehículos conocidos, desconocidos, sin garantía por kilometraje y VIN inválido; cotizaciones idempotentes y su estado según el historial del motor; reservas reservadas o pendientes; avisos y entregas manuales con sus 404, 409 y 502; ciclo automático (retardo, reintentos, `SIN_PROCESO`, motor caído) y la API de simulación.
- `ArranqueTest` (2): la aplicación arranca con el `MotorClient` de `cb-comun` y toda la API exige la credencial interna.

Los casos de prueba de postventa completa se documentan en [CASOS-DE-PRUEBA.md](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CASOS-DE-PRUEBA.md).

## Estructura del código

Paquete base `com.corocorabenz.tallerbodega` (`src/main/java`):

- `TallerBodegaApplication`: arranque de Spring Boot, con tareas programadas activas (`@EnableScheduling`).
- `vehiculo`: `VehiculoController` (`/api/vehiculos`), la entidad `Vehiculo` con su repositorio y `DatosIniciales` (vehículos de prueba).
- `cotizacion`: `CotizacionController` (`/api/cotizaciones`), la entidad `Cotizacion` con su repositorio y `EstadoCotizacionService` (estado según el historial del motor).
- `repuesto`: `RepuestoController` (`/api/reservas`, `/api/solicitudes-repuesto`), `BodegaService` (avisos al motor y ciclo automático) y las entidades `Reserva` y `SolicitudRepuesto` con sus repositorios.
- `motor`: `AvisoAlMotor` y `ResultadoAviso`, regla de reintento de los avisos.
- `simulacion`: `Simulacion` y `SimulacionController` (`/api/simulacion`).
- `api`: `EstadoController` (`/api/estado`).
- `src/main/resources`: `application.yaml`.
