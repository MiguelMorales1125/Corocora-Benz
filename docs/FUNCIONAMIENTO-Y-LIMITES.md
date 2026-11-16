# Funcionamiento y límites de la demostración

Esta aplicación es una demostración académica local de tres procesos. Camunda 7 coordina servicios Spring Boot; Banco y Bodega son simuladores. No procesa pagos reales ni debe utilizarse con información real. Los modelos incluyen el flujo de espera del mensaje de repuestos descrito más abajo.

## Ejecutar con H2

Compila con `mvn package` desde `corocora-benz-embebida` y arranca los seis módulos con `--spring.profiles.active=h2`, como se muestra en el [README](../README.md). H2 sigue siendo la base de datos: cada servicio usa una base **en memoria** separada. Al reiniciar un servicio se pierde su base; al reiniciar solo uno, los demás pueden conservar datos que ya no coincidan. Para una demostración limpia, detén y vuelve a iniciar los seis. No equivale a almacenamiento persistente ni a recuperación ante fallos.

Las aplicaciones escuchan en `127.0.0.1` salvo que se cambie `SERVER_ADDRESS`. La selección de usuario o grupo en los portales **no autentica ni autoriza** a las personas, y Camunda REST está disponible localmente. Limitar la interfaz de red no sustituye autenticación y autorización. No usar datos reales ni exponer los servicios a una red compartida.

## Identidades de ejemplo en H2 — implementación parcial

Con el perfil `h2`, el motor crea cuentas para Tasklist. La contraseña inicial de estas cuentas es `demo` y puede cambiarse para la próxima inicialización mediante `COROCORA_DEMO_PASSWORD`. Como H2 está en memoria, se vuelven a crear al reiniciar el motor. La cuenta existente `USR-01` conserva su configuración anterior.

| Cuenta | Grupo |
|---|---|
| `mesa1` | `mesa-servicio` |
| `soporte1` / `especialista1` | `soporte-ti-n1` / `soporte-ti-especializado` |
| `responsable1` | `responsables-ti` |
| `nomina1` / `talento1` / `jefatura1` / `tesoreria1` | `analistas-nomina` / `gestion-talento-humano` / `jefatura-nomina` / `tesoreria` |
| `asesor1` / `garantia1` / `coordinador1` | `asesores-servicio` / `analistas-garantia` / `coordinadores-postventa` |
| `tecnico1` / `calidad1` | `tecnicos-taller` / `inspectores-calidad` |

También se crean `cliente1` y `cliente2`, pero todavía no están vinculados al PortalCliente ni a la propiedad de sus solicitudes. Las cuentas son identidades de ejemplo y no protegen las API.

**Pendiente:** las cuentas y grupos no activan por sí solos la autorización del motor. La API REST de Camunda, los portales y las API de negocio aún permiten operaciones sin estas credenciales; CentralSys sigue aceptando un usuario y grupo escritos en pantalla. No hay aislamiento de solicitudes por cliente. No presentar esto como un sistema de seguridad ni utilizar datos reales. Completarlo requiere proteger las API, adaptar las comunicaciones entre servicios y los portales, y comprobar las autorizaciones por tarea y por instancia.

## Postventa y garantías

1. El cliente introduce nombre, VIN, kilometraje, tipo de servicio, síntoma y, opcionalmente, correo. Para consultar debe escribir exactamente el mismo nombre. No se verifica propiedad del vehículo ni identidad, y nombres iguales pueden mezclar solicitudes.
2. El asesor registra el caso; se consultan los vehículos de ejemplo. La regla simplificada usa campaña y un límite de 60.000 km. Un VIN que no esté en el catálogo de demostración no se considera elegible para garantía; esto no constituye una verificación real del fabricante.
3. Si hace falta cotización, Bodega crea una por **$850.000 simulados**. No deriva del diagnóstico ni de precios reales. El cliente responde desde el portal; los estados aceptada, rechazada o vencida se concilian a partir del historial del motor cuando se consultan las cotizaciones.
4. Una aceptación continúa a programación, taller y entrega. El portal muestra la etapa disponible, la cita, la orden, el trabajo registrado y el resultado cuando esos datos existen. La cita es una fecha y hora de texto, no una reserva real de agenda. La reserva de repuestos no consulta inventario ni cantidades: Bodega simula si puede atenderla de inmediato. Si responde `PENDIENTE`, el proceso espera el mensaje `RepuestosDisponibles` antes de continuar al taller. Las entregas del subproceso de taller también son simuladas; un repuesto adicional sin código se registra como `SIN-CODIGO`.
5. La satisfacción se limita a 1–5 en el formulario abierto desde **CentralSys**. El archivo `.form` original no se cambió; si se usa Tasklist directamente conserva sus restricciones anteriores.

No hay pago, factura, inventario, valoración real de la reparación ni comparación automática entre el importe cotizado y el costo final. El estado visible procede del historial de Camunda, no de un sistema de taller externo.

### Simular espera de repuestos

No hay catálogo ni control de existencias. Para probar el mensaje de Bodega:

1. Envía `PUT http://localhost:9005/api/simulacion` con `{"repuestosDisponibles":false}`.
2. Inicia una solicitud de postventa y llévala hasta la reserva. Bodega devuelve `PENDIENTE`; la cita puede agendarse en paralelo, pero el taller no comienza hasta recibir el mensaje.
3. Consulta `GET http://localhost:9005/api/reservas`, identifica la reserva por su `businessKey` y envía `POST http://localhost:9005/api/reservas/{id}/disponibles`. Bodega correlaciona `RepuestosDisponibles` con el motor. Si el proceso aún no espera el mensaje, el endpoint responde 409 y se puede reintentar. Repetir el aviso de una reserva ya atendida no envía otro mensaje.
4. Restaura `{"repuestosDisponibles":true}` con el mismo `PUT`. El cambio afecta solo al simulador en ejecución y se pierde al reiniciar H2/servicios.

## Incidentes TI

El empleado introduce su identificador, sistema, descripción, impacto, urgencia, equipos afectados y correo opcional. La tabla de decisión calcula la prioridad; Mesa de Servicio asigna Nivel 1 o especializado. Soporte registra causa y solución; el usuario confirma o rechaza en el portal. Un rechazo devuelve el caso a soporte y el ticket vuelve a `OPEN` al consultarlo. Si no responde en 24 horas, el proceso puede cerrarlo.

El plazo de resolución comienza al entrar en la actividad de resolución, **después del triaje**, y se vuelve a iniciar en una nueva vuelta de resolución. En CentralSys, al completar la revisión de SLA con un nuevo responsable, se intenta asignar a ese identificador la siguiente tarea de soporte. Si no se puede, se informa que la revisión sí terminó pero la asignación falló; un operador debe revisar la bandeja. Esta reasignación es una función de CentralSys, no del BPMN ni de Tasklist o de llamadas REST directas, y no verifica que el usuario exista o tenga permisos.

## Nómina y Banco

La recolección usa novedades fijas `EMP-001` y `EMP-002`, con una tercera opcional en los escenarios de prueba. Las excepciones de periodo y soportes se simulan con variables; la fecha de corte introducida no se contrasta con registros reales. La liquidación es siempre **$1.000.000 simulados**, sin salarios, deducciones ni reglas laborales. Las correcciones quedan en las instancias hijas del proceso; no actualizan una fuente real de novedades ni afectan el cálculo fijo. «Adjuntar soporte» es una casilla, no una carga de archivos.

Banco simula la respuesta: acepta cuentas de origen con formato `000-123` y rechaza otros formatos. En la tarea de corrección abierta en **CentralSys** se muestra la cuenta anterior y se puede enviar una nueva. Esta mejora se aplica en CentralSys sin cambiar el `.form` original; Tasklist directo no incorpora ese campo. El rechazo, la aceptación y el reintento son simulados, no existe transferencia bancaria. El registro de pago y los comprobantes se representan con variables y logs, no con documentos o pagos reales. Evitar tratar una instancia terminada como prueba de un pago.

CentralSys no precarga valores de ejemplo en campos editables; conserva los valores que ya existen en el proceso. Las casillas sin valor previo empiezan desmarcadas, lo que representa la opción negativa en el proceso: el empleado debe revisar expresamente su decisión antes de completar. Los campos de solo lectura pueden seguir mostrando valores de ejemplo si la variable no existe. Tasklist directo conserva los valores originales del formulario.

## Correo y notificaciones

Por defecto `CORREO_HABILITADO=false`: el motor escribe los mensajes en el log, no los envía. `notificationSent` solo es verdadero cuando el servicio informa que envió por SMTP; `notificationMode` distingue `EMAIL`, `LOG` y `ERROR`. Para probar envío, configura un servidor SMTP de pruebas como Mailpit, `CORREO_HABILITADO=true`, `MAIL_HOST` y `MAIL_PORT`. Los correos opcionales suministrados en los portales se usan en las notificaciones que el proceso ya emite; si no existe destinatario se registra en log. No existe una notificación adicional cuando se crea una cotización: el cliente debe consultar el portal. No se garantiza entrega, lectura ni reintento del correo. El temporizador del proceso puede cerrar o vencer el caso aunque el correo no haya llegado.

## Verificación

Con Motor, Portal TI, Banco y Taller-Bodega en H2, ejecutar desde la raíz del repositorio:

```powershell
node corocora-benz-embebida/tests/e2e.mjs
```

La prueba principal pasó 12 escenarios y 191 verificaciones en H2. Las pruebas `tests/permisos.mjs`, `tests/identidades.mjs` y `tests/regresion.mjs` incluyen expectativas de protección de API que todavía no están implementadas y actualmente fallan; no deben presentarse como verificaciones aprobadas.

La prueba principal incluye el caso en que el taller espera un mensaje de Bodega y fuerza los temporizadores largos y responde manualmente el banco y la bodega; no espera 24/48 horas reales ni demuestra operaciones financieras. Incluye el reenvío de un lote con cuenta inválida corregida. La simulación original de Banco y Bodega se restaura al terminar el bloque de prueba.

Para verificar visualmente: en CentralSys selecciona `tesoreria` para corregir la cuenta de un lote rechazado; `responsables-ti` permite introducir un nuevo responsable tras un SLA vencido. En el portal cliente consulta `Cliente de prueba` después de la prueba E2E para observar ejemplos de entrega, rechazo y vencimiento mientras los servicios H2 sigan encendidos.

## Fuera del alcance de esta demostración

Autenticación e identidad, permisos por rol, persistencia duradera, datos reales de RR. HH., liquidación laboral, comprobantes reales, banco real, inventario y agenda, y notificaciones garantizadas requieren implementar e integrar sistemas y reglas de negocio que este repositorio no proporciona. No se han sustituido por datos o reglas inventados.
