# Descripción de los procesos de Corocora Benz

Corocora Benz automatiza tres procesos de negocio: la **Gestión de Incidentes de TI**, la **Gestión de Nómina** y la **Postventa y Garantías**. Cada uno es un proceso principal que delega una parte de su trabajo en un subproceso reutilizable, invocado mediante una actividad de llamada (call activity), de modo que hay seis modelos BPMN en total. Los acompañan tres tablas de decisión DMN (una por proceso) y veintidós formularios de Camunda para los eventos de inicio y las tareas de usuario.

Los archivos editables están en la raíz del repositorio: los modelos de proceso en `bpmn/principales/` y `bpmn/reutilizables/`, las tablas de decisión en `dmn/` y los formularios en `form/`. El motor despliega una copia idéntica de esos mismos archivos, ubicada en `corocora-benz-embebida/cb-bpm-engine/src/main/resources/static.bpmn`, `static.dmn` y `static.form`. Todos se abren y se editan con Camunda Modeler; los modelos están configurados para Camunda Platform 7.24. Los diagramas ya dibujados se pueden consultar en `docs/diagramas_bpmn_corocora_benz.pdf`; este documento los describe por escrito.

## 1. Las seis aplicaciones

La solución está formada por seis aplicaciones. Se nombran aquí porque el resto del documento indica, para cada carril de cada proceso, qué aplicación lo atiende.

| Aplicación | Módulo y puerto | Papel |
|---|---|---|
| Motor | `cb-bpm-engine`, 9000 | Camunda 7 embebido en Spring Boot. Ejecuta los seis modelos, evalúa las tablas DMN, controla los temporizadores y ejecuta las tareas automáticas (clases Java llamadas *delegates* y conectores HTTP). |
| PortalCliente | `cb-portal-cliente`, 9001 | Sitio web del cliente externo. Participa en Postventa. |
| PortalTI | `cb-portal-ti`, 9002 | Sitio web del empleado que reporta un incidente y confirma la solución. Además ofrece una API de tickets que el motor invoca. |
| CentralSys | `cb-central-sys`, 9003 | Sitio web de los empleados: una bandeja de tareas por grupo, donde cada tarea se completa con su formulario de Camunda; el inicio de la nómina, y un panel de bodega. |
| Banco | `cb-banco`, 9004 | Servicio REST sin pantallas. Recibe la dispersión de nómina y responde con un mensaje. |
| Taller-Bodega | `cb-taller-bodega`, 9005 | Servicio REST sin pantallas. Consulta de vehículos por VIN, cotizaciones, reservas y solicitudes de repuesto; responde con mensajes. |

## 2. Resumen de los modelos

| Archivo | Id del proceso | Tipo | Quién lo inicia y cómo |
|---|---|---|---|
| `bpmn/principales/CB_GestionIncidenteTI.bpmn` | `CB_GestionIncidenteTI` | Principal | El empleado que sufre el incidente, desde PortalTI. El evento de inicio «Incidente de TI reportado» usa el formulario `ti-reporte`. |
| `bpmn/reutilizables/CB_ResolverIncidenteTI.bpmn` | `CB_ResolverIncidenteTI` | Reutilizable | La call activity «Resolver incidente técnico» del proceso de TI. |
| `bpmn/principales/CB_GestionNomina.bpmn` | `CB_GestionNomina` | Principal | Un analista de nómina, desde CentralSys. El evento de inicio «Ciclo de pago iniciado» usa el formulario `nomina-periodo`. |
| `bpmn/reutilizables/CB_ValidarNovedadEmpleado.bpmn` | `CB_ValidarNovedadEmpleado` | Reutilizable | La call activity multiinstancia «Validar cada novedad de empleado» del proceso de Nómina, una vez por cada novedad. |
| `bpmn/principales/CB_PostventaGarantias.bpmn` | `CB_PostventaGarantias` | Principal | El cliente, desde PortalCliente. El evento de inicio «Solicitud de servicio recibida» es un evento de mensaje (`SolicitudServicio`) con el formulario `postventa-solicitud`. |
| `bpmn/reutilizables/CB_AtenderOrdenTaller.bpmn` | `CB_AtenderOrdenTaller` | Reutilizable | La call activity «Atender orden de taller» del proceso de Postventa. |

Los tres subprocesos reutilizables están marcados como no iniciables desde Tasklist (`camunda:isStartableInTasklist="false"`): solo se ejecutan cuando su proceso principal los llama. Cada call activity entrega a su subproceso la misma clave de negocio (*businessKey*) del proceso principal, de manera que todo el caso se identifica con una sola clave.

Cada caso lleva una clave de negocio con el prefijo de su proceso: `TI-` para incidentes, `NOM-` para nómina y `PV-` para postventa. La genera la aplicación que inicia el proceso. Si una instancia se inicia sin clave (por ejemplo, desde Camunda Tasklist, que ofrece los tres procesos principales con su formulario de inicio), el motor le asigna una automáticamente con el mismo prefijo y ocho caracteres. La clave es indispensable porque el Banco, Taller-Bodega y los portales la usan para dirigir sus mensajes a la instancia correcta.

## 3. Gestión de Incidentes de TI

Modelos: `CB_GestionIncidenteTI` (principal) y `CB_ResolverIncidenteTI` (reutilizable). Tabla de decisión: `CB_ClasificarIncidenteTI`.

### 3.1 Propósito

Un empleado reporta una falla en un sistema de la empresa. El proceso registra el ticket, calcula con una tabla de decisión la prioridad y el plazo de resolución, lo asigna al nivel de soporte adecuado y vigila ese plazo. El caso solo se cierra cuando el propio empleado confirma que la solución funcionó, o cuando pasan 24 horas sin que responda.

### 3.2 Participantes

Ambos modelos tienen un único pool, «Corocora Benz». No hay participantes externos.

| Modelo | Carril | Aplicación que lo atiende | Grupo de candidatos o usuario |
|---|---|---|---|
| Principal | Usuario Interno Solicitante | PortalTI | El empleado que reportó: la tarea se asigna con `camunda:assignee="${requesterId}"`. |
| Principal | Mesa de Servicio | CentralSys para la tarea de usuario. Las tareas automáticas del carril las ejecuta el Motor, que llama a la API de tickets de PortalTI. | `mesa-servicio` |
| Principal | Responsable del Servicio TI | CentralSys | `responsables-ti` |
| Reutilizable | Soporte TI Nivel 1 | CentralSys. La tarea automática del carril la ejecuta el Motor contra PortalTI. | `soporte-ti-n1` |
| Reutilizable | Soporte Especializado | CentralSys | `soporte-ti-especializado` |

### 3.3 Inicio

El evento de inicio «Incidente de TI reportado» está en el carril del Usuario Interno Solicitante y tiene asociado el formulario `ti-reporte` (usuario que reporta, sistema afectado, descripción, impacto, urgencia, si afecta la seguridad y cantidad de equipos afectados). El empleado llena esos datos en PortalTI; el backend del portal inicia el proceso por la API REST del motor, pone como `requesterId` al usuario de la sesión y asigna una clave de negocio con el formato `TI-XXXXXXXX`.

### 3.4 Flujo principal

1. **«Registrar ticket del incidente»** (tarea de servicio con conector HTTP). El motor envía `POST /api/tickets` a PortalTI con los datos del reporte y guarda el número del ticket (`ticketId`) y su estado (`ticketStatus`).
2. **«Clasificar prioridad y plazo»** (tarea de regla de negocio). Evalúa la tabla DMN `CB_ClasificarIncidenteTI` y deja dos resultados: la prioridad (`priority`) y el plazo de resolución (`targetDuration`).
3. **«Validar clasificación y asignar grupo»** (tarea de usuario, grupo `mesa-servicio`, formulario `ti-triaje`). La Mesa de Servicio ve el ticket, la prioridad y el plazo calculados, y elige el grupo de atención (`technicalDomain`): «Soporte TI Nivel 1» (`catalogoNivel1`) o «Soporte especializado» (`especializado`).
4. **«Resolver incidente técnico»** (call activity que invoca `CB_ResolverIncidenteTI`). Le pasa el ticket, el grupo de atención, la prioridad y la descripción, y recibe de vuelta la causa raíz (`rootCause`) y la solución (`resolutionSummary`). Dentro del subproceso:
   1. Evento de inicio «Incidente asignado».
   2. Compuerta exclusiva «¿Dentro del alcance de Nivel 1?».
   3. Según el grupo elegido, **«Diagnosticar y resolver»** (tarea de usuario, grupo `soporte-ti-n1`) o **«Analizar causa raíz y resolver»** (tarea de usuario, grupo `soporte-ti-especializado`). Las dos usan el formulario `ti-diagnostico`, donde el técnico escribe la causa raíz y la solución aplicada.
   4. **«Registrar solución en la base de conocimiento»** (tarea de servicio con conector HTTP). El motor envía `POST /api/tickets/{ticketId}/solucion` a PortalTI y guarda `knowledgeArticleId`.
   5. Evento de fin «Solución registrada».
5. **«Solución notificada al usuario»** (evento intermedio de envío de mensaje, `SolucionNotificada`). Lo implementa el delegate `enviarNotificacionDelegate`, que envía al solicitante un correo con la solución y le pide confirmarla.
6. **«Confirmar solución del incidente»** (tarea de usuario asignada al solicitante, formulario `generated-confirm-solution`). El empleado responde en PortalTI si la solución resolvió el incidente (`userConfirmed`).
7. Compuerta exclusiva **«¿Solución confirmada?»**. Si la respuesta es afirmativa, el flujo sigue.
8. **«Cerrar ticket verificado»** (tarea de servicio con conector HTTP). El motor envía `POST /api/tickets/{ticketId}/cerrar` a PortalTI.
9. Evento de fin **«Incidente resuelto y cerrado»**.

### 3.5 Compuertas

| Compuerta | Tipo | Salida y condición |
|---|---|---|
| «¿Dentro del alcance de Nivel 1?» (subproceso) | Exclusiva | «Sí», `${technicalDomain == 'catalogoNivel1'}`: va a «Diagnosticar y resolver». «No», `${technicalDomain != 'catalogoNivel1'}`: va a «Analizar causa raíz y resolver». |
| «¿Solución confirmada?» | Exclusiva | «Sí», `${userConfirmed == true}`: va a «Cerrar ticket verificado». «No, reabrir», `${userConfirmed == false}`: regresa a «Resolver incidente técnico». |

Las compuertas exclusivas sin nombre que aparecen antes de «Resolver incidente técnico» y después de las dos tareas de soporte solo unen caminos; no evalúan condiciones.

### 3.6 Eventos

| Evento | Tipo | Detalle |
|---|---|---|
| «Incidente de TI reportado» | Inicio | Con formulario `ti-reporte`. Lo dispara PortalTI. |
| «Plazo de resolución vencido» | Temporizador de borde, interrumpe, sobre la call activity «Resolver incidente técnico» | La duración no es fija: es la expresión `${targetDuration}`, el plazo que calculó la tabla DMN (`PT2H`, `PT8H` o `PT24H`, es decir 2, 8 o 24 horas). |
| «Solución notificada al usuario» | Intermedio de envío de mensaje | Mensaje `SolucionNotificada`. Lo emite el propio proceso mediante el delegate de notificaciones del motor. |
| «Sin respuesta en 24 h» | Temporizador de borde, interrumpe, sobre la tarea «Confirmar solución del incidente» | Duración `PT24H`. |
| «Incidente resuelto y cerrado» | Fin | Cierre normal, con la solución confirmada. |
| «Ticket cerrado por falta de respuesta» | Fin | Cierre automático por silencio del usuario. |
| «Incidente asignado» y «Solución registrada» | Inicio y fin del subproceso | Sin definición adicional. |

### 3.7 Caminos alternos y de excepción

- **Plazo de resolución vencido.** Si el subproceso de resolución no termina dentro del plazo, el temporizador de borde lo interrumpe y abre **«Revisar incidente con plazo vencido»** (tarea de usuario, grupo `responsables-ti`, formulario `generated-review-sla`), donde el responsable escribe un plan de atención (`recoveryPlan`) y, si quiere, un nuevo responsable (`newOwner`). Al completarla, el flujo regresa a «Resolver incidente técnico» y el plazo vuelve a contarse. Si se indicó un nuevo responsable, CentralSys le asigna la siguiente tarea de soporte del caso.
- **El usuario rechaza la solución.** La salida «No, reabrir» devuelve el caso a «Resolver incidente técnico». En esa nueva vuelta, la call activity entrega al subproceso la causa y la solución del intento anterior con los nombres `previousRootCause` y `previousResolution`, y el formulario de diagnóstico las muestra en solo lectura.
- **El usuario no responde.** A las 24 horas, el temporizador de borde cancela la tarea de confirmación. El motor ejecuta **«Notificar cierre por falta de respuesta»** (tarea de servicio con el delegate `enviarNotificacionDelegate`) y **«Cerrar ticket por falta de respuesta»** (tarea de servicio con conector HTTP, `POST /api/tickets/{ticketId}/cerrar` en PortalTI), y termina en «Ticket cerrado por falta de respuesta».

### 3.8 Variables principales

| Variable | Quién la escribe | Para qué se usa |
|---|---|---|
| `requesterId` | PortalTI al iniciar (usuario de la sesión) | Asigna la tarea «Confirmar solución del incidente» y determina el destinatario de los correos. |
| `systemName`, `description` | Formulario de inicio | Datos del incidente; se envían al crear el ticket. |
| `impact`, `urgency`, `safetyImpact`, `affectedTeams` | Formulario de inicio | Entradas de la tabla DMN. |
| `notificationEmail` (opcional) | PortalTI al iniciar | Correo al que se envían las notificaciones. |
| `ticketId`, `ticketStatus` | Conector de «Registrar ticket del incidente»; `ticketStatus` también lo actualizan los cierres | Identifica el ticket en las llamadas siguientes a PortalTI. |
| `priority`, `targetDuration` | Tarea de regla de negocio | La prioridad se muestra a Mesa de Servicio y a soporte; el plazo es la duración del temporizador de borde. |
| `technicalDomain` | Tarea «Validar clasificación y asignar grupo» | Condición de la compuerta «¿Dentro del alcance de Nivel 1?». |
| `rootCause`, `resolutionSummary` | Tareas de soporte del subproceso; la call activity las devuelve al proceso principal | Se registran en el ticket y se incluyen en la notificación al usuario. |
| `knowledgeArticleId` | Conector de «Registrar solución en la base de conocimiento» (queda en el subproceso) | Referencia del registro de la solución. |
| `recoveryPlan`, `newOwner` | Tarea «Revisar incidente con plazo vencido» | Plan de atención y reasignación tras el vencimiento. |
| `userConfirmed` | Tarea «Confirmar solución del incidente» | Condición de la compuerta «¿Solución confirmada?». |
| `notificationSent`, `notificationMode` | Delegate de notificaciones | Indican si la notificación se envió por correo. |

### 3.9 Tabla de decisión `CB_ClasificarIncidenteTI`

Nombre: «Clasificar prioridad y plazo del incidente». Política de acierto: **FIRST** (se aplica la primera regla que coincide, por eso el orden importa: primero la seguridad, luego la combinación de impacto y urgencia altos, después los criterios de prioridad media).

Entradas: ¿afecta la seguridad? (`safetyImpact`, booleano), nivel de impacto (`impact`: Bajo, Medio, Alto), nivel de urgencia (`urgency`: Baja, Media, Alta) y cantidad de equipos afectados (`affectedTeams`, entero). Salidas: prioridad de atención (`priority`) y tiempo objetivo de resolución (`targetDuration`).

| Regla | ¿Afecta la seguridad? | Impacto | Urgencia | Equipos afectados | Prioridad | Plazo |
|---|---|---|---|---|---|---|
| 1 | Sí | cualquiera | cualquiera | cualquiera | P1 | `PT2H` |
| 2 | No | Alto | Alta | cualquiera | P1 | `PT2H` |
| 3 | No | Alto | cualquiera | cualquiera | P2 | `PT8H` |
| 4 | No | cualquiera | Alta | cualquiera | P2 | `PT8H` |
| 5 | No | cualquiera | cualquiera | más de 1 | P2 | `PT8H` |
| 6 | No | cualquiera | cualquiera | cualquiera | P3 | `PT24H` |

## 4. Gestión de Nómina

Modelos: `CB_GestionNomina` (principal) y `CB_ValidarNovedadEmpleado` (reutilizable). Tabla de decisión: `CB_ClasificarExcepcionNomina`.

### 4.1 Propósito

El proceso lleva un ciclo de pago desde la recolección de las novedades de los empleados (horas extra, incapacidades) hasta el pago por el banco y la emisión de los comprobantes. Antes de liquidar comprueba, con una tabla de decisión, que el periodo esté abierto, que los soportes estén completos y que no haya novedades fuera de la fecha de corte. Cada novedad se valida por separado, la liquidación pasa por dos controles (analista y jefatura) y el pago solo se da por hecho cuando el Banco lo confirma.

### 4.2 Participantes

El modelo principal tiene dos pools: «Corocora Benz», con cuatro carriles, y «Banco», un pool externo sin detalle interno (caja negra). Tres flujos de mensaje los conectan: uno sale de «Enviar dispersión al Banco» hacia el Banco y dos regresan del Banco a los eventos «Dispersión aceptada» y «Dispersión rechazada». El subproceso reutilizable tiene un solo pool, «Corocora Benz», con dos carriles que repiten los nombres del principal.

| Modelo | Pool / carril | Aplicación que lo atiende | Grupo de candidatos |
|---|---|---|---|
| Principal y reutilizable | Corocora Benz / Analista de Nómina | CentralSys para las tareas de usuario y para iniciar el ciclo. Las tareas automáticas del carril las ejecuta el Motor. | `analistas-nomina` |
| Principal y reutilizable | Corocora Benz / Gestión de Talento Humano | CentralSys | `gestion-talento-humano` |
| Principal | Corocora Benz / Jefatura de Nómina | CentralSys | `jefatura-nomina` |
| Principal | Corocora Benz / Tesorería | CentralSys para las tareas de usuario. El envío al Banco, el registro del pago y los comprobantes los ejecuta el Motor. | `tesoreria` |
| Principal | Banco (pool externo) | Banco | No aplica: recibe una llamada HTTP y responde con un mensaje. |

### 4.3 Inicio

El evento de inicio «Ciclo de pago iniciado» tiene el formulario `nomina-periodo`: periodo (`payPeriodId`), ciclo de pago (`payCycle`: quincenal o mensual) y fecha de corte de novedades (`cutoffDate`). El formulario incluye además tres casillas de demostración, desmarcadas por defecto, que permiten recorrer a voluntad cada ruta de excepción: periodo cerrado, soportes incompletos y novedades fuera de corte. Un usuario del grupo `analistas-nomina` inicia el ciclo desde CentralSys, cuyo backend arranca el proceso por la API REST del motor con una clave de negocio `NOM-<periodo>-<6 caracteres>`.

### 4.4 Flujo principal

1. **«Recolectar novedades del periodo»** (tarea de servicio, delegate `obtenerNovedadesDelegate`). Reúne la lista de novedades del periodo (`employeeChanges`) y calcula tres indicadores: si el periodo está abierto, si los soportes están completos y si hay novedades fuera de corte.
2. **«Evaluar novedades y fecha de corte»** (tarea de regla de negocio). Evalúa la tabla DMN `CB_ClasificarExcepcionNomina` con esos tres indicadores y obtiene la ruta de revisión (`reviewStatus`) y el código del motivo (`reasonCode`).
3. Compuerta exclusiva **«¿Qué resultado aplica?»**. En el caso normal toma la salida «Continuar».
4. **«Validar cada novedad de empleado»** (call activity multiinstancia que invoca `CB_ValidarNovedadEmpleado`). Recorre la colección `${employeeChanges}` y crea una instancia del subproceso por cada novedad, todas al mismo tiempo; a cada una le pasa el empleado, el tipo de novedad, el valor y si tiene soporte. Dentro de cada instancia:
   1. Evento de inicio «Novedad de empleado recibida».
   2. **«Revisar novedad y soporte»** (tarea de usuario, grupo `analistas-nomina`, formulario `nomina-revisar-novedad`). El analista marca si la novedad es válida (`changeValid`) y puede dejar una observación.
   3. Compuerta exclusiva «¿Novedad válida?». Si lo es, termina en el evento de fin «Novedad validada».
5. **«Calcular liquidación»** (tarea de servicio, delegate `calcularLiquidacionDelegate`). Deja el resumen (`payrollSummary`) y el total a pagar (`payrollTotal`).
6. **«Revisar liquidación»** (tarea de usuario, grupo `analistas-nomina`, formulario `nomina-revisar-liquidacion`). El analista indica si la liquidación es consistente (`payrollConsistent`).
7. Compuerta exclusiva **«¿Liquidación consistente?»**.
8. **«Aprobar liquidación»** (tarea de usuario, grupo `jefatura-nomina`, formulario `generated-approve-payroll`). La jefatura aprueba o devuelve (`payrollApproved`) y escribe el motivo.
9. Compuerta exclusiva **«¿Liquidación aprobada?»**.
10. **«Preparar lote de dispersión»** (tarea de usuario, grupo `tesoreria`, formulario `generated-prepare-bank-batch`). Tesorería registra el identificador del lote (`paymentBatchId`) y la cuenta de origen (`bankAccount`).
11. **«Enviar dispersión al Banco»** (tarea de servicio con conector HTTP). El motor envía `POST /api/dispersiones` al Banco con la clave de negocio, el lote, la cuenta, el total y el periodo.
12. Compuerta basada en eventos **«Respuesta del Banco»**. El proceso espera lo que ocurra primero: el mensaje de aceptación, el de rechazo o el paso de dos horas.
13. Evento intermedio de mensaje **«Dispersión aceptada»** (`DispersionAceptada`).
14. **«Registrar pago y cerrar periodo»** (tarea de servicio, delegate `registrarPagoDelegate`).
15. **«Generar comprobantes de pago»** (tarea de servicio, delegate `generarComprobantesDelegate`).
16. Evento de fin **«Nómina pagada y comprobantes emitidos»**.

### 4.5 Compuertas

| Compuerta | Tipo | Salida y condición |
|---|---|---|
| «¿Qué resultado aplica?» | Exclusiva | «Continuar», `${reviewStatus == 'CONTINUAR'}`: va a la validación de novedades. «Novedades fuera de corte», `${reviewStatus == 'REQUIERE_APROBACION_JEFATURA'}`: va a «Aprobar novedades fuera de corte». «Periodo cerrado o faltan soportes», `${reviewStatus == 'PERIODO_CERRADO' \|\| reviewStatus == 'DEVOLVER_A_TALENTO_HUMANO'}`: va a «Corregir novedades o reabrir periodo». |
| «¿Novedades aprobadas?» | Exclusiva | «Sí», `${lateChangesApproved == true}`: va a la validación de novedades. «No», `${lateChangesApproved == false}`: va a «Corregir novedades o reabrir periodo». |
| «¿Novedad válida?» (subproceso) | Exclusiva | «Sí», `${changeValid == true}`: termina en «Novedad validada». «No», `${changeValid == false}`: va a «Corregir novedad y adjuntar soporte». |
| «¿Liquidación consistente?» | Exclusiva | «Sí», `${payrollConsistent == true}`: va a «Aprobar liquidación». «No, recalcular», `${payrollConsistent == false}`: regresa a «Calcular liquidación». |
| «¿Liquidación aprobada?» | Exclusiva | «Sí», `${payrollApproved == true}`: va a «Preparar lote de dispersión». «No, devolver», `${payrollApproved == false}`: regresa a «Calcular liquidación». |
| «Respuesta del Banco» | Basada en eventos | No tiene condiciones. Tres salidas hacia tres eventos de espera: «Dispersión aceptada», «Dispersión rechazada» y «Sin respuesta en 2 h». Continúa por el primero que ocurra y descarta los otros dos. |

Las compuertas exclusivas sin nombre solo unen caminos que regresan (antes de recolectar novedades, antes de validar, antes de calcular, antes de enviar al Banco y antes de corregir el lote).

### 4.6 Eventos

| Evento | Tipo | Detalle |
|---|---|---|
| «Ciclo de pago iniciado» | Inicio | Con formulario `nomina-periodo`. Lo dispara CentralSys. |
| «Dispersión aceptada» | Intermedio de recepción de mensaje | Mensaje `DispersionAceptada`. Lo envía el Banco, dirigido por la clave de negocio, con el código de respuesta (`bankResponseCode`) y la referencia bancaria (`bankReference`). |
| «Dispersión rechazada» | Intermedio de recepción de mensaje | Mensaje `DispersionRechazada`. Lo envía el Banco con el código del rechazo (`bankResponseCode`). |
| «Sin respuesta en 2 h» | Intermedio de temporizador | Duración `PT2H`. Tiene un *execution listener* que, al salir del evento, escribe `bankResponseCode = 'SIN-RESPUESTA-2H'`, para que Tesorería sepa que no hubo respuesta. |
| «Nómina pagada y comprobantes emitidos» | Fin | Único fin del proceso principal. |
| «Novedad de empleado recibida» y «Novedad validada» | Inicio y fin del subproceso | Sin definición adicional. |

### 4.7 Caminos alternos y de excepción

- **Periodo cerrado o soportes incompletos.** Cuando la tabla DMN devuelve `PERIODO_CERRADO` o `DEVOLVER_A_TALENTO_HUMANO`, se abre **«Corregir novedades o reabrir periodo»** (tarea de usuario, grupo `gestion-talento-humano`, formulario `generated-correct-changes`). Talento Humano ve el motivo, registra la acción realizada (`correctionAction`: corregir los registros o solicitar la reapertura) y el detalle. El flujo regresa a «Recolectar novedades del periodo» y la evaluación se repite.
- **Novedades fuera de corte.** Cuando la tabla devuelve `REQUIERE_APROBACION_JEFATURA`, se abre **«Aprobar novedades fuera de corte»** (tarea de usuario, grupo `jefatura-nomina`, formulario `generated-approve-late-changes`). Si la jefatura las aprueba, el proceso continúa con la validación; si no, pasa a «Corregir novedades o reabrir periodo».
- **Novedad no válida (subproceso).** Si el analista marca una novedad como no válida, se abre **«Corregir novedad y adjuntar soporte»** (tarea de usuario, grupo `gestion-talento-humano`, formulario `generated-correct-change`) y después la novedad vuelve a «Revisar novedad y soporte». El ciclo se repite hasta que sea válida; las demás novedades siguen su curso de forma independiente.
- **Liquidación inconsistente o no aprobada.** Tanto el «No, recalcular» del analista como el «No, devolver» de la jefatura regresan a «Calcular liquidación». En el segundo caso, el analista ve el motivo de la devolución al revisar de nuevo.
- **Dispersión rechazada o sin respuesta del Banco.** Si llega el mensaje `DispersionRechazada`, o si pasan dos horas sin respuesta, se abre **«Corregir lote o consultar al Banco»** (tarea de usuario, grupo `tesoreria`, formulario `generated-fix-bank-batch`). Tesorería ve el código de respuesta, corrige el lote o la cuenta de origen y describe la gestión. El flujo regresa a «Enviar dispersión al Banco» y vuelve a esperar la respuesta.

### 4.8 Variables principales

| Variable | Quién la escribe | Para qué se usa |
|---|---|---|
| `payPeriodId`, `payCycle`, `cutoffDate` | Formulario de inicio | Identifican el ciclo de pago; el periodo se envía al Banco. |
| `simulatePeriodClosed`, `simulateMissingSupports`, `simulateLateChanges` | Casillas de demostración del formulario de inicio | Las lee «Recolectar novedades del periodo» para producir cada excepción. |
| `periodOpen`, `documentsComplete`, `hasLateChanges` | Delegate de «Recolectar novedades del periodo» | Entradas de la tabla DMN. |
| `employeeChanges` | Delegate de «Recolectar novedades del periodo» | Colección que recorre la multiinstancia; cada elemento (`employeeChange`) lleva empleado, tipo, valor y soporte. |
| `lateChangesSummary` | Delegate de «Recolectar novedades del periodo» | Texto que ve la jefatura al aprobar novedades fuera de corte. |
| `reviewStatus`, `reasonCode` | Tarea de regla de negocio | Condición de la compuerta «¿Qué resultado aplica?»; el motivo se muestra a Talento Humano. |
| `correctionAction`, `correctionNotes` | Tarea «Corregir novedades o reabrir periodo» | Registro de la corrección; tras ella, la recolección da el periodo por abierto y los soportes por completos. |
| `lateChangesApproved` | Tarea «Aprobar novedades fuera de corte» | Condición de la compuerta «¿Novedades aprobadas?». |
| `employeeId`, `changeType`, `changeValue`, `supportAttached` | Call activity (datos de una novedad, en cada instancia del subproceso) | Lo que revisa el analista y corrige Talento Humano. |
| `changeValid`, `reviewComment` | Tarea «Revisar novedad y soporte» | Condición de la compuerta «¿Novedad válida?» y observación para quien corrige. |
| `payrollSummary`, `payrollTotal` | Delegate de «Calcular liquidación» | Resumen y total que revisan el analista y la jefatura; el total se envía al Banco. |
| `payrollConsistent` | Tarea «Revisar liquidación» | Condición de la compuerta «¿Liquidación consistente?». |
| `payrollApproved`, `approvalReason` | Tarea «Aprobar liquidación» | Condición de la compuerta «¿Liquidación aprobada?» y motivo de la decisión. |
| `paymentBatchId`, `bankAccount` | Tareas «Preparar lote de dispersión» y «Corregir lote o consultar al Banco» | Datos del lote que se envían al Banco. |
| `paymentBatchSent`, `bankDispersionId` | Conector de «Enviar dispersión al Banco» | Acuse de recibo del Banco. |
| `bankResponseCode`, `bankReference` | Mensajes del Banco; el código también lo escribe el listener del temporizador | Resultado de la dispersión; lo ve Tesorería al corregir. |
| `paymentRecorded`, `payrollPeriodClosed`, `receiptsGenerated` | Delegates de «Registrar pago y cerrar periodo» y «Generar comprobantes de pago» | Dejan constancia del cierre del ciclo. |

### 4.9 Tabla de decisión `CB_ClasificarExcepcionNomina`

Nombre: «Clasificar control de nómina». Política de acierto: **UNIQUE** (las reglas son casos excluyentes; solo una puede coincidir).

Entradas: ¿periodo de nómina abierto? (`periodOpen`), ¿soportes completos? (`documentsComplete`) y ¿hay novedades fuera de corte? (`hasLateChanges`), las tres booleanas. Salidas: ruta de revisión (`reviewStatus`) y código del motivo (`reasonCode`).

| Regla | ¿Periodo abierto? | ¿Soportes completos? | ¿Novedades fuera de corte? | Ruta de revisión | Código del motivo |
|---|---|---|---|---|---|
| 1 | No | cualquiera | cualquiera | `PERIODO_CERRADO` | `REQUIERE_REAPERTURA` |
| 2 | Sí | No | cualquiera | `DEVOLVER_A_TALENTO_HUMANO` | `SOPORTE_INCOMPLETO` |
| 3 | Sí | Sí | Sí | `REQUIERE_APROBACION_JEFATURA` | `NOVEDAD_FUERA_DE_CORTE` |
| 4 | Sí | Sí | No | `CONTINUAR` | `NOVEDADES_VALIDAS` |

## 5. Postventa y Garantías

Modelos: `CB_PostventaGarantias` (principal) y `CB_AtenderOrdenTaller` (reutilizable). Tabla de decisión: `CB_EvaluarCoberturaGarantia`.

### 5.1 Propósito

Un cliente solicita un servicio para su vehículo. El proceso consulta el historial del vehículo, decide con una tabla de decisión si lo cubre una campaña o la garantía, y en caso contrario emite una cotización que el cliente debe aceptar. Una vez autorizada la atención, reserva los repuestos y agenda la cita al mismo tiempo, ejecuta la orden de taller (diagnóstico, reparación e inspección de calidad) y entrega el vehículo.

### 5.2 Participantes

El modelo principal tiene tres pools: «Corocora Benz», con tres carriles, y dos pools externos sin detalle interno, «Cliente» y «Bodega de repuestos». Los flujos de mensaje dibujados son: del Cliente al evento de inicio; de «Enviar cotización al cliente» al Cliente; del Cliente a los eventos «Cotización aceptada» y «Cotización rechazada»; de «Notificar cierre al cliente» y de «Entregar vehículo y cerrar caso» al Cliente, y de la Bodega al evento «Esperar aviso de repuestos disponibles». El subproceso reutilizable tiene el pool «Corocora Benz», con dos carriles, y el pool externo «Bodega de Repuestos», con un flujo de mensaje de ida («Solicitar repuesto a bodega») y uno de vuelta («Repuesto recibido»).

| Modelo | Pool / carril | Aplicación que lo atiende | Grupo de candidatos |
|---|---|---|---|
| Principal | Corocora Benz / Asesor de Servicio | CentralSys para las tareas de usuario. Las tareas automáticas del carril las ejecuta el Motor, que llama a Taller-Bodega. | `asesores-servicio` |
| Principal | Corocora Benz / Analista de Garantías | CentralSys. La tarea de regla de negocio la ejecuta el Motor. | `analistas-garantia` |
| Principal | Corocora Benz / Coordinador de Postventa | CentralSys. La reserva de repuestos la ejecuta el Motor contra Taller-Bodega. | `coordinadores-postventa` |
| Principal | Cliente (pool externo) | PortalCliente | Cuentas del grupo `clientes`; no reciben tareas de Camunda, se comunican por mensajes. |
| Principal y reutilizable | Bodega de repuestos (pool externo) | Taller-Bodega. Un usuario del grupo `bodega` puede operarla desde el panel de bodega de CentralSys. | No aplica: recibe llamadas HTTP y responde con mensajes. |
| Reutilizable | Corocora Benz / Técnico de Taller | CentralSys. La solicitud de repuesto la ejecuta el Motor contra Taller-Bodega. | `tecnicos-taller` |
| Reutilizable | Corocora Benz / Inspector de Calidad | CentralSys | `inspectores-calidad` |

### 5.3 Inicio

El evento de inicio «Solicitud de servicio recibida» es un evento de inicio por mensaje: espera el mensaje `SolicitudServicio`. El cliente llena en PortalCliente su nombre, el VIN, el kilometraje, el tipo de servicio (garantía, mantenimiento o reparación) y el síntoma; el backend del portal guarda la solicitud, genera una clave de negocio con el formato `PV-XXXXXXXX` y envía ese mensaje al motor con los datos como variables. El evento tiene además el formulario `postventa-solicitud`, con los mismos datos.

### 5.4 Flujo principal

Se describe primero el camino en que el vehículo está cubierto por campaña o garantía.

1. **«Registrar solicitud y asignar taller»** (tarea de usuario, grupo `asesores-servicio`, formulario `postventa-registro`). El asesor revisa los datos del cliente y elige el taller (`dealerId`).
2. **«Consultar historial y campañas del VIN»** (tarea de servicio con conector HTTP). El motor envía `GET /api/vehiculos/{vin}?km={kilometraje}` a Taller-Bodega y guarda si el vehículo cumple las condiciones de garantía (`warrantyEligible`), si tiene una campaña activa (`campaignActive`), el identificador de la campaña y el del historial.
3. **«Evaluar cobertura de garantía»** (tarea de regla de negocio). Evalúa la tabla DMN `CB_EvaluarCoberturaGarantia` y obtiene el resultado de cobertura (`coverageStatus`) y su motivo (`coverageReason`).
4. Compuerta exclusiva **«¿Qué cobertura aplica?»**. Con campaña o garantía, toma la salida «Campaña o garantía».
5. **«Revisar cobertura y evidencia»** (tarea de usuario, grupo `analistas-garantia`, formulario `postventa-garantia`). El analista ve el resultado de la tabla y aprueba o no la atención sin costo (`coverageApproved`), con una justificación.
6. Compuerta exclusiva **«¿Garantía aprobada?»**. Si la aprueba, la reparación queda autorizada.
7. **Compuerta paralela de apertura.** Abre dos ramas que avanzan al mismo tiempo:
   - Rama de repuestos: **«Reservar repuestos»** (tarea de servicio con conector HTTP, `POST /api/reservas` en Taller-Bodega), seguida de la compuerta exclusiva **«¿Repuestos disponibles?»**. Si la bodega responde que quedaron reservados, la rama termina.
   - Rama de agenda: **«Agendar cita en el taller»** (tarea de usuario, grupo `coordinadores-postventa`, formulario `generated-schedule-workshop`). El coordinador registra la fecha y hora de la cita y el número de la orden de taller (`serviceOrderId`).
8. **Compuerta paralela de cierre.** Espera a que terminen las dos ramas.
9. **«Atender orden de taller»** (call activity que invoca `CB_AtenderOrdenTaller`). Le pasa el VIN, el síntoma, el kilometraje y la orden de taller, y recibe el diagnóstico, el trabajo realizado, el costo y el resultado de la inspección. Dentro del subproceso:
   1. Evento de inicio «Orden de taller recibida».
   2. **«Recibir y diagnosticar vehículo»** (tarea de usuario, grupo `tecnicos-taller`, formulario `taller-diagnostico`). El técnico registra el kilometraje de recepción, el diagnóstico y si necesita un repuesto adicional.
   3. Compuerta exclusiva «¿Requiere repuesto adicional?». Si no lo requiere, pasa directamente a la reparación.
   4. **«Reparar vehículo»** (tarea de usuario, grupo `tecnicos-taller`, formulario `generated-repair-vehicle`). El técnico describe el trabajo realizado y el costo del servicio.
   5. **«Inspeccionar reparación»** (tarea de usuario, grupo `inspectores-calidad`, formulario `generated-inspect-repair`). El inspector registra el resultado de la prueba de ruta y aprueba o no la reparación (`inspectionPassed`).
   6. Compuerta exclusiva «¿Inspección aprobada?». Si la aprueba, termina en el evento de fin «Vehículo reparado e inspeccionado».
10. **«Entregar vehículo y cerrar caso»** (tarea de usuario, grupo `asesores-servicio`, formulario `generated-deliver-vehicle`). El asesor ve la orden, el trabajo y el costo, y registra la satisfacción del cliente (de 1 a 5) y una observación de entrega.
11. Evento de fin **«Vehículo entregado y caso cerrado»**.

### 5.5 Compuertas

| Compuerta | Tipo | Salida y condición |
|---|---|---|
| «¿Qué cobertura aplica?» | Exclusiva | «Campaña o garantía», `${coverageStatus == 'CAMPANA_ACTIVA' \|\| coverageStatus == 'CUBIERTO_POR_GARANTIA'}`: va a «Revisar cobertura y evidencia». «Requiere cotización», `${coverageStatus == 'REQUIERE_COTIZACION'}`: va a «Enviar cotización al cliente». |
| «¿Garantía aprobada?» | Exclusiva | «Sí», `${coverageApproved == true}`: la reparación queda autorizada. «No», `${coverageApproved == false}`: va a «Enviar cotización al cliente». |
| «Respuesta a la cotización» | Basada en eventos | No tiene condiciones. Tres salidas hacia tres eventos de espera: «Cotización aceptada», «Cotización rechazada» y «Plazo de cotización vencido (48 h)». Continúa por el primero que ocurra. |
| Compuertas paralelas (sin nombre) | Paralelas | La de apertura lanza a la vez la reserva de repuestos y la agenda de la cita; la de cierre espera a que ambas terminen antes de pasar al taller. |
| «¿Repuestos disponibles?» | Exclusiva | «Sí», `${partsReservationStatus == 'RESERVED'}`: la rama termina. «No», `${partsReservationStatus == 'PENDIENTE'}`: va a «Esperar aviso de repuestos disponibles». |
| «¿Requiere repuesto adicional?» (subproceso) | Exclusiva | «Sí», `${additionalPartNeeded == true}`: va a «Solicitar repuesto a bodega». «No», `${additionalPartNeeded == false}`: va a «Reparar vehículo». |
| «¿Inspección aprobada?» (subproceso) | Exclusiva | «Sí», `${inspectionPassed == true}`: termina el subproceso. «No, repetir reparación», `${inspectionPassed == false}`: regresa a «Reparar vehículo». |

Las compuertas exclusivas sin nombre solo unen caminos (reparación autorizada por garantía o por cotización aceptada; cierre por rechazo o por vencimiento; repuestos reservados de inmediato o después de la espera; entrada a la reparación).

### 5.6 Eventos

| Evento | Tipo | Detalle |
|---|---|---|
| «Solicitud de servicio recibida» | Inicio por mensaje | Mensaje `SolicitudServicio`. Lo envía PortalCliente cuando el cliente registra su solicitud. |
| «Cotización aceptada» | Intermedio de recepción de mensaje | Mensaje `CotizacionAceptada`. Lo envía PortalCliente cuando el cliente acepta. |
| «Cotización rechazada» | Intermedio de recepción de mensaje | Mensaje `CotizacionRechazada`. Lo envía PortalCliente cuando el cliente rechaza. |
| «Plazo de cotización vencido (48 h)» | Intermedio de temporizador | Duración `PT48H`. |
| «Esperar aviso de repuestos disponibles» | Intermedio de recepción de mensaje | Mensaje `RepuestosDisponibles`. Lo envía Taller-Bodega cuando llegan los repuestos de una reserva pendiente; trae `partsReservationStatus = RESERVED`. |
| «Repuesto recibido» (subproceso) | Intermedio de recepción de mensaje | Mensaje `RepuestoRecibido`. Lo envía Taller-Bodega cuando entrega el repuesto solicitado; trae la referencia de la entrega (`partReceivedRef`). |
| «Vehículo entregado y caso cerrado» | Fin | Cierre normal. |
| «Reparación no autorizada» | Fin | Cierre sin reparación, por rechazo o vencimiento de la cotización. |
| «Orden de taller recibida» y «Vehículo reparado e inspeccionado» | Inicio y fin del subproceso | Sin definición adicional. |

Todos los mensajes se dirigen a la instancia correcta por la clave de negocio del caso.

### 5.7 Caminos alternos y de excepción

- **El servicio requiere cotización.** Ocurre cuando la tabla DMN devuelve `REQUIERE_COTIZACION` o cuando el analista no aprueba la garantía. El motor ejecuta **«Enviar cotización al cliente»** (tarea de servicio con conector HTTP, `POST /api/cotizaciones` en Taller-Bodega), que registra la cotización y devuelve su número (`quoteId`) y su valor (`quoteAmount`); el cliente la consulta en PortalCliente. El proceso queda en la compuerta basada en eventos «Respuesta a la cotización».
- **El cliente acepta la cotización.** El mensaje `CotizacionAceptada` lleva el flujo al mismo punto que la garantía aprobada: compuerta paralela, taller y entrega.
- **El cliente rechaza la cotización o no responde en 48 horas.** El mensaje `CotizacionRechazada` o el temporizador llevan a **«Notificar cierre al cliente»** (tarea de servicio, delegate `enviarNotificacionDelegate`, que envía un correo al cliente) y al fin «Reparación no autorizada».
- **No hay repuestos al reservar.** Si la bodega responde `PENDIENTE`, la rama de repuestos se detiene en «Esperar aviso de repuestos disponibles» hasta que llegue el mensaje `RepuestosDisponibles`. La cita se puede agendar mientras tanto, pero el taller no empieza hasta que la compuerta paralela de cierre reciba las dos ramas.
- **El técnico necesita un repuesto adicional (subproceso).** El motor ejecuta **«Solicitar repuesto a bodega»** (tarea de servicio con conector HTTP, `POST /api/solicitudes-repuesto` en Taller-Bodega, con el VIN y el código del repuesto) y el subproceso espera el mensaje `RepuestoRecibido` antes de pasar a «Reparar vehículo».
- **La inspección no aprueba la reparación (subproceso).** La salida «No, repetir reparación» regresa a «Reparar vehículo», donde el técnico ve el resultado de la última inspección. El ciclo se repite hasta que la inspección apruebe.

### 5.8 Variables principales

| Variable | Quién la escribe | Para qué se usa |
|---|---|---|
| `customerName`, `vin`, `mileageKm`, `serviceType`, `symptom` | PortalCliente, en el mensaje de inicio | Datos de la solicitud; el VIN y el kilometraje se usan en la consulta del vehículo. |
| `customerUserId`, `customerEmail` (opcional) | PortalCliente, en el mensaje de inicio | Cuenta del cliente dueño de la solicitud y correo para notificarle. |
| `dealerId` | Tarea «Registrar solicitud y asignar taller» | Taller asignado; lo ve el coordinador al agendar. |
| `warrantyEligible`, `campaignActive`, `campaignId`, `historyRecordId` | Conector de «Consultar historial y campañas del VIN» | Los dos primeros son las entradas de la tabla DMN. |
| `coverageStatus`, `coverageReason` | Tarea de regla de negocio | Condición de la compuerta «¿Qué cobertura aplica?»; los ve el analista de garantías. |
| `coverageApproved`, `warrantyNote` | Tarea «Revisar cobertura y evidencia» | Condición de la compuerta «¿Garantía aprobada?» y justificación. |
| `quoteId`, `quoteAmount` | Conector de «Enviar cotización al cliente» | Cotización que el cliente ve y responde en PortalCliente. |
| `partsReservationStatus` | Conector de «Reservar repuestos»; después, el mensaje `RepuestosDisponibles` | Condición de la compuerta «¿Repuestos disponibles?». |
| `appointmentDate`, `serviceOrderId` | Tarea «Agendar cita en el taller» | Cita y orden de taller; la orden se pasa al subproceso. |
| `diagnosis`, `additionalPartNeeded`, `partCode` | Tarea «Recibir y diagnosticar vehículo» | Diagnóstico; condición de «¿Requiere repuesto adicional?» y código del repuesto que se pide a la bodega. |
| `partRequestId`, `partReceivedRef` | Conector de «Solicitar repuesto a bodega» y mensaje `RepuestoRecibido` | Seguimiento del repuesto adicional (quedan en el subproceso). |
| `repairNotes`, `serviceCost` | Tarea «Reparar vehículo» | Trabajo y costo; vuelven al proceso principal y se muestran en la entrega. |
| `inspectionPassed`, `inspectionResults` | Tarea «Inspeccionar reparación» | Condición de «¿Inspección aprobada?» y resultado que ve el técnico si debe repetir. |
| `customerSatisfaction`, `deliveryNote` | Tarea «Entregar vehículo y cerrar caso» | Cierre del caso. |
| `notificationSent`, `notificationMode` | Delegate de notificaciones | Indican si la notificación de cierre se envió por correo. |

### 5.9 Tabla de decisión `CB_EvaluarCoberturaGarantia`

Nombre: «Clasificar cobertura de garantía y campañas». Política de acierto: **UNIQUE**. Una campaña activa tiene precedencia sobre la garantía ordinaria.

Entradas: ¿hay campaña activa para el VIN? (`campaignActive`) y ¿cumple condiciones de garantía? (`warrantyEligible`), ambas booleanas. Salidas: resultado de cobertura (`coverageStatus`) y motivo de la decisión (`coverageReason`).

| Regla | ¿Campaña activa? | ¿Cumple garantía? | Resultado de cobertura | Motivo |
|---|---|---|---|---|
| 1 | Sí | cualquiera | `CAMPANA_ACTIVA` | «Campaña activa para el VIN» |
| 2 | No | Sí | `CUBIERTO_POR_GARANTIA` | «Cumple las condiciones de garantía configuradas» |
| 3 | No | No | `REQUIERE_COTIZACION` | «Fuera de cobertura; requiere cotización» |

## 6. Elementos avanzados usados

| Elemento | Dónde aparece | Para qué |
|---|---|---|
| Call activity (subproceso reutilizable) | «Resolver incidente técnico» (TI), «Validar cada novedad de empleado» (Nómina) y «Atender orden de taller» (Postventa) | Separar en un modelo propio una parte del trabajo con sus propios carriles. Cada llamada define qué variables entran y cuáles regresan, y propaga la clave de negocio. |
| Multiinstancia paralela | Call activity «Validar cada novedad de empleado» (Nómina), sobre la colección `${employeeChanges}` | Validar todas las novedades del periodo al mismo tiempo, cada una en su propia instancia del subproceso. |
| Tarea de regla de negocio con tabla DMN | «Clasificar prioridad y plazo» (TI), «Evaluar novedades y fecha de corte» (Nómina) y «Evaluar cobertura de garantía» (Postventa) | Sacar del diagrama las reglas de negocio. El resultado alimenta la compuerta siguiente y, en TI, la duración de un temporizador. |
| Temporizador de borde que interrumpe | «Plazo de resolución vencido», sobre la call activity de TI (duración `${targetDuration}`), y «Sin respuesta en 24 h», sobre «Confirmar solución del incidente» (`PT24H`) | Vigilar el plazo de resolución con escalamiento al responsable, y cerrar el ticket si el usuario no responde. |
| Temporizador con duración calculada | «Plazo de resolución vencido» (TI) | El plazo no está fijo en el modelo: lo decide la tabla DMN según la prioridad. |
| Compuerta basada en eventos | «Respuesta del Banco» (Nómina: dos mensajes y un temporizador de `PT2H`) y «Respuesta a la cotización» (Postventa: dos mensajes y un temporizador de `PT48H`) | Esperar la respuesta de un participante externo y reaccionar también cuando no responde. |
| Evento de inicio por mensaje | «Solicitud de servicio recibida» (Postventa, `SolicitudServicio`) | Iniciar el proceso desde una aplicación externa al motor. |
| Eventos intermedios de recepción de mensaje | `DispersionAceptada` y `DispersionRechazada` (Nómina); `CotizacionAceptada`, `CotizacionRechazada` y `RepuestosDisponibles` (Postventa); `RepuestoRecibido` (taller) | Recibir respuestas asíncronas del Banco, del cliente y de la bodega, dirigidas por la clave de negocio. |
| Evento intermedio de envío de mensaje | «Solución notificada al usuario» (TI, `SolucionNotificada`) | Avisar al solicitante que debe confirmar la solución; lo implementa un delegate que envía un correo. |
| Compuerta paralela (apertura y cierre) | Postventa, alrededor de «Reservar repuestos» y «Agendar cita en el taller» | Adelantar las dos gestiones a la vez y empezar el taller solo cuando ambas estén listas. |
| Pools externos y flujos de mensaje | «Banco» (Nómina), «Cliente» y «Bodega de repuestos» (Postventa), «Bodega de Repuestos» (taller) | Mostrar la colaboración con los participantes que no pertenecen a la organización o que son otro sistema. |
| Tareas de servicio con conector HTTP | TI: «Registrar ticket del incidente», «Registrar solución en la base de conocimiento», «Cerrar ticket verificado» y «Cerrar ticket por falta de respuesta». Nómina: «Enviar dispersión al Banco». Postventa: «Consultar historial y campañas del VIN», «Enviar cotización al cliente», «Reservar repuestos» y «Solicitar repuesto a bodega» | Invocar las API REST de PortalTI, Banco y Taller-Bodega directamente desde el modelo, sin código adicional por tarea. |
| Tareas de servicio con delegate Java | Nómina: «Recolectar novedades del periodo», «Calcular liquidación», «Registrar pago y cerrar periodo» y «Generar comprobantes de pago». TI y Postventa: «Notificar cierre por falta de respuesta» y «Notificar cierre al cliente» | Ejecutar lógica propia del motor: los pasos de nómina y el envío de notificaciones por correo. |
| Execution listener | Evento «Sin respuesta en 2 h» (Nómina) | Dejar en `bankResponseCode` el valor `SIN-RESPUESTA-2H` para que la tarea de corrección muestre por qué se abrió. |
| Asignación dinámica de tarea | «Confirmar solución del incidente» (TI), con `camunda:assignee="${requesterId}"` | Que la confirmación la reciba exactamente la persona que reportó el incidente. |
| Formularios de Camunda enlazados | Los tres eventos de inicio y todas las tareas de usuario (`camunda:formRef`) | Definir en un archivo editable los datos que captura cada paso; CentralSys y Tasklist muestran el mismo formulario. |
| Continuación asíncrona | Todas las tareas con conector HTTP (`camunda:asyncBefore`); también la compuerta paralela de cierre de Postventa | En las tareas con conector: si el sistema externo responde con error, el motor reintenta la llamada y, si persiste, deja un incidente visible en Cockpit. |
| Ciclos de reproceso | TI (reapertura y plazo vencido), Nómina (correcciones, recálculo y reenvío al Banco), taller (repetir reparación) | Modelar las devoluciones entre áreas sin duplicar tareas. |

## 7. Correspondencia entre aplicaciones y procesos

Las aplicaciones se comunican con los procesos por tres mecanismos:

- **API REST del motor, desde el backend de un portal.** El navegador solo habla con su portal; es el backend del portal el que inicia procesos, consulta y completa tareas o envía mensajes al motor.
- **Conector HTTP del motor.** Una tarea de servicio del modelo llama a la API REST de otra aplicación y guarda la respuesta en variables del proceso.
- **Mensaje de correlación.** Una aplicación envía al motor un mensaje con nombre y clave de negocio (`POST /engine-rest/message`), y el motor lo entrega al evento de mensaje que lo está esperando en esa instancia.

| Aplicación | Proceso | Carriles y tareas que atiende | Mecanismo |
|---|---|---|---|
| Motor (`cb-bpm-engine`) | Los tres y sus subprocesos | Ejecuta los modelos: compuertas, temporizadores, tablas DMN, multiinstancia y las tareas de servicio. Con delegates: las cuatro tareas automáticas de Nómina y las notificaciones de TI y Postventa. Con conectores: las llamadas a PortalTI, Banco y Taller-Bodega. | Camunda embebido; delegates Java y conectores HTTP. |
| PortalTI (`cb-portal-ti`) | Incidentes de TI | Carril «Usuario Interno Solicitante»: evento de inicio «Incidente de TI reportado» y tarea «Confirmar solución del incidente». | API REST del motor desde el backend del portal: inicia el proceso con la clave `TI-…` y completa la tarea con `userConfirmed`. |
| PortalTI (API de tickets) | Incidentes de TI | Carriles «Mesa de Servicio» y «Soporte TI Nivel 1»: «Registrar ticket del incidente», «Registrar solución en la base de conocimiento», «Cerrar ticket verificado» y «Cerrar ticket por falta de respuesta». | Conector HTTP del motor: `POST /api/tickets`, `/api/tickets/{ticketId}/solucion` y `/api/tickets/{ticketId}/cerrar`. |
| CentralSys (`cb-central-sys`) | Incidentes de TI | «Mesa de Servicio» (`mesa-servicio`): «Validar clasificación y asignar grupo». «Responsable del Servicio TI» (`responsables-ti`): «Revisar incidente con plazo vencido». «Soporte TI Nivel 1» (`soporte-ti-n1`): «Diagnosticar y resolver». «Soporte Especializado» (`soporte-ti-especializado`): «Analizar causa raíz y resolver». | API REST del motor desde el backend: bandeja por grupo, reclamar, mostrar el formulario y completar. |
| CentralSys | Nómina | Inicio del ciclo de pago. «Analista de Nómina» (`analistas-nomina`): «Revisar novedad y soporte» y «Revisar liquidación». «Gestión de Talento Humano» (`gestion-talento-humano`): «Corregir novedades o reabrir periodo» y «Corregir novedad y adjuntar soporte». «Jefatura de Nómina» (`jefatura-nomina`): «Aprobar novedades fuera de corte» y «Aprobar liquidación». «Tesorería» (`tesoreria`): «Preparar lote de dispersión» y «Corregir lote o consultar al Banco». | API REST del motor desde el backend: inicia el proceso con la clave `NOM-…` y gestiona las tareas. |
| CentralSys | Postventa | «Asesor de Servicio» (`asesores-servicio`): «Registrar solicitud y asignar taller» y «Entregar vehículo y cerrar caso». «Analista de Garantías» (`analistas-garantia`): «Revisar cobertura y evidencia». «Coordinador de Postventa» (`coordinadores-postventa`): «Agendar cita en el taller». «Técnico de Taller» (`tecnicos-taller`): «Recibir y diagnosticar vehículo» y «Reparar vehículo». «Inspector de Calidad» (`inspectores-calidad`): «Inspeccionar reparación». | API REST del motor desde el backend. |
| CentralSys (panel de bodega) | Postventa | Pool «Bodega de repuestos»: un usuario del grupo `bodega` marca como disponibles los repuestos de una reserva pendiente o entrega un repuesto solicitado. | El backend reenvía la acción a la API REST de Taller-Bodega, que es la que envía el mensaje al motor. |
| PortalCliente (`cb-portal-cliente`) | Postventa | Pool «Cliente»: evento de inicio «Solicitud de servicio recibida» y eventos «Cotización aceptada» y «Cotización rechazada». También muestra al cliente el estado de su solicitud y la cotización. | Mensajes de correlación `SolicitudServicio`, `CotizacionAceptada` y `CotizacionRechazada`, enviados por el backend del portal con la clave `PV-…`; el seguimiento se consulta por la API REST del motor. |
| Banco (`cb-banco`) | Nómina | Pool «Banco»: recibe la tarea «Enviar dispersión al Banco» y responde a los eventos «Dispersión aceptada» o «Dispersión rechazada». | Conector HTTP del motor (`POST /api/dispersiones`) para la ida; mensaje de correlación `DispersionAceptada` o `DispersionRechazada` para la vuelta. |
| Taller-Bodega (`cb-taller-bodega`) | Postventa | Tareas automáticas de los carriles «Asesor de Servicio» y «Coordinador de Postventa»: «Consultar historial y campañas del VIN», «Enviar cotización al cliente» y «Reservar repuestos». Pool «Bodega de repuestos»: evento «Esperar aviso de repuestos disponibles». | Conector HTTP del motor (`GET /api/vehiculos/{vin}`, `POST /api/cotizaciones`, `POST /api/reservas`); mensaje de correlación `RepuestosDisponibles`. |
| Taller-Bodega | Subproceso de taller | Carril «Técnico de Taller»: «Solicitar repuesto a bodega». Pool «Bodega de Repuestos»: evento «Repuesto recibido». | Conector HTTP del motor (`POST /api/solicitudes-repuesto`); mensaje de correlación `RepuestoRecibido`. |

En resumen, cada carril con tareas de usuario lo atiende CentralSys mediante el grupo de candidatos de la tarea, salvo el del empleado que reporta un incidente, que tiene su propio portal; cada participante externo dibujado como pool aparte (Cliente, Banco y Bodega) corresponde a una aplicación distinta que se comunica con el proceso por mensajes, y las tareas automáticas las ejecuta el motor, por sí mismo o llamando a esas aplicaciones.
