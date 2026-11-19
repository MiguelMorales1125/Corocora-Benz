# Casos de prueba y resultados

Este documento reúne los casos de prueba de la demostración Corocora Benz, el resultado que se esperaba de cada uno y el resultado que se obtuvo en la última ejecución completa. Está escrito para una persona que no conoce el código: cada caso se describe con palabras del negocio y se indica qué prueba automatizada lo ejecuta.

Resumen de la última ejecución (19 de noviembre de 2026, los seis sistemas contra PostgreSQL 17):

| Nivel | Qué se ejecutó | Resultado |
|---|---|---|
| Pruebas integradas de los procesos por la REST de Camunda (`e2e`) | 12 escenarios de los tres procesos y 1 comprobación del motor | 13 de 13 correctos, 202 verificaciones, 0 incidentes en el motor, 0 instancias de prueba activas al terminar |
| Pruebas integradas por las API de los portales (`portales`) | 7 escenarios de punta a punta con la sesión de cada persona | 7 de 7 correctos, 217 verificaciones, 0 instancias de prueba activas al terminar |
| Seguridad y permisos (`permisos`) | Sesión, CSRF, permisos por grupo, aislamiento | Correcto, 73 verificaciones |
| Cuentas (`identidades`) | 18 cuentas de demostración y motor protegido | Correcto |
| Validaciones y reintentos (`regresion`) | Casos límite de las API internas | Correcto |
| Pruebas unitarias y de integración por módulo (`mvnw`) | 236 pruebas en 7 módulos | BUILD SUCCESS, 236 pruebas |

En total se documentan aquí **40 casos automatizados** (19 de proceso y 21 transversales) y **8 comprobaciones en el navegador**: tres acciones sueltas y el ensayo completo del guion de demostración (sección 3.5).

Vocabulario que se usa en las tablas:

- **Caso** o **clave del caso** (`businessKey`): el identificador con el que los seis sistemas se refieren al mismo trámite, por ejemplo `TI-…` para un incidente, `NOM-…` para un ciclo de nómina y `PV-…` para una solicitud de postventa.
- **Verificación**: una comprobación concreta dentro de un escenario (por ejemplo, «el ticket quedó cerrado»). Si una verificación no se cumple, el escenario se detiene y se informa como `FAIL`. Por eso un escenario `OK` significa que **todas** sus verificaciones se cumplieron.
- **Fin** del proceso: el evento de fin del diagrama BPMN en el que terminó la instancia. Se indica con su nombre en el diagrama y, entre paréntesis, su identificador técnico.
- **Temporizador**: un plazo del proceso (2, 8 o 24 horas para resolver un incidente, 24 horas para confirmar la solución, 48 horas de vigencia de la cotización, 2 horas de espera al Banco). Las pruebas no esperan esas horas: comprueban que el plazo quedó programado con la duración correcta y luego lo vencen ejecutando su trabajo pendiente en el motor.

## 1. Cómo se ejecutan las pruebas y entorno de la última ejecución

### 1.1 Dos niveles de prueba

1. **Pruebas por módulo.** Cada aplicación tiene pruebas unitarias y de integración que no necesitan ningún sistema arrancado: usan una base H2 en memoria y, cuando hace falta, un motor simulado. Se describen en la sección 5.
2. **Pruebas integradas.** Cinco suites escritas en Node.js que se ejecutan contra los seis sistemas arrancados (el motor Camunda, los tres portales y los simuladores de Banco y Taller-Bodega). Son las que demuestran los procesos de principio a fin y se describen en las secciones 2 y 3.

### 1.2 Comandos

Requisitos: Java 17 o superior y Node.js 20 o superior. Todos los comandos se ejecutan desde la carpeta `corocora-benz-embebida` del repositorio. Las suites de Node.js no tienen dependencias que instalar.

```powershell
# Pruebas por módulo (no necesitan nada arrancado)
.\mvnw.cmd test

# Compilar y arrancar los seis sistemas (sin argumento usa PostgreSQL; con "h2", bases en memoria)
.\mvnw.cmd package -DskipTests
.\iniciar-sistemas.bat

# Pruebas integradas, con los seis sistemas arrancados
cd tests
npm run todo
```

`npm run todo` ejecuta las cinco suites en este orden y se detiene en la primera que falle. Cada una se puede ejecutar por separado:

| Comando | Qué comprueba | Sistemas que necesita |
|---|---|---|
| `npm run e2e` | 12 escenarios de los tres procesos por la REST de Camunda, más la clave de caso automática | Motor, Portal TI, Banco, Taller-Bodega |
| `npm run identidades` | Cuentas, grupos y motor protegido | Motor |
| `npm run regresion` | Validaciones, reintentos idempotentes y API protegidas | Portal TI, Banco, Taller-Bodega |
| `npm run permisos` | Sesión, permisos por grupo, aislamiento entre clientes | Los seis |
| `npm run portales` | Los tres procesos de punta a punta por las API de los portales | Los seis |

La diferencia entre `e2e` y `portales` es la puerta de entrada. `e2e` habla directamente con el motor: completa las tareas humanas por la REST de Camunda con las variables de sus formularios, simula al cliente enviando los mensajes de la cotización y vence los temporizadores. `portales` hace lo mismo que haría cada persona en su navegador: inicia sesión en el portal que le corresponde con su cuenta, ve la tarea en su bandeja, la reclama, abre su formulario y la completa; el cliente y el empleado que reporta usan sus propios portales. En las dos suites el Banco y la Bodega se ponen temporalmente en modo manual para que la prueba decida la respuesta, y al terminar se restaura la configuración que tenían.

### 1.3 Entorno de la última ejecución

| Dato | Valor |
|---|---|
| Fecha | 19 de noviembre de 2026 |
| Comando | `npm run todo`, ejecutado dos veces seguidas; las dos pasadas dieron los mismos escenarios correctos y el mismo número de verificaciones |
| Código de salida | 0 (todas las suites correctas) |
| Sistemas | Los seis, arrancados con el perfil por defecto |
| Base de datos | PostgreSQL 17 (los registros de arranque informan la versión 17.2): base `corocora_camunda` para el motor y base `corocora` para los demás sistemas |
| Motor de procesos | Camunda 7.24 embebido en Spring Boot |
| Java | 24.0.1 (según los registros de arranque) |
| Sistema operativo | Windows 11 |
| Correo | `CORREO_HABILITADO=false`: las notificaciones se escriben en el registro del motor y no se envían |
| Pruebas por módulo | Último `mvnw clean install`: BUILD SUCCESS con 236 pruebas |

### 1.4 Salida real de la última ejecución

Esta es la salida de la segunda pasada de `npm run todo`, sin modificar. Cada línea `OK` es un resultado obtenido; entre paréntesis aparecen la duración del escenario y el número de verificaciones que cumplió.

```text
> todo
> node e2e.mjs && node identidades.mjs && node regresion.mjs && node permisos.mjs && node portales.mjs

OK   Postventa A: recall activo, garantía aprobada, AND, taller con una inspección fallida (2.0 s, 21 verificaciones)
OK   Postventa B: fuera de garantía, cotización rechazada (0.7 s, 7 verificaciones)
OK   Postventa C: garantía no aprobada, cotización aceptada, repuesto adicional (2.0 s, 18 verificaciones)
OK   Postventa D: cotización sin respuesta, vence el timer de 48 h (0.2 s, 6 verificaciones)
OK   Postventa E: Bodega avisa cuando el repuesto vuelve a estar disponible (1.6 s, 18 verificaciones)
OK   TI A: P3, Nivel 1, usuario confirma (1.5 s, 11 verificaciones)
OK   TI B: P1 por seguridad, vence el plazo, usuario rechaza y luego no responde (2.0 s, 19 verificaciones)
OK   Nómina A: CONTINUAR, novedad corregida, recálculo, devolución de Jefatura, timeout y rechazo bancario (1.6 s, 27 verificaciones)
OK   Nómina B: periodo cerrado y reapertura (0.7 s, 17 verificaciones)
OK   Nómina C: soportes incompletos (0.7 s, 17 verificaciones)
OK   Nómina D: novedad fuera de corte rechazada por Jefatura (0.7 s, 18 verificaciones)
OK   Nómina E: novedad fuera de corte aprobada (3 novedades en multiinstancia) (0.7 s, 18 verificaciones)
OK   Motor: businessKey automática al iniciar sin clave (TI, Nómina y Postventa) (0.2 s, 5 verificaciones)

Verificaciones: 202; formularios Camunda usados: generated-approve-late-changes, generated-approve-payroll, generated-confirm-solution, generated-correct-change, generated-correct-changes, generated-deliver-vehicle, generated-fix-bank-batch, generated-inspect-repair, generated-prepare-bank-batch, generated-repair-vehicle, generated-review-sla, generated-schedule-workshop, nomina-revisar-liquidacion, nomina-revisar-novedad, postventa-garantia, postventa-registro, taller-diagnostico, ti-diagnostico, ti-triaje
Incidentes en el engine: 0; instancias de prueba activas: 0
OK: 18 cuentas de demostración (14 empleados con su grupo, 2 clientes, USR-01 y demo) y API de Camunda protegida
OK: credencial interna, validación, transición de ticket y reintentos de cotización y dispersión
OK: 73 verificaciones de sesión, CSRF, permisos por grupo, aislamiento entre clientes y portales, y APIs internas protegidas
OK   TI A: USR-01 reporta, mesa1 hace el triaje, soporte1 resuelve y USR-01 confirma (1.6 s, 17 verificaciones)
OK   TI B: USR-01 rechaza la solución, soporte1 resuelve de nuevo y USR-01 confirma (2.2 s, 24 verificaciones)
OK   Postventa C: cotización aceptada, repuesto adicional entregado por bodega1 (1.6 s, 44 verificaciones)
OK   Postventa D: garantía por campaña aprobada, sin repuesto adicional (0.8 s, 46 verificaciones)
OK   Postventa E: el cliente rechaza la cotización (0.8 s, 14 verificaciones)
OK   Nómina F: novedades, liquidación, aprobación, lote y Banco acepta (1.2 s, 33 verificaciones)
OK   Nómina G: el Banco rechaza la cuenta, Tesorería la corrige y el Banco acepta (1.4 s, 39 verificaciones)

Escenarios correctos: 7 de 7; verificaciones: 217
Instancias de prueba que quedaron activas: 0
```

Cómo leer esta salida:

- Las trece primeras líneas `OK` son de `e2e`. Esa suite solo termina con código 0 si, además, el motor no tiene ningún incidente y no queda activa ninguna instancia de prueba; las dos condiciones se cumplieron («Incidentes en el engine: 0; instancias de prueba activas: 0»).
- La lista «formularios Camunda usados» son los 19 formularios de tarea que `e2e` encontró desplegados y usó. Los otros tres formularios del proyecto son formularios de inicio (`ti-reporte`, `nomina-periodo`, `postventa-solicitud`).
- Las tres líneas `OK:` siguientes son, en orden, `identidades`, `regresion` y `permisos`. Estas suites se detienen en la primera comprobación que no se cumpla, de modo que su `OK` significa que se cumplieron todas.
- Las siete últimas líneas `OK` son de `portales`.

## 2. Casos por proceso

En la columna «Resultado esperado» aparece lo que el escenario comprueba realmente (el fin en el que termina el proceso, las variables y el estado del ticket, de la dispersión o de la solicitud), no una descripción genérica. En la columna «Resultado obtenido» aparece el dato de la línea correspondiente de la salida de la sección 1.4.

### 2.1 Gestión de incidentes de TI

| Caso | Tipo | Qué recorre | Datos de entrada | Resultado esperado | Resultado obtenido | Prueba que lo automatiza |
|---|---|---|---|---|---|---|
| CP-TI-01 | Camino principal | Un empleado reporta un incidente. El sistema registra el ticket en el Portal TI y la tabla de decisión calcula la prioridad. Mesa de Servicio valida la clasificación y lo asigna a Soporte Nivel 1. Nivel 1 registra la causa y la solución. Se notifica al usuario, que confirma que quedó resuelto, y el ticket se cierra. | Impacto Medio, urgencia Media, sin impacto de seguridad, 1 equipo afectado. Grupo de atención: Nivel 1. El usuario confirma. | En el triaje se ven la prioridad P3 y el plazo PT24H calculados por la tabla de decisión. El plazo de resolución queda programado a 24 horas. El técnico ve el ticket, la prioridad y la descripción. Antes de la confirmación el ticket del Portal TI está en RESOLVED con la solución registrada. El proceso termina en «Incidente resuelto y cerrado» (`incidentEnd`), la variable `ticketStatus` vale CLOSED y el Portal TI muestra el ticket en CLOSED. El subproceso de resolución termina completo. | OK, 11 verificaciones | `e2e.mjs`, escenario «TI A: P3, Nivel 1, usuario confirma» |
| CP-TI-02 | Excepción | Incidente que afecta la seguridad. Mesa lo asigna a Soporte especializado. Vence el plazo de resolución mientras el especialista trabaja: el responsable del servicio revisa el caso y define un plan. El especialista resuelve; el usuario rechaza la solución y el caso vuelve a soporte; el especialista resuelve de nuevo y esta vez el usuario no responde en 24 horas, por lo que el ticket se cierra automáticamente. | Impacto Bajo, urgencia Baja, con impacto de seguridad. Grupo de atención: especializado. Primera respuesta del usuario: no se resolvió. Segunda vez: sin respuesta. | La tabla de decisión da prioridad P1 y el plazo de resolución queda a 2 horas. Al vencer el plazo, la tarea del especialista se cancela (el temporizador es interruptivo) y aparece «Revisar incidente con plazo vencido», donde el responsable ve el ticket y la prioridad P1; el campo «Nuevo responsable» no trae valor por defecto. Tras el rechazo del usuario el Portal TI muestra el ticket en OPEN. La espera de confirmación queda programada a 24 horas y, al vencer, el proceso termina en «Ticket cerrado por falta de respuesta» (`autoCloseEnd`). Exactamente una instancia de resolución quedó interrumpida por el plazo vencido. | OK, 19 verificaciones | `e2e.mjs`, escenario «TI B: P1 por seguridad, vence el plazo, usuario rechaza y luego no responde» |
| CP-TI-03 | Camino principal (por los portales) | El mismo camino de CP-TI-01, pero cada persona con su sesión: `USR-01` reporta en el Portal TI; `mesa1` encuentra la tarea en su bandeja de CentralSys, la reclama, abre el formulario y la completa; `soporte1` registra la solución; `USR-01` la ve en el Portal TI y la confirma. | Sistema «Software del vehículo», descripción «La cámara de reversa no carga», impacto Medio, urgencia Media, 1 equipo, sin impacto de seguridad. | El Portal TI genera una clave `TI-…`. `mesa1` ve en el formulario P3 y PT24H, el ticket creado y la descripción del reporte. `USR-01` ve el ticket en RESOLVED, con opción de confirmar, y con la causa y la solución que escribió `soporte1`. Tras confirmar, el proceso termina en «Incidente resuelto y cerrado» (`incidentEnd`), el ticket queda en CLOSED y ya no pide confirmación. Confirmar por segunda vez un ticket cerrado se rechaza con el código 409. | OK, 17 verificaciones | `portales.mjs`, escenario «TI A: USR-01 reporta, mesa1 hace el triaje, soporte1 resuelve y USR-01 confirma» |
| CP-TI-04 | Camino alterno | `USR-01` rechaza la primera solución desde el Portal TI. El caso vuelve a `soporte1`, que registra una segunda solución; `USR-01` la confirma y el ticket se cierra. | Descripción «El tablero no muestra la presión de las llantas». Primera respuesta: no se resolvió. Segunda solución: «Sensor recalibrado en taller». Segunda respuesta: confirmada. | Tras el rechazo el ticket deja de pedir confirmación y no queda cerrado. La segunda solución llega al mismo ticket, que vuelve a RESOLVED con opción de confirmar. Tras confirmar, el proceso termina en «Incidente resuelto y cerrado» (`incidentEnd`) y el ticket queda en CLOSED. Una confirmación repetida se rechaza con 409. | OK, 24 verificaciones | `portales.mjs`, escenario «TI B: USR-01 rechaza la solución, soporte1 resuelve de nuevo y USR-01 confirma» |

### 2.2 Gestión de nómina

En todos los casos la liquidación es de 1.000.000 (valor simulado fijo) y el periodo es `2026-11-Q2`, quincenal, con fecha de corte 2026-11-25. Las novedades del periodo son las de los empleados `EMP-001` y `EMP-002`; la novedad fuera de corte es la de `EMP-003`.

| Caso | Tipo | Qué recorre | Datos de entrada | Resultado esperado | Resultado obtenido | Prueba que lo automatiza |
|---|---|---|---|---|---|---|
| CP-NOM-01 | Alterno y excepción | Ciclo sin excepciones de periodo, pero con todas las devoluciones posibles. El analista rechaza la novedad de `EMP-002`; Talento Humano la corrige y el analista la valida. El analista marca la liquidación como inconsistente y se recalcula. Jefatura devuelve la liquidación y luego la aprueba. Tesorería prepara el lote con una cuenta inválida. El Banco no responde en 2 horas; Tesorería gestiona y reenvía. El Banco rechaza la cuenta; Tesorería la corrige y el Banco acepta. | Sin casillas de simulación. Cuenta de origen inicial: `cuenta-invalida`. Código de rechazo del Banco: `R03-CUENTA-INVALIDA`. Cuenta corregida: `000-123`. | Talento Humano ve el empleado y la observación del analista. El analista y Jefatura ven el resumen y el total 1.000.000. La espera al Banco queda programada a 2 horas; al vencer, Tesorería ve el código `SIN-RESPUESTA-2H` y no un código de rechazo. Tras el rechazo ve `R03-CUENTA-INVALIDA`, y el campo de la cuenta corregida es editable y obligatorio. El Banco recibe el lote, el total y la cuenta `000-123`. El proceso termina en «Nómina pagada y comprobantes emitidos» (`payrollEnd`) con una referencia bancaria que empieza por `BANCO-`. | OK, 27 verificaciones | `e2e.mjs`, escenario «Nómina A: CONTINUAR, novedad corregida, recálculo, devolución de Jefatura, timeout y rechazo bancario» |
| CP-NOM-02 | Excepción | El periodo está cerrado: el ciclo se devuelve a Talento Humano, que solicita la reapertura. Con el periodo reabierto, el ciclo sigue la ruta normal hasta el pago. | Casilla «Simular: periodo cerrado». Acción de Talento Humano: reabrir el periodo. Cuenta `000-123`. | Talento Humano ve el motivo `REQUIERE_REAPERTURA` que dio la tabla de decisión. Se validan 2 novedades. El proceso termina en «Nómina pagada y comprobantes emitidos» (`payrollEnd`) con referencia `BANCO-…`. Se crearon exactamente 2 validaciones de novedad. | OK, 17 verificaciones | `e2e.mjs`, escenario «Nómina B: periodo cerrado y reapertura» |
| CP-NOM-03 | Excepción | Faltan soportes de una novedad: el ciclo se devuelve a Talento Humano, que completa los soportes. Después sigue la ruta normal hasta el pago. | Casilla «Simular: soportes incompletos». Acción de Talento Humano: completar soportes. Cuenta `000-123`. | Talento Humano ve el motivo `SOPORTE_INCOMPLETO`. Se validan 2 novedades. Fin en `payrollEnd` con referencia `BANCO-…`. Se crearon exactamente 2 validaciones de novedad. | OK, 17 verificaciones | `e2e.mjs`, escenario «Nómina C: soportes incompletos» |
| CP-NOM-04 | Excepción | Hay una novedad registrada después de la fecha de corte: Jefatura debe decidir y la rechaza. Talento Humano retira la novedad tardía y el ciclo continúa con las novedades normales. | Casilla «Simular: novedades fuera de corte». Decisión de Jefatura: no aprobar. | Jefatura ve el resumen de la novedad fuera de corte, que menciona a `EMP-003`. Tras la corrección de Talento Humano se validan 2 novedades. Fin en `payrollEnd` con referencia `BANCO-…`. Se crearon exactamente 2 validaciones de novedad. | OK, 18 verificaciones | `e2e.mjs`, escenario «Nómina D: novedad fuera de corte rechazada por Jefatura» |
| CP-NOM-05 | Camino alterno | Novedad fuera de corte que Jefatura aprueba: se valida junto con las demás, cada una en su propia instancia del subproceso. | Casilla «Simular: novedades fuera de corte». Decisión de Jefatura: aprobar. | Se validan 3 novedades de empleados distintos. Fin en `payrollEnd` con referencia `BANCO-…`. Se crearon exactamente 3 validaciones de novedad (multiinstancia). | OK, 18 verificaciones | `e2e.mjs`, escenario «Nómina E: novedad fuera de corte aprobada (3 novedades en multiinstancia)» |
| CP-NOM-06 | Camino principal (por los portales) | `nomina1` inicia el ciclo en CentralSys y valida las dos novedades. Revisa la liquidación. `jefatura1` la aprueba. `tesoreria1` prepara el lote. El Banco acepta la dispersión. | Sin casillas de simulación. Cuenta de origen `000-123`. | CentralSys genera una clave `NOM-2026-11-Q2-…`. `nomina1` valida 2 novedades de empleados distintos y ve el resumen y el total 1.000.000; `jefatura1` y `tesoreria1` ven el mismo total. La dispersión queda ACEPTADA en el Banco con el lote, el total y la cuenta `000-123`. El proceso termina en `payrollEnd` y guarda una referencia `BANCO-…`. La «actividad reciente» de `nomina1` (máximo 20 acciones) registra lo que hizo en este ciclo. | OK, 33 verificaciones | `portales.mjs`, escenario «Nómina F: novedades, liquidación, aprobación, lote y Banco acepta» |
| CP-NOM-07 | Excepción | Igual que CP-NOM-06 hasta el lote, pero Tesorería escribe una cuenta con formato inválido. El Banco la rechaza. `tesoreria1` recibe la tarea de corrección, corrige la cuenta y el Banco acepta el nuevo envío. | Cuenta de origen `123`. Código de rechazo `R03-CUENTA-INVALIDA`. Cuenta corregida `000-123`. | La dispersión de la cuenta `123` queda RECHAZADA en el Banco. `tesoreria1` ve el código de rechazo, y el formulario exige la cuenta corregida como campo obligatorio. Fin en `payrollEnd` con referencia `BANCO-…`. En el Banco queda exactamente una dispersión ACEPTADA, con la cuenta `000-123`, y se conserva la RECHAZADA con la cuenta original. | OK, 39 verificaciones | `portales.mjs`, escenario «Nómina G: el Banco rechaza la cuenta, Tesorería la corrige y el Banco acepta» |

### 2.3 Postventa y garantías

Los vehículos de muestra son `CB0TEST0000000001` (con campaña activa) y `WDB0000000000000B`, `WDB0000000000000C` y `WDB0000000000000D` (sin campaña; la garantía los cubre hasta 60.000 km). La cotización simulada es siempre de 850.000.

| Caso | Tipo | Qué recorre | Datos de entrada | Resultado esperado | Resultado obtenido | Prueba que lo automatiza |
|---|---|---|---|---|---|---|
| CP-PV-01 | Camino principal con una repetición | Solicitud de un vehículo con campaña activa. El asesor la registra. La tabla de decisión reconoce la campaña y el analista aprueba la garantía. En paralelo se reservan los repuestos y se agenda la cita. En el taller se diagnostica y se repara; la primera inspección de calidad no pasa y se repite la reparación; la segunda pasa. Se entrega el vehículo. | VIN `CB0TEST0000000001`, 15.000 km. Garantía aprobada. Sin repuesto adicional. Inspecciones: no aprobada y luego aprobada. Satisfacción 5. | El asesor ve el VIN y el síntoma del cliente. El formulario del analista muestra `CAMPANA_ACTIVA` y su motivo. La agenda muestra el taller asignado. El taller recibe VIN, síntoma y kilometraje. La entrega muestra el trabajo y el costo que devolvió el taller, y limita la satisfacción a 5. El proceso termina en «Vehículo entregado y caso cerrado» (`closedEnd`), con la satisfacción 5 registrada y la campaña `RECALL-CAMARA-2026` consultada en Taller-Bodega. | OK, 21 verificaciones | `e2e.mjs`, escenario «Postventa A: recall activo, garantía aprobada, AND, taller con una inspección fallida» |
| CP-PV-02 | Camino alterno | Vehículo fuera de garantía por kilometraje: se envía una cotización al cliente, que la rechaza. El caso se cierra sin reparación y se notifica al cliente. | VIN `WDB0000000000000B`, 80.000 km. Respuesta del cliente: rechaza. | El proceso termina en «Reparación no autorizada» (`unauthorizedEnd`). La cobertura quedó en `REQUIERE_COTIZACION`. Taller-Bodega muestra la cotización como RECHAZADA; su número empieza por `COT-` y su valor es 850.000. | OK, 7 verificaciones | `e2e.mjs`, escenario «Postventa B: fuera de garantía, cotización rechazada» |
| CP-PV-03 | Camino alterno | La regla dice que la garantía cubre el vehículo, pero el analista no la aprueba: se cotiza. El cliente acepta. En el taller el técnico pide un repuesto adicional a la bodega y continúa cuando llega. | VIN `WDB0000000000000C`, 20.000 km. Garantía no aprobada. Respuesta del cliente: acepta. Repuesto adicional `CAM-123`. | El formulario del analista muestra `CUBIERTO_POR_GARANTIA`. Taller-Bodega muestra la cotización como ACEPTADA. La bodega recibe la solicitud del repuesto `CAM-123`, la entrega y avisa al proceso. Fin en «Vehículo entregado y caso cerrado» (`closedEnd`). | OK, 18 verificaciones | `e2e.mjs`, escenario «Postventa C: garantía no aprobada, cotización aceptada, repuesto adicional» |
| CP-PV-04 | Excepción | El cliente no responde la cotización: vence el plazo de 48 horas y el caso se cierra sin reparación. | VIN `WDB0000000000000D`, 70.000 km. Sin respuesta del cliente. | El plazo de la cotización queda programado a 48 horas. Al vencer, el proceso termina en «Reparación no autorizada» (`unauthorizedEnd`) y Taller-Bodega muestra la cotización como VENCIDA. | OK, 6 verificaciones | `e2e.mjs`, escenario «Postventa D: cotización sin respuesta, vence el timer de 48 h» |
| CP-PV-05 | Excepción | La bodega no tiene los repuestos al reservar. El proceso espera el aviso de la bodega; el taller no empieza mientras tanto. Cuando la bodega avisa que hay repuestos, el caso continúa hasta la entrega. | Bodega sin repuestos disponibles. VIN `CB0TEST0000000001`, 15.000 km. Garantía aprobada. | La reserva queda PENDIENTE y el proceso queda esperando el mensaje de la bodega. No existe tarea de diagnóstico mientras falta el repuesto. Al avisar, la reserva pasa a RESERVED; repetir el aviso no cambia nada (es idempotente). Fin en `closedEnd`. | OK, 18 verificaciones | `e2e.mjs`, escenario «Postventa E: Bodega avisa cuando el repuesto vuelve a estar disponible» |
| CP-PV-06 | Camino alterno (por los portales) | `cliente1` crea la solicitud en el Portal del cliente. `asesor1` la registra. Como el vehículo está fuera de garantía, `cliente1` ve la cotización y la acepta. `coordinador1` agenda. `tecnico1` diagnostica y pide un repuesto. `bodega1` lo entrega desde el panel de bodega. `tecnico1` repara, `calidad1` inspecciona y `asesor1` entrega. | VIN `WDB0000000000000B`, 80.000 km, tipo Reparación. Respuesta: acepta. Repuesto `CAM-123`. | El portal genera una clave `PV-…`. `cliente1` ve la cotización por 850.000, la cobertura `REQUIERE_COTIZACION` y el taller asignado; responder dos veces se rechaza con 409. `bodega1` ve la solicitud del repuesto `CAM-123` y, tras entregarla, queda ENTREGADA. El proceso termina en `closedEnd` y guarda a `cliente1` como dueño de la solicitud. `cliente1` ve el caso terminado, con el trabajo realizado, la cita y la orden de taller. | OK, 44 verificaciones | `portales.mjs`, escenario «Postventa C: cotización aceptada, repuesto adicional entregado por bodega1» |
| CP-PV-07 | Camino principal (por los portales) | `cliente2` crea la solicitud de un vehículo en campaña. `asesor1` registra, `garantia1` aprueba la garantía, `coordinador1` agenda, `tecnico1` diagnostica sin repuesto adicional y repara, `calidad1` aprueba la inspección y `asesor1` entrega. | VIN `CB0TEST0000000001`, 15.000 km, tipo Garantía. | `garantia1` ve la cobertura `CAMPANA_ACTIVA` y su motivo. El proceso termina en `closedEnd` y guarda a `cliente2` como dueño. `cliente2` ve el caso terminado con el trabajo, la cita y la orden. `bodega1` ve la reserva de repuestos del caso en RESERVED y ninguna solicitud de repuesto adicional. `cliente1` no ve la solicitud de `cliente2`. | OK, 46 verificaciones | `portales.mjs`, escenario «Postventa D: garantía por campaña aprobada, sin repuesto adicional» |
| CP-PV-08 | Camino alterno (por los portales) | `cliente1` crea la solicitud, `asesor1` la registra y `cliente1` rechaza la cotización desde el portal. | VIN `WDB0000000000000B`, 80.000 km. Respuesta: rechaza. | El proceso termina en «Reparación no autorizada» (`unauthorizedEnd`). `cliente1` ve la cotización rechazada y el caso cerrado sin reparación: la solicitud no pasó por el taller y ya no espera respuestas. Responder dos veces se rechaza con 409. | OK, 14 verificaciones | `portales.mjs`, escenario «Postventa E: el cliente rechaza la cotización» |

## 3. Casos transversales

### 3.1 Motor

| Caso | Tipo | Qué recorre | Datos de entrada | Resultado esperado | Resultado obtenido | Prueba que lo automatiza |
|---|---|---|---|---|---|---|
| CP-MOT-01 | Camino alterno de inicio | Los sistemas externos responden al motor por la clave del caso, así que ninguna instancia puede quedar sin ella. Se inicia cada proceso sin clave, como ocurre al iniciarlo desde Tasklist. | Incidente, nómina y postventa iniciados sin clave. Postventa se inicia de dos formas: por mensaje y por su formulario de inicio. | Cada instancia recibe una clave automática con el prefijo de su proceso y ocho caracteres: `TI-…`, `NOM-…` y `PV-…`. Postventa tiene desplegado el formulario de inicio `postventa-solicitud`. Las instancias creadas se borran al terminar. | OK, 5 verificaciones | `e2e.mjs`, escenario «Motor: businessKey automática al iniciar sin clave (TI, Nómina y Postventa)» |

### 3.2 Seguridad y permisos (`permisos.mjs`)

La suite informó «OK: 73 verificaciones de sesión, CSRF, permisos por grupo, aislamiento entre clientes y portales, y APIs internas protegidas». Como se detiene en la primera verificación que no se cumpla, ese resultado vale para todos los casos de esta tabla; en cada uno se indica cuántas de las 73 verificaciones le corresponden según el código de la suite.

| Caso | Tipo | Qué recorre | Datos de entrada | Resultado esperado | Resultado obtenido | Prueba que lo automatiza |
|---|---|---|---|---|---|---|
| CP-SEG-01 | Excepción | Acceso sin sesión, inicio de sesión con contraseña incorrecta e inicio de sesión sin el token contra falsificación de peticiones (CSRF). Al final, la cuenta usada para el intento fallido vuelve a entrar con su contraseña. | Sin sesión en los tres portales. Cuenta `talento1` con una contraseña equivocada. Cuenta `mesa1` sin token CSRF. | Sin sesión, las API de los tres portales responden 401 con el mensaje «Inicia sesión». La contraseña incorrecta responde 401 con «Usuario o contraseña incorrectos» y no abre sesión. El inicio de sesión sin token CSRF responde 403. Pasado el bloqueo temporal, `talento1` entra con su contraseña. | OK (10 de las 73 verificaciones) | `permisos.mjs`, funciones `sesionYCsrf` y `entrarTrasElBloqueo` |
| CP-SEG-02 | Principal y excepción | Perfil de un empleado con sesión, y aislamiento de la sesión entre portales (el navegador comparte las cookies de `localhost` entre puertos). | `mesa1` en CentralSys y en el Portal TI. | `mesa1` aparece con su grupo `mesa-servicio`, sin perfil de cliente ni de administrador. Cada portal usa su propia cookie de sesión. La sesión de CentralSys no sirve en el Portal TI ni en el Portal del cliente, y la del Portal TI no sirve en CentralSys: en los tres casos la respuesta es 401. | OK (10 de 73) | `permisos.mjs`, funciones `empleadoDeMesa` y `sesionNoSirveEnOtroPortal` |
| CP-SEG-03 | Principal y excepción | Permisos por grupo sobre una tarea de Mesa de Servicio: quién la ve, quién la reclama, quién lee su formulario, quién la suelta y quién la completa. | Tarea de triaje de un incidente. `mesa1` (grupo correcto) y `responsable1` (otro grupo). | La tarea llega sin asignar y con su grupo candidato. `responsable1` no la ve en su bandeja y recibe 403 al reclamarla, al leer su formulario, al soltarla y al completarla. `mesa1` la reclama, queda asignada a su nombre y lee el formulario de triaje. | OK (12 de 73) | `permisos.mjs`, función `permisosSobreTareas` |
| CP-SEG-04 | Excepción | Una escritura sin token CSRF se rechaza aunque la sesión y el permiso sean correctos, también si la ruta se escribe con un carácter codificado. | `mesa1` intenta reclamar su tarea sin token, por la ruta normal y por la ruta `/api/%61pp`. | Las dos peticiones responden 403. | OK (2 de 73) | `permisos.mjs`, función `permisosSobreTareas` |
| CP-SEG-05 | Excepción | Al completar una tarea, el servidor solo acepta los campos editables de su formulario: una persona no puede cambiar desde el navegador un dato de solo lectura ni agregar variables. | `mesa1` completa el triaje enviando, además del grupo de atención, una prioridad `P1` (campo de solo lectura) y una variable que no existe en el formulario. | La tarea se completa. La prioridad del proceso sigue siendo la que calculó la tabla de decisión y no `P1`. La variable ajena no llega al proceso. El grupo de atención sí queda guardado. | OK (6 de 73) | `permisos.mjs`, función `permisosSobreTareas` |
| CP-SEG-06 | Excepción | Acciones y portales reservados a un perfil. | `mesa1` intenta iniciar la nómina. `cliente1` entra a CentralSys; `cliente2` entra al Portal TI e intenta reportar un incidente; `mesa1` entra al Portal del cliente. | Iniciar la nómina sin ser analista de nómina responde 403. Un cliente recibe 403 en la bandeja de CentralSys, en los tickets del Portal TI y al reportar un incidente. Un empleado recibe 403 en las solicitudes del Portal del cliente. | OK (5 de 73) | `permisos.mjs`, funciones `permisosSobreTareas` y `perfilesPorPortal` |
| CP-SEG-07 | Principal y excepción | Aislamiento entre clientes: cada cliente ve y responde solo sus solicitudes. | `cliente1` crea una solicitud; `cliente2` intenta verla y responderla; `cliente1` intenta responder antes de que exista la cotización. | `cliente1` tiene perfil de cliente, crea la solicitud (clave `PV-…`) y la ve en su lista con su instancia y sus variables. `cliente2` no la ve y, al intentar responderla, recibe 404. Responder cuando el proceso aún no espera la cotización responde 409. | OK (11 de 73) | `permisos.mjs`, función `aislamientoEntreClientes` |
| CP-SEG-08 | Principal y excepción | El panel de bodega es solo para el grupo `bodega` y para el administrador. | `bodega1`, `asesor1` y `demo`. | `bodega1` consulta las reservas. `asesor1` recibe 403 al consultarlas y al intentar cambiar la simulación de la bodega. `demo` tiene perfil de administrador y también las consulta. | OK (6 de 73) | `permisos.mjs`, función `permisosDeBodega` |
| CP-SEG-09 | Camino principal | Cierre de sesión. | `mesa1` cierra su sesión. | El cierre responde 204 sin contenido y, a partir de ahí, la bandeja responde 401. | OK (2 de 73) | `permisos.mjs`, función `cierreDeSesion` |
| CP-SEG-10 | Excepción | Las API entre sistemas exigen la credencial interna; tener sesión en un portal no la sustituye. | Peticiones sin credencial al motor, a la API de tickets del Portal TI, al Banco y a Taller-Bodega. `soporte1` con sesión en el Portal TI llama a la API interna de tickets. | Los cuatro sistemas responden 401 con el mensaje «Credencial interna requerida». La llamada con sesión, pero sin credencial, también responde 401. | OK (9 de 73) | `permisos.mjs`, función `apisInternasProtegidas` |

### 3.3 Cuentas e identidades (`identidades.mjs`)

La suite informó «OK: 18 cuentas de demostración (14 empleados con su grupo, 2 clientes, USR-01 y demo) y API de Camunda protegida». Esta suite no imprime un número de verificaciones; se detiene en la primera que no se cumpla, así que su `OK` vale para los cuatro casos.

| Caso | Tipo | Qué recorre | Datos de entrada | Resultado esperado | Resultado obtenido | Prueba que lo automatiza |
|---|---|---|---|---|---|---|
| CP-ID-01 | Camino principal | Las cuentas de demostración que el motor crea al arrancar. | Los 14 empleados (`mesa1`, `soporte1`, `especialista1`, `responsable1`, `nomina1`, `talento1`, `jefatura1`, `tesoreria1`, `asesor1`, `garantia1`, `coordinador1`, `tecnico1`, `calidad1`, `bodega1`) y los 2 clientes (`cliente1`, `cliente2`). | Cada cuenta autentica con la contraseña de demostración, pertenece exactamente a su grupo (ni más ni menos) y tiene el correo `<cuenta>@corocorabenz.test`. | OK | `identidades.mjs` |
| CP-ID-02 | Camino principal | Las dos cuentas especiales. | `USR-01` y `demo`. | `USR-01` autentica con su propia contraseña, no pertenece a ningún grupo y tiene su correo de demostración. `demo` autentica y pertenece al grupo `camunda-admin`. | OK | `identidades.mjs` |
| CP-ID-03 | Excepción | Contraseña incorrecta y bloqueo temporal de la cuenta. | `cliente2` con una contraseña equivocada y luego con la correcta. | La contraseña incorrecta no autentica. Pasado el bloqueo temporal, `cliente2` vuelve a autenticar con la correcta. | OK | `identidades.mjs` |
| CP-ID-04 | Excepción | El motor solo responde a quien presenta la credencial interna. | Peticiones a la REST de Camunda y a `/api/demo` sin credencial, con una credencial equivocada y con la credencial como `Authorization: Bearer`. | Sin credencial o con una equivocada, la respuesta es 401 con «Credencial interna requerida». Como `Bearer` se acepta. La descripción OpenAPI que usa Swagger es pública. El motor no devuelve cabeceras CORS, porque ningún navegador lo llama directamente. | OK | `identidades.mjs` |

### 3.4 Validaciones y reintentos (`regresion.mjs`)

La suite informó «OK: credencial interna, validación, transición de ticket y reintentos de cotización y dispersión». Tampoco imprime un número de verificaciones y se detiene en la primera que no se cumpla. Un «reintento» es la misma petición enviada dos veces, como ocurre cuando el motor repite una llamada que no supo si había llegado; «idempotente» significa que el reintento no crea un duplicado.

| Caso | Tipo | Qué recorre | Datos de entrada | Resultado esperado | Resultado obtenido | Prueba que lo automatiza |
|---|---|---|---|---|---|---|
| CP-REG-01 | Excepción | Credencial interna en el Portal TI, la Bodega y el Banco. | Peticiones sin credencial, con credencial equivocada y como `Bearer`. | Sin credencial, los tres responden 401 con «Credencial interna requerida». Una credencial equivocada se rechaza. Como `Bearer` se acepta. | OK | `regresion.mjs` |
| CP-REG-02 | Principal y excepción | Creación del ticket de un incidente y reintento. | Ticket incompleto; ticket completo enviado dos veces; el mismo ticket con otra descripción. | El ticket incompleto responde 400. El ticket completo se crea. El reintento devuelve el mismo número de ticket (no lo duplica). La misma clave con datos distintos responde 409. | OK | `regresion.mjs` |
| CP-REG-03 | Excepción | Transiciones de estado del ticket. | Cerrar el ticket; intentar registrar una solución después; cerrar un ticket que no existe. | Al cerrarlo queda en CLOSED. Un ticket cerrado no admite una solución nueva (409). Un ticket inexistente responde 404. | OK | `regresion.mjs` |
| CP-REG-04 | Excepción | Consulta de vehículos en Taller-Bodega. | VIN demasiado corto; kilometraje negativo; VIN bien formado que no está en el catálogo. | El VIN corto y el kilometraje negativo responden 400. El VIN desconocido responde que no está registrado y que no tiene garantía. | OK | `regresion.mjs` |
| CP-REG-05 | Principal y excepción | Cotizaciones en Taller-Bodega. | Cotización incompleta; la misma cotización enviada dos veces; la misma clave con otro síntoma. | La incompleta responde 400. El reintento devuelve el mismo número de cotización. El contenido incompatible responde 409. | OK | `regresion.mjs` |
| CP-REG-06 | Camino principal | Dispersión en el Banco. | El mismo lote enviado dos veces. | El reintento devuelve la misma dispersión (no la duplica). | OK | `regresion.mjs` |

### 3.5 Comprobaciones manuales en el navegador

Las suites automatizadas usan las API de los portales, no hacen clic en las páginas. Estas comprobaciones se hicieron en el navegador el 19 de noviembre de 2026, con los sistemas sobre PostgreSQL. PM-01 a PM-03 son acciones sueltas. PM-04 a PM-08 son el ensayo completo de `docs/GUION-DEMOSTRACION.md`, en el que los botones y los campos de las páginas se accionaron con un script dentro del navegador, no con el ratón; al terminar, el motor no tenía incidentes.

| Comprobación | Dónde y con qué cuenta | Qué se hizo | Resultado esperado | Resultado obtenido |
|---|---|---|---|---|
| PM-01 | Portal TI (`http://localhost:9002`), `USR-01` | Enviar un reporte de incidente con el formulario de la página. | Mensaje de confirmación con la clave del caso y el ticket visible en la lista. | Apareció «Reporte enviado con la clave TI-…» y el ticket en estado «En atención». |
| PM-02 | CentralSys (`http://localhost:9003`), `mesa1` | Abrir la tarea «Validar clasificación y asignar grupo» con «Reclamar y abrir» y completarla con «Completar tarea». | El formulario muestra la prioridad y el plazo calculados por la tabla de decisión, y la tarea se completa. | El formulario cargó con prioridad P3 y plazo PT24H, y al completar apareció «Tarea completada.». |
| PM-03 | Portal del cliente (`http://localhost:9001`), `cliente1` | Consultar las solicitudes anteriores y enviar una nueva. | El cliente ve sus solicitudes con su estado, y la nueva recibe un número de caso. | `cliente1` vio sus solicitudes anteriores con su estado y, al enviar una nueva, apareció «Solicitud enviada. Tu número de caso es PV-…». |
| PM-04 | Portal TI y CentralSys, con `USR-01`, `mesa1`, `soporte1`, `especialista1` y `responsable1` | Secciones 2.1 a 2.4 del guion: camino principal, rechazo de la solución, incidente de seguridad y los dos plazos vencidos. | Cada caso llega al fin previsto y las pantallas muestran lo que indica el guion. | Los cuatro incidentes terminaron cerrados. El de seguridad mostró P1 y PT2H y llegó a `especialista1`. Tras el plazo vencido, `soporte1` recibió la tarea «Asignada a mí». |
| PM-05 | CentralSys, con `nomina1`, `talento1`, `jefatura1` y `tesoreria1` | Secciones 3.1 a 3.5: camino principal, las tres casillas de simulación, las devoluciones, el rechazo del Banco y el Banco sin respuesta. | El Banco acepta o rechaza solo según la cuenta; cada excepción deja la tarea a quien corresponde. | El ciclo principal terminó pagado (`00-APROBADA`). Las casillas dejaron 2, 2 y 3 novedades. La cuenta `123` produjo `R03-CUENTA-INVALIDA` y, corregida, el pago. El plazo del Banco mostró `SIN-RESPUESTA-2H`. |
| PM-06 | Portal del cliente y CentralSys, con `cliente1`, `asesor1`, `garantia1`, `coordinador1`, `tecnico1`, `calidad1` y `bodega1` | Secciones 4.1 a 4.5: seis solicitudes, una por cada camino. | Cuatro vehículos entregados, una cotización rechazada y una vencida. | Así terminaron. La bodega entregó a mano el repuesto `CAM-123` y avisó la disponibilidad de dos reservas pendientes. |
| PM-07 | Terminal de PowerShell y Cockpit, con `demo` | Sección 5: vencer `slaTimer`, `noResponse`, `bankTimeout` y `quoteExpired` con la orden de PowerShell, y `slaTimer` cambiando la fecha en la pestaña «Jobs» de Cockpit. | Cada temporizador lleva el caso por su camino de excepción. | Los cuatro funcionaron con la orden. Con Cockpit, el motor ejecutó el temporizador en cerca de un minuto. La orden que traía el guion fallaba en Windows PowerShell 5.1 y se corrigió. |
| PM-08 | Los seis sistemas, los tres portales y Cockpit | Sección 6: detener y volver a arrancar todo con casos a medias. | Los mismos datos antes y después. | Coincidieron las instancias y su actividad, las tareas, los temporizadores, el historial y las listas de los portales. Una tarea pendiente se completó después del reinicio. |

## 4. Matriz de cobertura

Para cada proceso se listan los elementos del diagrama BPMN que definen un camino (cada salida de compuerta, cada temporizador, cada mensaje y cada fin) y los casos que pasan por ellos. Los subprocesos reutilizables se incluyen dentro de su proceso principal.

### 4.1 Gestión de incidentes de TI (`CB_GestionIncidenteTI` y `CB_ResolverIncidenteTI`)

| Elemento del diagrama | Camino | Casos que lo cubren |
|---|---|---|
| Inicio «Incidente de TI reportado» | Reporte de un incidente | CP-TI-01, CP-TI-02 (formulario de inicio); CP-TI-03, CP-TI-04 (Portal TI); CP-MOT-01 (sin clave) |
| Tabla de decisión «Clasificar prioridad y plazo» | Seguridad afectada: P1, 2 horas | CP-TI-02 |
| Tabla de decisión «Clasificar prioridad y plazo» | Resto de casos: P3, 24 horas | CP-TI-01, CP-TI-03, CP-TI-04 |
| Tabla de decisión «Clasificar prioridad y plazo» | Impacto Alto con urgencia Alta (P1), e impacto Alto, urgencia Alta o más de un equipo afectado (P2, 8 horas) | **Ninguno** (ver sección 4.4) |
| Compuerta «¿Dentro del alcance de Nivel 1?» | Sí: «Diagnosticar y resolver» (Nivel 1) | CP-TI-01, CP-TI-03, CP-TI-04 |
| Compuerta «¿Dentro del alcance de Nivel 1?» | No: «Analizar causa raíz y resolver» (especializado) | CP-TI-02 |
| Temporizador «Plazo de resolución vencido» (interrumpe la resolución) | Vence el plazo: «Revisar incidente con plazo vencido» y vuelta a resolución | CP-TI-02 (duración de 2 horas comprobada y temporizador vencido); CP-TI-01 comprueba la duración de 24 horas sin vencerlo |
| Evento «Solución notificada al usuario» | Notificación al usuario | Se ejecuta en CP-TI-01 a CP-TI-04; su efecto se revisó en el registro del motor (sección 6) |
| Compuerta «¿Solución confirmada?» | Sí: «Cerrar ticket verificado» | CP-TI-01, CP-TI-03, CP-TI-04 |
| Compuerta «¿Solución confirmada?» | No, reabrir: vuelta a resolución | CP-TI-02, CP-TI-04 |
| Temporizador «Sin respuesta en 24 h» | Notificar y cerrar por falta de respuesta | CP-TI-02 (duración de 24 horas comprobada y temporizador vencido) |
| Fin «Incidente resuelto y cerrado» | | CP-TI-01, CP-TI-03, CP-TI-04 |
| Fin «Ticket cerrado por falta de respuesta» | | CP-TI-02 |
| Fin «Solución registrada» (subproceso) | | CP-TI-01 a CP-TI-04 |

### 4.2 Gestión de nómina (`CB_GestionNomina` y `CB_ValidarNovedadEmpleado`)

| Elemento del diagrama | Camino | Casos que lo cubren |
|---|---|---|
| Inicio «Ciclo de pago iniciado» | Inicio del ciclo | CP-NOM-01 a CP-NOM-05 (formulario de inicio); CP-NOM-06, CP-NOM-07 (CentralSys); CP-MOT-01 (sin clave) |
| Compuerta «¿Qué resultado aplica?» | Continuar | CP-NOM-01, CP-NOM-06, CP-NOM-07; y CP-NOM-02, CP-NOM-03 y CP-NOM-04 después de la corrección |
| Compuerta «¿Qué resultado aplica?» | Periodo cerrado o faltan soportes: «Corregir novedades o reabrir periodo» | CP-NOM-02 (periodo cerrado), CP-NOM-03 (soportes incompletos) |
| Compuerta «¿Qué resultado aplica?» | Novedades fuera de corte: «Aprobar novedades fuera de corte» | CP-NOM-04, CP-NOM-05 |
| Compuerta «¿Novedades aprobadas?» | Sí | CP-NOM-05 |
| Compuerta «¿Novedades aprobadas?» | No: corrección de Talento Humano | CP-NOM-04 |
| Multiinstancia «Validar cada novedad de empleado» | Dos novedades | CP-NOM-01 a CP-NOM-04, CP-NOM-06, CP-NOM-07 |
| Multiinstancia «Validar cada novedad de empleado» | Tres novedades | CP-NOM-05 |
| Compuerta «¿Novedad válida?» (subproceso) | Sí | Todos los casos de nómina |
| Compuerta «¿Novedad válida?» (subproceso) | No: «Corregir novedad y adjuntar soporte» y nueva revisión | CP-NOM-01 |
| Compuerta «¿Liquidación consistente?» | Sí | Todos los casos de nómina |
| Compuerta «¿Liquidación consistente?» | No, recalcular | CP-NOM-01 |
| Compuerta «¿Liquidación aprobada?» | Sí | Todos los casos de nómina |
| Compuerta «¿Liquidación aprobada?» | No, devolver | CP-NOM-01 |
| Compuerta de eventos «Respuesta del Banco» | Mensaje «Dispersión aceptada» | Todos los casos de nómina |
| Compuerta de eventos «Respuesta del Banco» | Mensaje «Dispersión rechazada»: «Corregir lote o consultar al Banco» y reenvío | CP-NOM-01, CP-NOM-07 |
| Compuerta de eventos «Respuesta del Banco» | Temporizador «Sin respuesta en 2 h»: corrección con el código `SIN-RESPUESTA-2H` y reenvío | CP-NOM-01 (duración de 2 horas comprobada y temporizador vencido) |
| Fin «Nómina pagada y comprobantes emitidos» | | Todos los casos de nómina |
| Fin «Novedad validada» (subproceso) | | Todos los casos de nómina |

### 4.3 Postventa y garantías (`CB_PostventaGarantias` y `CB_AtenderOrdenTaller`)

| Elemento del diagrama | Camino | Casos que lo cubren |
|---|---|---|
| Inicio «Solicitud de servicio recibida» | Por el mensaje del Portal del cliente | CP-PV-01 a CP-PV-05 (mensaje directo); CP-PV-06 a CP-PV-08 (Portal del cliente) |
| Inicio «Solicitud de servicio recibida» | Por el formulario de inicio de Tasklist | CP-MOT-01 (solo el inicio y la clave; ver sección 4.4) |
| Compuerta «¿Qué cobertura aplica?» | Campaña o garantía: «Revisar cobertura y evidencia» | CP-PV-01, CP-PV-05, CP-PV-07 (campaña); CP-PV-03 (garantía) |
| Compuerta «¿Qué cobertura aplica?» | Requiere cotización | CP-PV-02, CP-PV-04, CP-PV-06, CP-PV-08 |
| Compuerta «¿Garantía aprobada?» | Sí | CP-PV-01, CP-PV-05, CP-PV-07 |
| Compuerta «¿Garantía aprobada?» | No: cotización | CP-PV-03 |
| Compuerta de eventos «Respuesta a la cotización» | Mensaje «Cotización aceptada» | CP-PV-03, CP-PV-06 |
| Compuerta de eventos «Respuesta a la cotización» | Mensaje «Cotización rechazada» | CP-PV-02, CP-PV-08 |
| Compuerta de eventos «Respuesta a la cotización» | Temporizador «Plazo de cotización vencido (48 h)» | CP-PV-04 (duración de 48 horas comprobada y temporizador vencido) |
| Compuerta paralela | Reservar repuestos y agendar la cita a la vez, y unión antes del taller | CP-PV-01, CP-PV-03, CP-PV-05, CP-PV-06, CP-PV-07 |
| Compuerta «¿Repuestos disponibles?» | Sí | CP-PV-01, CP-PV-03, CP-PV-06, CP-PV-07 |
| Compuerta «¿Repuestos disponibles?» | No: espera del mensaje «Repuestos disponibles» de la bodega | CP-PV-05 |
| Compuerta «¿Requiere repuesto adicional?» (taller) | Sí: solicitud a bodega y espera del mensaje «Repuesto recibido» | CP-PV-03, CP-PV-06 |
| Compuerta «¿Requiere repuesto adicional?» (taller) | No | CP-PV-01, CP-PV-05, CP-PV-07 |
| Compuerta «¿Inspección aprobada?» (taller) | Sí | CP-PV-01, CP-PV-03, CP-PV-05, CP-PV-06, CP-PV-07 |
| Compuerta «¿Inspección aprobada?» (taller) | No, repetir reparación | CP-PV-01 |
| Fin «Vehículo entregado y caso cerrado» | | CP-PV-01, CP-PV-03, CP-PV-05, CP-PV-06, CP-PV-07 |
| Fin «Reparación no autorizada» (con notificación de cierre al cliente) | | CP-PV-02, CP-PV-04, CP-PV-08 |
| Fin «Vehículo reparado e inspeccionado» (subproceso) | | CP-PV-01, CP-PV-03, CP-PV-05, CP-PV-06, CP-PV-07 |

### 4.4 Caminos que no cubre ninguna prueba automatizada

**En los diagramas BPMN no queda ninguno:** todas las salidas de compuerta, los cuatro temporizadores, todos los mensajes y todos los eventos de fin de los tres procesos y de sus tres subprocesos los recorre al menos un caso automatizado.

Lo que sí queda sin cubrir, dicho con precisión, son estas reglas y combinaciones:

1. **Cuatro reglas de la tabla de decisión de incidentes.** Los casos solo producen la prioridad P1 por impacto de seguridad y la prioridad P3. Ninguna prueba automatizada evalúa la regla de P1 por impacto Alto con urgencia Alta ni las tres reglas que dan P2 con plazo de 8 horas (impacto Alto, urgencia Alta, o más de un equipo afectado). En consecuencia, el plazo de resolución de 8 horas nunca se programa en una prueba.
2. **Plazo de resolución vencido en Nivel 1.** El temporizador solo se vence mientras trabaja el soporte especializado (CP-TI-02). Es el mismo evento del diagrama, pero la combinación con Nivel 1 no se ejecuta.
3. **Postventa iniciada desde Tasklist hasta el final.** Se prueba que el proceso arranca con su formulario de inicio y recibe su clave, pero esa instancia se borra sin llevarla hasta un fin.
4. **Garantía por campaña no aprobada.** La salida «No» de «¿Garantía aprobada?» se prueba con un vehículo cubierto por garantía ordinaria (CP-PV-03), no con uno en campaña.
5. **Varias casillas de simulación de nómina marcadas a la vez.** Cada excepción se prueba por separado.
6. **Rutas de excepción recorridas con la sesión de la persona en el portal.** Las excepciones de nómina (Talento Humano, novedades fuera de corte), el soporte especializado y la revisión del plazo vencido se prueban completando las tareas por la REST de Camunda (`e2e`), no con las sesiones de `talento1`, `especialista1` y `responsable1` en CentralSys. La suite `portales` recorre con sesiones reales los siete escenarios de la sección 1.4.

## 5. Pruebas unitarias y de integración por módulo

Se ejecutan con `.\mvnw.cmd test` y no necesitan ningún sistema arrancado: cada módulo usa una base H2 en memoria. Las pruebas del motor arrancan un motor Camunda real embebido; las de los portales y los simuladores sustituyen el motor por uno simulado para poder provocar también sus fallos. El último `mvnw clean install` terminó en BUILD SUCCESS con **236 pruebas**.

| Módulo | Pruebas | Clases de prueba | Qué cubren |
|---|---|---|---|
| `cb-comun` (librería compartida) | 49 | `SesionPortalTest` (30), `MotorClientTest` (13), `AutoconfiguracionTest` (6) | La sesión de los portales: respuestas 401 sin sesión, inicio y cierre de sesión, token CSRF obligatorio en toda escritura (también con rutas codificadas), perfiles de cliente, empleado y administrador, mensajes de validación y errores del motor convertidos en 502 sin filtrar su detalle. El filtro de la credencial interna. El cliente del motor: codificación de las direcciones, conversión de errores, envío de mensajes (entregado, no esperado, motor no disponible) y tipos de las variables. La configuración automática: sin credencial la aplicación no arranca. |
| `cb-bpm-engine` (motor) | 42 | `IdentidadesDemoTest` (10), `ClaveDeNegocioAutomaticaTest` (9), `RutasInternasTest` (9), `ModelosDesplegadosTest` (8), `ConectoresConCredencialTest` (3), `IdentidadesDemoConfiguracionTest` (3) | Las cuentas de demostración: cada una con su grupo exacto, su correo y su contraseña; volver a arrancar no duplica ni cambia una contraseña ya cambiada. La clave de caso automática con el prefijo de cada proceso, heredada por los subprocesos. Las rutas del motor detrás de la credencial interna. Los modelos desplegados: Tasklist solo ofrece los tres procesos principales; Postventa arranca desde su formulario de inicio con los valores por defecto; los campos de solo lectura que muestran el motivo de una devolución no traen dato de ejemplo; la segunda resolución de un incidente recibe la solución rechazada; si el Banco no responde en 2 horas el código lo indica. Todos los conectores HTTP de los diagramas envían la credencial interna. |
| `cb-portal-cliente` | 19 | `SolicitudesClienteTest` (19) | Permisos (solo clientes; un empleado o el administrador reciben 403). Creación de la solicitud con su dueño y con el mensaje y las variables que espera el motor. Validación de los datos. Lista con el avance del proceso y solo las variables permitidas. Aislamiento entre clientes. Respuesta a la cotización (aceptar, rechazar, 409 si el proceso no la espera). Comportamiento cuando el motor falla (502, sin guardar nada). |
| `cb-portal-ti` | 21 | `PortalTiTest` (21) | Permisos (un cliente recibe 403). Reporte de un incidente a nombre de quien inició sesión. Validación del reporte. Cada persona ve solo sus tickets y sabe cuál puede confirmar. Confirmación y rechazo de la solución, con sus errores (ticket ajeno 404, sin tarea pendiente 409). API interna de tickets: creación idempotente, un ticket cerrado no admite cambios, un ticket resuelto vuelve a abierto si soporte lo retoma. |
| `cb-central-sys` | 51 | `CentralSysApiTest` (37), `FormularioTareaTest` (7), `ClienteBodegaTest` (7) | La bandeja (tareas libres de mis grupos y las mías; el administrador ve todas). Reclamar, soltar, leer y completar con los permisos por grupo. Completar solo acepta los campos editables, fija el tipo de cada dato y respeta obligatorios, mínimos y máximos. Reasignación de la siguiente tarea al nuevo responsable tras la revisión del plazo vencido. Inicio de la nómina (solo analistas o administrador, con las casillas de simulación). Panel de bodega y registro de la actividad de cada persona. |
| `cb-banco` | 24 | `DispersionApiTest` (22), `ArranqueTest` (2) | Credencial interna. Recepción idempotente de la dispersión (el mismo lote no se duplica, tampoco si llega dos veces a la vez). Respuesta automática: acepta la cuenta con formato válido y rechaza la de formato inválido; reintenta mientras el proceso exista o si el motor no responde; deja la dispersión «sin proceso» si la instancia ya no existe. Respuesta manual (aceptar, rechazar) con sus errores. Consulta y cambio de la simulación. |
| `cb-taller-bodega` | 30 | `TallerBodegaApiTest` (28), `ArranqueTest` (2) | Credencial interna. Consulta de vehículos: garantía, campaña, pérdida de la garantía por kilometraje, VIN desconocido o mal formado. Cotizaciones idempotentes y su estado según la respuesta del cliente. Reservas (reservada o pendiente) y aviso de disponibilidad. Solicitudes de repuesto, entrega manual y ciclo de entrega automática, con sus reintentos y errores. Consulta y cambio de la simulación. |
| **Total** | **236** | | |

## 6. Qué no se probó

1. **El envío real de correo por SMTP.** La última ejecución se hizo con `CORREO_HABILITADO=false`: el motor escribe las notificaciones en su registro y no las envía. Lo que se verificó fue ese registro: en el del motor de esa sesión aparecen diez líneas `[correo simulado]` con los tres avisos que emiten los procesos («Tu incidente … tiene solución», «Tu incidente … fue cerrado» y «Tu solicitud de servicio … fue cerrada»). En los escenarios de `portales` van dirigidas al correo de demostración de quien reportó (`USR-01@corocorabenz.test`) o del cliente (`cliente1@corocorabenz.test`); en los de `e2e` aparecen sin destinatario, porque esos casos de prueba no lo tienen. Ninguna prueba automatizada comprueba el contenido de esas líneas ni las variables que indican si el aviso se envió. No se probó la entrega con un servidor SMTP.
2. **Las páginas web, con pruebas automatizadas.** La suite `portales` usa las mismas API que el navegador, con sesión y token CSRF, pero no hace clic en las páginas. Las pantallas se comprobaron en el ensayo de la sección 3.5, que no es una prueba repetible con un comando.
3. **Tasklist y Cockpit, con pruebas automatizadas.** No las tienen. En el ensayo se comprobó en Cockpit la lista de procesos, las instancias con su clave, el contador sobre el diagrama y el cambio de fecha de un temporizador. Tasklist no se volvió a recorrer en el ensayo.
4. **La espera real de los plazos.** Las pruebas comprueban que cada temporizador queda programado con su duración (2, 24, 48 y 2 horas) y lo vencen ejecutando su trabajo en el motor; nadie esperó esas horas. El plazo de 8 horas no se programa en ninguna prueba (sección 4.4).
5. **Las respuestas automáticas del Banco y de la Bodega junto con el motor real, con pruebas automatizadas.** Las suites integradas apagan la respuesta automática y responden a mano para decidir el resultado. El comportamiento automático está probado en las pruebas de cada simulador, con un motor simulado, y se comprobó con el motor real en el ensayo (PM-05 y PM-06).
6. **La caída de un sistema durante un proceso real.** Las pruebas de módulo cubren qué hace cada sistema cuando el motor no responde, pero ninguna prueba integrada apaga un sistema a mitad de un proceso. Las suites integradas comprueban que el motor termina sin incidentes.
7. **La persistencia tras reiniciar, con pruebas automatizadas.** Ninguna prueba automatizada reinicia los sistemas y compara los datos. Se comprobó en el ensayo (PM-08).
8. **Las pruebas por módulo sobre PostgreSQL.** Usan H2 en memoria; PostgreSQL solo interviene en las pruebas integradas.
9. **La reasignación al nuevo responsable con el motor real, con pruebas automatizadas.** Es una función de CentralSys probada en sus pruebas de módulo con un motor simulado; en `e2e` la revisión del plazo vencido se completa directamente en el motor, donde esa reasignación no existe. Con el motor real se comprobó en el ensayo (PM-04).
10. **Lo que está fuera del alcance de la demostración:** carga y concurrencia, HTTPS y seguridad de producción, pagos, inventario y agenda reales. Los límites se describen en `docs/FUNCIONAMIENTO-Y-LIMITES.md`.
