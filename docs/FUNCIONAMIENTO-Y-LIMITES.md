# Funcionamiento y límites de la demostración

Esta aplicación es una demostración académica local de tres procesos. Camunda 7 coordina servicios Spring Boot; Banco y Bodega son simuladores. No procesa pagos reales ni debe utilizarse con información real. Los modelos incluyen el flujo de espera del mensaje de repuestos descrito más abajo.

## Ejecutar con H2

Compila con `mvnw.cmd package -DskipTests` desde `corocora-benz-embebida` y arranca los seis sistemas con `iniciar-sistemas.bat h2`, como se muestra en el [README](../README.md). Con ese perfil cada servicio usa una base H2 **en memoria** separada. Al reiniciar un servicio se pierde su base; al reiniciar solo uno, los demás pueden conservar datos que ya no coincidan. Para una demostración limpia, detén y vuelve a iniciar los seis. No equivale a almacenamiento persistente ni a recuperación ante fallos.

Las aplicaciones escuchan en `127.0.0.1` salvo que se cambie `SERVER_ADDRESS`. No usar datos reales ni exponer los servicios a una red compartida.

## Inicio de sesión, permisos y credencial interna

El [contrato de integración](CONTRATO-INTEGRACION.md) describe el detalle. En resumen:

- **Cuentas.** El motor crea al arrancar, en cualquier perfil, las cuentas y grupos de demostración (tabla en el [README](../README.md)), cada una con el correo `<cuenta>@corocorabenz.test`. La contraseña es `demo` (o la de `COROCORA_DEMO_PASSWORD`), salvo `USR-01`, cuya contraseña es `USR-01`. Una cuenta que ya existe conserva su contraseña. Con `COROCORA_DEMO_IDENTIDADES=false` no se crean, salvo el administrador `demo`.
- **Sesión en los portales.** Los tres portales piden usuario y contraseña y los validan contra el servicio de identidad de Camunda. El navegador solo habla con `/api/app/**` de su propio portal; sin sesión la API responde 401 y sin permiso 403. Las escrituras exigen el token CSRF. Tras una contraseña incorrecta Camunda bloquea la cuenta unos segundos y en ese rato rechaza también la correcta; el formulario de inicio de sesión lo avisa.
- **Permisos.** El Portal del cliente es solo para el grupo `clientes`, y cada cliente ve y responde únicamente sus solicitudes. El Portal TI y CentralSys son para empleados; cada ticket es de quien lo reportó. En CentralSys una tarea la reclama quien pertenece a uno de sus grupos candidatos y la completa quien la tiene asignada; iniciar la nómina es de `analistas-nomina` y el panel de bodega del grupo `bodega`. La cuenta `demo` (grupo `camunda-admin`) puede hacer todo lo de un empleado.
- **Credencial interna.** La REST de Camunda, la API de tickets del Portal TI y las API de Banco y Taller-Bodega solo responden a quien envía el encabezado `X-CB-Internal` con el valor de `COROCORA_INTERNAL_TOKEN`; sin él responden 401. La envían los portales, los HTTP Connector de los modelos y los simuladores, nunca el navegador.

Límites de esta protección, que no debe presentarse como seguridad de producción:

- Las contraseñas y la credencial interna por defecto son públicas (están en este repositorio) y viajan por HTTP sin cifrar.
- Las webapps de Camunda (`/camunda/**`) tienen su propio inicio de sesión, pero en la configuración del motor no se activa la autorización por recurso de Camunda: los permisos por grupo descritos arriba los aplican los portales, no el motor. Tasklist y Cockpit son herramientas de administración de la demostración, no una vía de trabajo para los empleados. En consecuencia, cualquier cuenta de demostración que inicie sesión en `/camunda` (también `cliente1` y `cliente2`) puede ver y modificar todos los procesos; para un uso real habría que activar `camunda.bpm.authorization.enabled` y dar permisos por grupo.
- No hay registro de usuarios, recuperación de contraseña ni auditoría más allá de la actividad que guarda CentralSys.
- Las sesiones viven en la memoria de cada portal: se pierden al reiniciarlo.

## Postventa y garantías

1. El cliente inicia sesión en el portal e introduce nombre, VIN, kilometraje, tipo de servicio, síntoma y, opcionalmente, correo. La solicitud queda ligada a su cuenta: cada cliente consulta solo las suyas. No se verifica la propiedad del vehículo.
2. El asesor registra el caso; se consultan los vehículos de ejemplo. La regla simplificada usa campaña y un límite de 60.000 km. Un VIN que no esté en el catálogo de demostración no se considera elegible para garantía; esto no constituye una verificación real del fabricante.
3. Si hace falta cotización, Bodega crea una por **$850.000 simulados**. No deriva del diagnóstico ni de precios reales. También hay cotización cuando la regla concedió garantía o campaña pero el analista no la aprobó: el proceso conserva el resultado de la regla, y el portal del cliente lo muestra como «garantía no aprobada; requiere cotización». El cliente responde desde el portal; los estados aceptada, rechazada o vencida se concilian a partir del historial del motor cuando se consultan las cotizaciones.
4. Una aceptación continúa a programación, taller y entrega. El portal muestra la etapa disponible, la cita, la orden, el trabajo registrado y el resultado cuando esos datos existen. La cita es una fecha y hora de texto, no una reserva real de agenda. La reserva de repuestos no consulta inventario ni cantidades: Bodega simula si puede atenderla de inmediato. Si responde `PENDIENTE`, el proceso espera el mensaje `RepuestosDisponibles` antes de continuar al taller. Las entregas del subproceso de taller también son simuladas; un repuesto adicional sin código se registra como `SIN-CODIGO`.
5. La satisfacción se limita a 1–5 en el propio formulario de entrega (`generated-deliver-vehicle.form`), así que la restricción aplica tanto en CentralSys, que además la valida en el servidor, como en Tasklist.

Postventa también se puede iniciar desde Tasklist con el formulario `postventa-solicitud` (mismos datos que el portal, con valores de ejemplo y el mismo máximo de 255 caracteres para el cliente y el síntoma; ese límite lo aplica Tasklist, no una llamada REST directa). Esa instancia no pertenece a ninguna cuenta de cliente: no aparece en el Portal del cliente y, si llegara a cotización, solo se podría responder con un mensaje por la REST del motor. Con los valores por defecto la garantía cubre el caso y no hay cotización.

No hay pago, factura, inventario, valoración real de la reparación ni comparación automática entre el importe cotizado y el costo final. El estado visible procede del historial de Camunda, no de un sistema de taller externo.

### Simular espera de repuestos

No hay catálogo ni control de existencias. Para probar el mensaje de Bodega se usa la API de Taller-Bodega (`http://localhost:9005`, con el encabezado `X-CB-Internal`) o las mismas acciones desde el panel de bodega de CentralSys, entrando como `bodega1` o `demo`:

1. Envía `PUT /api/simulacion` con `{"repuestosDisponibles":false}`.
2. Inicia una solicitud de postventa y llévala hasta la reserva. Bodega devuelve `PENDIENTE`; la cita puede agendarse en paralelo, pero el taller no comienza hasta recibir el mensaje.
3. Consulta `GET /api/reservas`, identifica la reserva por su `businessKey` y envía `POST /api/reservas/{id}/disponibles`. Bodega correlaciona `RepuestosDisponibles` con el motor. Si el proceso aún no espera el mensaje, el endpoint responde 409 y se puede reintentar. Repetir el aviso de una reserva ya atendida no envía otro mensaje.
4. Restaura `{"repuestosDisponibles":true}` con el mismo `PUT`. El cambio afecta solo al simulador en ejecución y se pierde al reiniciarlo.

Si Banco o Bodega van a avisar al motor y el proceso con esa `businessKey` ya no existe, el registro queda en estado `SIN_PROCESO` y no se reintenta más. Mientras el motor no responda, el aviso se reintenta en el siguiente ciclo.

## Incidentes TI

El empleado inicia sesión en el Portal TI e introduce sistema, descripción, impacto, urgencia, equipos afectados (un número entero, también en el formulario de inicio de Tasklist) y correo opcional; el incidente queda a nombre de su cuenta. La tabla de decisión calcula la prioridad; Mesa de Servicio asigna Nivel 1 o especializado. Soporte registra causa y solución; el usuario confirma o rechaza en el portal. Un rechazo devuelve el caso a soporte y el ticket vuelve a `OPEN` al consultarlo; en esa nueva vuelta el formulario de diagnóstico muestra, en solo lectura, la causa y la solución del intento anterior. Si no responde en 24 horas, el proceso puede cerrarlo.

El plazo de resolución comienza al entrar en la actividad de resolución, **después del triaje**, y se vuelve a iniciar en una nueva vuelta de resolución. En CentralSys, al completar la revisión de SLA con un nuevo responsable, se intenta asignar a esa cuenta la siguiente tarea de soporte, siempre que la cuenta exista y no sea de un cliente. Si no se puede, se informa que la revisión sí terminó pero la asignación falló; un operador debe revisar la bandeja. Esta reasignación es una función de CentralSys, no del BPMN ni de Tasklist o de llamadas REST directas, y no comprueba que la cuenta pertenezca al grupo de soporte.

## Nómina y Banco

La recolección usa novedades fijas `EMP-001` y `EMP-002`, con una tercera opcional en los escenarios de prueba. Las excepciones se simulan con tres variables booleanas que el proceso lee al recolectar las novedades: `simulatePeriodClosed` (periodo cerrado) y `simulateMissingSupports` (soportes incompletos) abren «Corregir novedades o reabrir periodo» para `talento1`, y `simulateLateChanges` (novedades fuera de corte) agrega la novedad `EMP-003` y abre «Aprobar novedades fuera de corte» para `jefatura1`. Se marcan con las casillas «Simular una excepción (demostración)» al iniciar la nómina en CentralSys, o con las mismas casillas del formulario `nomina-periodo` en Tasklist; sin marcar, el ciclo sigue la ruta normal. Cuando Talento Humano registra su corrección, la simulación deja de aplicarse para no repetir el ciclo. La fecha de corte introducida no se contrasta con registros reales. La liquidación es siempre **$1.000.000 simulados**, sin salarios, deducciones ni reglas laborales. Las correcciones quedan en las instancias hijas del proceso; no actualizan una fuente real de novedades ni afectan el cálculo fijo. «Adjuntar soporte» es una casilla, no una carga de archivos.

Banco simula la respuesta: acepta cuentas de origen con formato `000-123` y rechaza otros formatos. En la tarea de corrección del lote, el formulario (`generated-fix-bank-batch.form`) incluye el campo obligatorio «Cuenta de origen corregida», tanto en CentralSys como en Tasklist. Si el Banco no responde en 2 horas, la misma tarea se abre con el código de respuesta `SIN-RESPUESTA-2H` en lugar de un código de rechazo. El rechazo, la aceptación y el reintento son simulados, no existe transferencia bancaria. El registro de pago y los comprobantes se representan con variables y logs, no con documentos o pagos reales. Evitar tratar una instancia terminada como prueba de un pago.

CentralSys muestra los formularios tal como están desplegados, con sus valores por defecto; los valores que ya existen en el proceso tienen prioridad. Al completar, el servidor solo acepta las variables de los campos editables del formulario y fija su tipo según el campo, de modo que una persona no puede cambiar desde el navegador un dato de solo lectura. Como los valores por defecto son de ejemplo, el empleado debe revisarlos antes de completar. Los campos de solo lectura que muestran el motivo de una devolución (el de Jefatura al revisar la liquidación, el resultado de la última inspección al reparar el vehículo, y la causa y la solución rechazadas al diagnosticar el incidente) no tienen valor de ejemplo: aparecen vacíos hasta que exista una devolución.

## Correo y notificaciones

Por defecto `CORREO_HABILITADO=false`: el motor escribe los mensajes en el log, no los envía. `notificationSent` solo es verdadero cuando el servicio informa que envió por SMTP; `notificationMode` distingue `EMAIL`, `LOG` y `ERROR`. Para probar envío, configura un servidor SMTP de pruebas como Mailpit, `CORREO_HABILITADO=true`, `MAIL_HOST` y `MAIL_PORT`. Los correos opcionales suministrados en los portales se usan en las notificaciones que el proceso ya emite; si no existe destinatario se registra en log. No existe una notificación adicional cuando se crea una cotización: el cliente debe consultar el portal. No se garantiza entrega, lectura ni reintento del correo. El temporizador del proceso puede cerrar o vencer el caso aunque el correo no haya llegado.

## Verificación

Las pruebas de cada módulo se ejecutan con `mvnw.cmd test` desde `corocora-benz-embebida` y no necesitan ningún sistema arrancado.

Con los seis sistemas arrancados, desde `corocora-benz-embebida/tests`:

```powershell
npm run todo
```

Ejecuta, en este orden, `e2e` (12 escenarios de los tres procesos por la REST de Camunda), `identidades` (cuentas, grupos y motor protegido), `regresion` (validaciones, reintentos idempotentes y API protegidas), `permisos` (sesión, permisos por grupo y aislamiento entre clientes) y `portales` (los tres procesos de punta a punta por las API de los portales, con la sesión de cada persona). Se detiene en la primera que falle. Cada una se puede ejecutar por separado (`npm run e2e`, etc.); la tabla del [README](../README.md) indica qué sistemas necesita.

`e2e` incluye el caso en que el taller espera un mensaje de Bodega, fuerza los temporizadores largos y responde manualmente por el banco y la bodega; no espera 24/48 horas reales ni demuestra operaciones financieras. Incluye el reenvío de un lote con cuenta inválida corregida. La simulación original de Banco y Bodega se restaura al terminar.

Para verificar visualmente: en CentralSys entra como `tesoreria1` para corregir la cuenta de un lote rechazado; `responsable1` puede introducir un nuevo responsable tras un SLA vencido (la sección siguiente explica cómo vencerlo); `bodega1` ve el panel de bodega. En el portal del cliente entra como `cliente1` o `cliente2` para registrar una solicitud y seguir su estado.

### Vencer un temporizador sin esperar

Los plazos de los procesos son reales: el de resolución de TI (2, 8 o 24 horas según la prioridad), las 24 horas para confirmar la solución, las 48 horas de vigencia de la cotización y las 2 horas de espera al Banco. Ningún portal permite adelantarlos, y Swagger tampoco. Para mostrarlos en una demostración se ejecuta el job del temporizador por la REST del motor, con el encabezado `X-CB-Internal` (es lo mismo que hace `e2e`):

1. `GET http://localhost:9000/engine-rest/process-instance?businessKey={businessKey}` devuelve las instancias del caso. Los cuatro temporizadores pertenecen a la instancia raíz, la de `CB_GestionIncidenteTI`, `CB_PostventaGarantias` o `CB_GestionNomina` (se reconoce por su `definitionId`), no a las de los subprocesos.
2. `GET http://localhost:9000/engine-rest/job?processInstanceId={id de la instancia raíz}&timers=true` lista sus temporizadores pendientes.
3. `POST http://localhost:9000/engine-rest/job/{jobId}/execute` vence el temporizador en ese momento.

Al vencer el plazo de resolución (`slaTimer`), la tarea «Revisar incidente con plazo vencido» aparece en la bandeja de `responsable1`. Los otros temporizadores son `noResponse` (24 horas sin confirmación del usuario), `quoteExpired` (48 horas de la cotización) y `bankTimeout` (2 horas del Banco, que abre la corrección del lote con `SIN-RESPUESTA-2H`).

Como alternativa, Cockpit (`demo` / `demo`) permite cambiar la fecha de vencimiento de un temporizador a la hora actual: en la vista de la instancia, pestaña «Jobs», botón «Change due date for this Job», opción «Set a specific date». Esta vía se comprobó el 19 de noviembre de 2026 con `slaTimer`; el motor ejecuta el temporizador por su cuenta en cerca de un minuto. El paso a paso y una orden de PowerShell con las tres llamadas están en la sección 5 de `docs/GUION-DEMOSTRACION.md`.

## Fuera del alcance de esta demostración

Seguridad de producción (HTTPS, gestión de contraseñas y secretos, autorización dentro del propio motor, auditoría), persistencia duradera de las sesiones, datos reales de RR. HH., liquidación laboral, comprobantes reales, banco real, inventario y agenda, y notificaciones garantizadas requieren implementar e integrar sistemas y reglas de negocio que este repositorio no proporciona. No se han sustituido por datos o reglas inventados.
