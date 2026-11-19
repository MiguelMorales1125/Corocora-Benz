# CB-Banco (`cb-banco`)

Simulador del banco: recibe del motor el lote de dispersión de la nómina y le responde, por mensaje, si fue aceptado o rechazado (Spring Boot 3.5.6, Java 17). No tiene pantallas.

> **Este repositorio es un componente de Corocora Benz. El hub integrador, con la guía general, está en https://github.com/MiguelMorales1125/Corocora-Benz.**

Repositorio de este componente: https://github.com/MiguelMorales1125/CorocoraBenz-Banco

## Propósito

Representa al participante externo **Banco** del proceso **Gestión de Nómina** (`CB_GestionNomina`). Interviene en el tramo del carril **Tesorería** que paga la nómina:

| Elemento del BPMN | Sentido | Qué ocurre |
|---|---|---|
| Tarea de servicio `sendBankBatch` ("Enviar dispersión al Banco") | Motor → Banco | El conector HTTP del motor llama a `POST /api/dispersiones` con `businessKey`, `paymentBatchId`, `bankAccount`, `payrollTotal` y `payPeriodId` |
| Evento `paymentAccepted` ("Dispersión aceptada") | Banco → Motor | Mensaje `DispersionAceptada` con `bankResponseCode` `00-APROBADA` y `bankReference` |
| Evento `paymentRejected` ("Dispersión rechazada") | Banco → Motor | Mensaje `DispersionRechazada` con `bankResponseCode` (por defecto `R03-CUENTA-INVALIDA`); el proceso abre la tarea `fixBankBatch` de Tesorería |

Si el banco no responde en 2 horas, el temporizador `bankTimeout` del proceso lleva también a `fixBankBatch`; eso lo decide el motor, no este sistema.

**Comunicación con el motor.** En los dos sentidos se usa la credencial interna `X-CB-Internal`:

- *Entrada:* el motor invoca la API de este sistema con su conector HTTP (`${sistemas.banco}/api/dispersiones`).
- *Salida:* la respuesta es asíncrona. `MotorClient` (de `cb-comun`) envía `POST /engine-rest/message` con el nombre del mensaje y la misma `businessKey` del proceso. Si el proceso todavía no espera el mensaje, comprueba con `GET /engine-rest/process-instance` que siga activo y reintenta en el siguiente ciclo (cada 2 segundos); si ya no existe, la dispersión queda `SIN_PROCESO` y no se reintenta.

**Simulación.** Con la respuesta automática activa (valor inicial), el banco contesta solo cada dispersión `RECIBIDA` pasados 10 segundos: acepta las cuentas con formato `000-123` (tres dígitos, guion y al menos tres dígitos) y rechaza las demás. Con ella apagada espera la respuesta manual por `aceptar` o `rechazar`. Se cambia sin reiniciar con `PUT /api/simulacion`.

## Puerto y API

Puerto **9004**. Toda la API (`/api/*`) exige la credencial interna; sin ella responde 401 `{"error":"Credencial interna requerida"}`.

| Método y ruta | Quién la llama | Qué hace |
|---|---|---|
| `GET /api/estado` | Comprobación de arranque | Responde `{"sistema":"CB-Banco","estado":"OK"}` |
| `POST /api/dispersiones` | Motor (conector HTTP de `sendBankBatch`) | Registra la dispersión; 201 `{dispersionId, estado, recibida}`. Idempotente: el mismo lote devuelve la dispersión existente; un lote distinto del mismo proceso deja la anterior `REEMPLAZADA` |
| `GET /api/dispersiones` (`?businessKey=` opcional) | Pruebas, consulta manual | Lista de dispersiones, la más reciente primero |
| `GET /api/dispersiones/{id}` | Pruebas, consulta manual | Una dispersión; 404 si no existe |
| `POST /api/dispersiones/{id}/aceptar` | Respuesta manual, pruebas | Envía `DispersionAceptada`; cuerpo opcional `{referencia}`. 409 si ya fue respondida o el proceso no la espera; 502 si el motor no responde |
| `POST /api/dispersiones/{id}/rechazar` | Respuesta manual, pruebas | Envía `DispersionRechazada`; cuerpo opcional `{codigo}`. Mismos códigos de error |
| `GET /api/simulacion` | Pruebas, consulta manual | `{respuestaAutomatica, retardoSegundos}` |
| `PUT /api/simulacion` | Pruebas, demostración | Cambia `respuestaAutomatica` o `retardoSegundos`; solo los campos enviados |

Campos de cada dispersión y detalle de los códigos: sección 7.1 del [contrato de integración](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CONTRATO-INTEGRACION.md).

## Datos

Tabla propia `banco_dispersion` (prefijo `banco_`) en la base `corocora`: `business_key` del proceso, lote, cuenta, total, periodo, estado (`RECIBIDA`, `ACEPTADA`, `RECHAZADA`, `REEMPLAZADA`, `SIN_PROCESO`), código y referencia de la respuesta, intentos y fechas. La tabla la crea JPA al arrancar (`ddl-auto: update`). La configuración de la simulación vive en memoria y vuelve a su valor inicial al reiniciar. Más detalle en el [modelo de datos](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/MODELO-DE-DATOS.md).

## Configuración

| Variable de entorno | Para qué | Valor por defecto |
|---|---|---|
| `SERVER_ADDRESS` | Interfaz en la que escucha | `127.0.0.1` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Servidor PostgreSQL y base de negocio | `localhost`, `5432`, `corocora` |
| `DB_USER`, `DB_PASSWORD` | Usuario y contraseña de PostgreSQL | `postgres` y la contraseña local definida en `application.yaml` |
| `COROCORA_INTERNAL_TOKEN` | Credencial interna: la exige en `/api/*` y la envía al motor; la misma en los seis sistemas | `cb-local-interno-demo` |
| `ENGINE_REST_URL` | REST de Camunda en el motor, a donde envía los mensajes | `http://localhost:9000/engine-rest` |
| `BANCO_RESPUESTA_AUTOMATICA` | `false` para que el banco no responda solo y espere `aceptar` o `rechazar` | `true` |

Perfil `h2` (`--spring.profiles.active=h2`): usa una base H2 en memoria (`jdbc:h2:mem:cb-banco`) que se borra al detener el sistema.

## Compilar y ejecutar

Este repositorio no compila clonado por separado: su `pom.xml` hereda del pom padre del hub (`com.corocorabenz:corocora-benz:1.0.0-SNAPSHOT`) y usa la librería `cb-comun`. Se compila desde el hub, con Java 17 o superior (Maven no hace falta: el hub trae su wrapper).

```powershell
git clone https://github.com/MiguelMorales1125/Corocora-Benz.git
cd Corocora-Benz/corocora-benz-embebida
.\mvnw.cmd -pl cb-banco -am package -DskipTests
java -jar cb-banco/target/cb-banco-1.0.0-SNAPSHOT.jar --spring.profiles.active=h2
```

En Linux o macOS se usa `./mvnw` en lugar de `.\mvnw.cmd`; lo demás es igual. Sin `--spring.profiles.active=h2` usa PostgreSQL y necesita la base `corocora` (se crea con `psql -U postgres -f crear-bases.sql`, script del hub).

- **Orden de arranque:** primero el motor (`cb-bpm-engine`, puerto 9000), que es quien envía las dispersiones y recibe las respuestas. Para que llegue una dispersión debe haber una nómina en curso, que se inicia y se atiende en CentralSys (9003).
- **Comprobar que arrancó:** `curl -H "X-CB-Internal: cb-local-interno-demo" http://localhost:9004/api/estado` debe responder `{"sistema":"CB-Banco","estado":"OK"}` (en PowerShell, `curl.exe`). Sin el encabezado responde 401.

## Pruebas

```powershell
.\mvnw.cmd -pl cb-banco -am test
```

Son 24 pruebas con H2 y el motor simulado, sin ningún otro sistema arrancado (`-am` ejecuta además las de `cb-comun`):

- `DispersionApiTest` (22): credencial interna (encabezado y `Bearer`), forma de la respuesta que espera el motor, idempotencia y reemplazo de lotes, envíos simultáneos, respuesta automática (aceptación, rechazo por cuenta inválida, reintentos mientras el proceso exista, `SIN_PROCESO` cuando ya no existe, motor caído), respuesta manual con sus 404, 409 y 502, y la API de simulación.
- `ArranqueTest` (2): la aplicación arranca con el `MotorClient` de `cb-comun` y la API exige la credencial configurada.

Los casos de prueba de la nómina completa se documentan en [CASOS-DE-PRUEBA.md](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CASOS-DE-PRUEBA.md).

## Estructura del código

Paquete base `com.corocorabenz.banco` (`src/main/java`):

- `BancoApplication`: arranque de Spring Boot, con tareas programadas activas (`@EnableScheduling`).
- `dispersion`: `DispersionController` (`/api/dispersiones`), `DispersionService` (registro, respuesta al motor, reintentos y ciclo automático), la entidad `Dispersion` con su repositorio y el enumerado `EstadoDispersion`.
- `simulacion`: `Simulacion` (respuesta automática y retardo) y `SimulacionController` (`/api/simulacion`).
- `api`: `EstadoController` (`/api/estado`).
- `src/main/resources`: `application.yaml`.
