# Guion de demostración

Este guion sirve para demostrar el sistema Corocora Benz de principio a fin usando solo las pantallas: el Portal del cliente, el Portal TI (Mesa de ayuda), CentralSys (la bandeja de trabajo de los empleados) y Cockpit (la consola de Camunda donde se ve en qué paso está cada proceso). Para cada uno de los tres procesos hay un camino principal y al menos un camino alterno o de excepción.

Cómo leer el guion:

- Cada paso dice **en qué aplicación**, **con qué cuenta**, **qué se hace** y **qué debe verse después**.
- Los textos entre comillas angulares, como «Enviar reporte», son los que aparecen literalmente en la pantalla.
- Los formularios vienen rellenos con valores de demostración. Salvo que el paso diga otra cosa, basta con dejarlos como están.
- La **clave del caso** es el identificador que comparten todas las aplicaciones para un mismo trámite: `TI-…` para un incidente, `NOM-…` para un ciclo de nómina y `PV-…` para una solicitud de postventa. Aparece en el mensaje de confirmación al crear el caso, en la columna «Caso» de CentralSys y en Cockpit.

Hay un único punto que no se puede hacer con las pantallas: **vencer un plazo sin esperar las horas reales**. Los plazos de los procesos son reales (2, 8 o 24 horas, 24 horas, 48 horas y 2 horas) y ningún portal permite adelantarlos. Las demostraciones que dependen de un plazo vencido están marcadas y usan la receta de la sección 5, que hace tres llamadas a la REST del motor.

Qué está comprobado. Tres acciones de este guion se comprobaron a mano en el navegador el 19 de noviembre de 2026, con los sistemas sobre PostgreSQL: el envío del reporte en el Portal TI (paso 2 de la sección 2.1), el triaje en CentralSys (pasos 3 y 4 de la sección 2.1) y el envío de una solicitud en el Portal del cliente (paso 1 de la sección 4.1). Los demás pasos se derivan de la suite `portales`, que recorre estos mismos caminos con la sesión de cada persona por las mismas API que usan las páginas, de los diagramas BPMN y del código de las páginas. Conviene ensayar el guion completo una vez antes de presentarlo; la sección 8 resume qué está comprobado y qué no.

## 1. Preparación

### 1.1 Arranque

Se necesita Java 17 o superior y PostgreSQL en marcha, con las dos bases creadas una sola vez (`psql -U postgres -f crear-bases.sql`). Desde la carpeta `corocora-benz-embebida`:

```powershell
.\mvnw.cmd package -DskipTests
.\iniciar-sistemas.bat
```

El script abre seis ventanas, una por sistema. Arranca primero el motor y espera a que responda, porque los demás sistemas validan contra él los usuarios; el motor puede tardar cerca de un minuto. Si no hay PostgreSQL disponible, `.\iniciar-sistemas.bat h2` arranca todo con bases en memoria: la demostración funciona igual, pero los datos se pierden al cerrar las ventanas y no se puede mostrar la persistencia de la sección 6.

Comprobación opcional antes de presentar: con los seis sistemas arrancados, `npm run todo` desde la carpeta `tests` ejecuta todas las pruebas integradas. No deja instancias en curso y restaura la configuración de los simuladores, pero sí deja casos terminados en el historial: `cliente1`, `cliente2` y `USR-01` verán en sus listas las solicitudes y los tickets que crearon las pruebas. No estorban a la demostración.

### 1.2 Direcciones

| Aplicación | Dirección | Quién la usa |
|---|---|---|
| Portal del cliente | http://localhost:9001 | Los clientes: crean solicitudes de servicio y responden la cotización |
| Portal TI (Mesa de ayuda) | http://localhost:9002 | Cualquier empleado: reporta incidentes y confirma la solución |
| CentralSys | http://localhost:9003 | Los empleados: bandeja de tareas, inicio de la nómina y panel de bodega |
| Cockpit de Camunda | http://localhost:9000/camunda/app/cockpit/ | La persona que presenta, con la cuenta `demo`, para mostrar en qué paso va cada proceso |

El Banco (puerto 9004) y Taller-Bodega (puerto 9005) son simuladores sin pantalla propia. La bodega se maneja desde el panel «Bodega» de CentralSys; el Banco responde solo.

### 1.3 Cuentas

Todas las cuentas usan la contraseña `demo`, salvo `USR-01`, cuya contraseña es `USR-01`. Las crea el motor al arrancar.

| Cuenta | Grupo | Dónde entra |
|---|---|---|
| `cliente1`, `cliente2` | `clientes` | Portal del cliente |
| `USR-01` | (empleado sin grupo) | Portal TI |
| `mesa1` | `mesa-servicio` | CentralSys |
| `soporte1`, `especialista1` | `soporte-ti-n1`, `soporte-ti-especializado` | CentralSys |
| `responsable1` | `responsables-ti` | CentralSys |
| `nomina1`, `talento1`, `jefatura1`, `tesoreria1` | `analistas-nomina`, `gestion-talento-humano`, `jefatura-nomina`, `tesoreria` | CentralSys |
| `asesor1`, `garantia1`, `coordinador1` | `asesores-servicio`, `analistas-garantia`, `coordinadores-postventa` | CentralSys |
| `tecnico1`, `calidad1` | `tecnicos-taller`, `inspectores-calidad` | CentralSys |
| `bodega1` | `bodega` | CentralSys (panel de bodega) |
| `demo` | `camunda-admin` | Administrador: CentralSys, Portal TI y Cockpit |

La sección 7 dice qué cuenta atiende cada tarea.

### 1.4 Cómo se trabaja en las pantallas

- **Iniciar y cerrar sesión.** Cada portal muestra al entrar el formulario «Iniciar sesión en …» con los campos «Usuario» y «Contraseña» y el botón «Entrar». Con la sesión abierta aparece arriba una barra con «Sesión: » y el nombre de la cuenta, sus grupos y el botón «Cerrar sesión».
- **Cambiar de cuenta en CentralSys.** Por CentralSys pasan muchas cuentas. Lo más sencillo es usar «Cerrar sesión» y entrar con la siguiente. Cada portal guarda una sola sesión por navegador; si se quiere tener varias cuentas abiertas a la vez hay que usar perfiles distintos del navegador o navegadores distintos.
- **Contraseña equivocada.** Tras una contraseña incorrecta Camunda bloquea la cuenta unos segundos y en ese rato rechaza también la correcta. El formulario lo avisa; hay que esperar y volver a intentar.
- **Atender una tarea en CentralSys.** La sección «Mis tareas» lista las tareas sin asignar de los grupos de la cuenta y las que ya tiene asignadas, con las columnas Tarea, Proceso, Caso, Grupo, Creada y Acción. El botón «Reclamar y abrir» asigna la tarea a la cuenta y muestra su formulario. Debajo del formulario están «Completar tarea», «Soltar tarea» (la devuelve al grupo) y «Cerrar sin completar». Al completar aparece el mensaje «Tarea completada.» y la acción queda anotada en «Mi actividad reciente».
- **Valores de los formularios.** Cada formulario propone valores de demostración, pero los datos que el proceso ya tiene mandan sobre ellos. Por eso, cuando una tarea vuelve después de una devolución, el formulario muestra lo que se respondió la vez anterior (por ejemplo, la casilla que se desmarcó sigue desmarcada): hay que revisar los valores antes de completar.
- **Refrescar.** La lista de tareas se actualiza sola cada 10 segundos mientras no haya un formulario abierto; el botón «Actualizar» la refresca al instante. Los portales del cliente y de TI tienen su propio botón «Actualizar».
- **Unos segundos de espera.** El motor llama a los otros sistemas en segundo plano. Después de un paso, el siguiente puede tardar unos segundos en aparecer.

### 1.5 Simuladores en modo automático

La demostración supone que los dos simuladores responden solos, que es como arrancan:

- **Banco:** responde cada dispersión unos 10 segundos después de recibirla. Acepta una cuenta de origen con formato `000-123` (tres dígitos, guion y al menos tres dígitos) y rechaza cualquier otro formato.
- **Bodega:** entrega cada repuesto solicitado por el taller unos 10 segundos después, y al reservar repuestos responde que están disponibles.

Para comprobar la bodega, entra a CentralSys como `bodega1` y mira en la sección «Bodega», apartado «Simulación de la bodega», que estén marcadas las casillas «Respuesta automática (entrega los repuestos sola)» y «Repuestos disponibles al reservar». El Banco no tiene pantalla: arranca en automático salvo que se haya definido la variable `BANCO_RESPUESTA_AUTOMATICA=false`.

### 1.6 Qué mostrar en Cockpit

Entra a Cockpit con `demo` / `demo`. En la lista de procesos desplegados aparecen los tres principales («Corocora Benz - Gestión de Incidentes TI», «Corocora Benz - Gestión de Nómina» y «Corocora Benz - Postventa y Garantías») y sus tres subprocesos («Corocora Benz - Resolución Técnica TI», «Corocora Benz - Validación de Novedad» y «Corocora Benz - Orden de Taller»). Al abrir un proceso se ve su diagrama, con un contador sobre la actividad donde está detenida cada instancia en curso, y la lista de instancias con su clave de caso (columna Business Key).

En cada paso del guion se indica en qué actividad del diagrama debe verse el caso. El proyecto usa la edición comunitaria de Camunda 7, cuyo Cockpit lista las instancias en ejecución: cuando un caso termina deja de aparecer en esa lista. Lo que se indica para Cockpit se deriva de los diagramas; no forma parte de las tres comprobaciones manuales, así que conviene ensayarlo.

### 1.7 Orden sugerido

1. Incidentes de TI: camino principal (2.1) y rechazo de la solución (2.2).
2. Nómina: camino principal (3.1), una excepción con las casillas de simulación (3.2) y el rechazo del Banco (3.3).
3. Postventa: camino principal (4.1), cotización rechazada (4.2) y cotización aceptada con repuesto adicional (4.3).
4. Un plazo vencido (5), si hay tiempo.
5. Persistencia (6).

## 2. Proceso 1: gestión de incidentes de TI

Qué hace el proceso: un empleado reporta un incidente; el sistema registra un ticket y calcula su prioridad y su plazo con una tabla de decisión; la Mesa de Servicio lo asigna a Soporte Nivel 1 o a Soporte especializado; soporte registra la causa y la solución; el empleado confirma que quedó resuelto o lo rechaza.

### 2.1 Camino principal: el incidente se resuelve y el usuario confirma

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 1 | Portal TI, `USR-01` (contraseña `USR-01`) | Iniciar sesión. | La página «Incidentes de TI», con las secciones «Reportar incidente» y «Mis tickets». |
| 2 | Portal TI, `USR-01` | En «Reportar incidente», dejar los valores propuestos (sistema «Software del vehículo», descripción «El tablero no muestra la presión de las llantas», impacto «Medio», urgencia «Media», 1 equipo afectado, sin marcar la casilla de seguridad) y pulsar «Enviar reporte». | El mensaje «Reporte enviado con la clave TI-…» y, en unos segundos, el ticket en «Mis tickets» con el estado «En atención» y la solución «Pendiente». Anota la clave del caso. En Cockpit, el proceso «Corocora Benz - Gestión de Incidentes TI» tiene una instancia con esa clave detenida en «Validar clasificación y asignar grupo». |
| 3 | CentralSys, `mesa1` | En «Mis tareas», localizar la tarea «Validar clasificación y asignar grupo» del caso y pulsar «Reclamar y abrir». | El formulario de la tarea, con «Prioridad calculada (DMN)» en P3 y «Plazo de resolución (DMN)» en PT24H (24 horas), los dos de solo lectura: los calculó la tabla de decisión. El campo «Grupo de atención» propone «Soporte TI Nivel 1». |
| 4 | CentralSys, `mesa1` | Pulsar «Completar tarea». | El mensaje «Tarea completada.». La tarea desaparece de la bandeja y la acción aparece en «Mi actividad reciente». En Cockpit, la instancia está en «Resolver incidente técnico» y hay una instancia del subproceso «Corocora Benz - Resolución Técnica TI» en «Diagnosticar y resolver». |
| 5 | CentralSys, `soporte1` | Cerrar la sesión de `mesa1` y entrar como `soporte1`. Abrir la tarea «Diagnosticar y resolver» con «Reclamar y abrir». El formulario muestra el ticket, la prioridad y la descripción, y propone una «Causa raíz» y una «Solución aplicada». Pulsar «Completar tarea». | «Tarea completada.». El proceso registra la solución en el ticket y avisa al usuario; como el correo está desactivado, el aviso queda escrito en la ventana del motor en una línea que empieza por `[correo simulado]`. En Cockpit, la instancia principal está en «Confirmar solución del incidente». |
| 6 | Portal TI, `USR-01` | Pulsar «Actualizar» en «Mis tickets». | El ticket pasa al estado «Esperando tu confirmación». La columna «Solución» muestra la solución y la causa que escribió soporte, y la columna «Acción» ofrece los botones «Confirmar solución» y «No se resolvió». |
| 7 | Portal TI, `USR-01` | Pulsar «Confirmar solución». | El mensaje «Gracias. El ticket TI-… se cerrará en un momento.» y, en unos segundos, el estado «Cerrado». En Cockpit, la instancia ya no aparece entre las que están en ejecución: terminó en el fin «Incidente resuelto y cerrado». |

Este camino corresponde al caso de prueba CP-TI-03 de `docs/CASOS-DE-PRUEBA.md`.

### 2.2 Camino alterno: el usuario rechaza la solución

Repite los pasos 1 a 6 de la sección 2.1 con un incidente nuevo y continúa así:

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 7 | Portal TI, `USR-01` | Pulsar «No se resolvió». | El mensaje «Entendido. El ticket TI-… vuelve a soporte.». El ticket vuelve al estado «En atención» y la columna «Solución» muestra «Solución anterior: » seguida de la solución rechazada. En Cockpit, la instancia vuelve a «Resolver incidente técnico». |
| 8 | CentralSys, `soporte1` | Abrir de nuevo la tarea «Diagnosticar y resolver» del mismo caso. Ahora el formulario muestra, en solo lectura, «Causa registrada en el intento anterior» y «Solución rechazada por el usuario». Escribir una causa y una solución distintas (por ejemplo, «Sensor descalibrado» y «Sensor recalibrado en taller») y pulsar «Completar tarea». | «Tarea completada.». |
| 9 | Portal TI, `USR-01` | Pulsar «Actualizar». | El mismo ticket, otra vez en «Esperando tu confirmación», con la segunda solución. |
| 10 | Portal TI, `USR-01` | Pulsar «Confirmar solución». | El ticket queda «Cerrado» y el proceso termina en «Incidente resuelto y cerrado». |

Corresponde al caso CP-TI-04.

### 2.3 Variante opcional: incidente de seguridad atendido por Soporte especializado

En el paso 2 marca la casilla «¿Afecta la seguridad del vehículo o de la información?». En el paso 3, `mesa1` verá la prioridad P1 y el plazo PT2H (2 horas): la tabla de decisión da la máxima prioridad a todo lo que afecta la seguridad. Si en «Grupo de atención» elige «Soporte especializado», la tarea de resolución se llama «Analizar causa raíz y resolver» y llega a la bandeja de `especialista1`, no a la de `soporte1`. Esta variante la cubre el caso CP-TI-02, que se ejecuta por la REST del motor; en las pantallas conviene ensayarla antes.

### 2.4 Excepciones con plazo vencido (necesitan la receta de la sección 5)

**Plazo de resolución vencido.** Deja un incidente en el paso 5 sin completar la tarea de soporte y vence el temporizador `slaTimer` del caso con la receta de la sección 5. Después:

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 1 | CentralSys, `responsable1` | Abrir la tarea «Revisar incidente con plazo vencido». El formulario muestra el ticket y la prioridad, propone un «Plan de atención» y deja vacío «Nuevo responsable». Escribir en «Nuevo responsable» la cuenta que debe retomar el caso (`soporte1` si el incidente era de Nivel 1) y pulsar «Completar tarea». | El mensaje «Tarea completada. La siguiente tarea de soporte quedó asignada a soporte1.». Si se deja vacío «Nuevo responsable», el mensaje es «Tarea completada.» y la tarea vuelve al grupo de soporte. |
| 2 | CentralSys, `soporte1` | Mirar «Mis tareas». | La tarea de soporte anterior se canceló al vencer el plazo y hay una nueva del mismo caso; si se indicó un nuevo responsable, aparece como «Asignada a mí» y el botón dice «Abrir». El plazo de resolución empieza de nuevo. A partir de aquí el caso sigue como en la sección 2.1. |

**El usuario no responde en 24 horas.** Deja un ticket en «Esperando tu confirmación» (paso 6 de la sección 2.1) y vence el temporizador `noResponse`. Al pulsar «Actualizar» en el Portal TI, el ticket aparece «Cerrado» sin que el usuario haya confirmado, y el proceso termina en el fin «Ticket cerrado por falta de respuesta».

Las dos excepciones las cubre el caso CP-TI-02. La asignación al nuevo responsable es una función de CentralSys probada en sus pruebas de módulo; no está entre las comprobaciones manuales.

## 3. Proceso 2: gestión de nómina

Qué hace el proceso: el analista de nómina inicia el ciclo de un periodo; el sistema recolecta las novedades de los empleados y una tabla de decisión revisa si el periodo está abierto, si los soportes están completos y si hay novedades fuera de la fecha de corte; el analista valida cada novedad y revisa la liquidación; Jefatura la aprueba; Tesorería prepara el lote y lo envía al Banco; cuando el Banco acepta, se registra el pago y se generan los comprobantes. Todos los valores son simulados: la liquidación es siempre de 1.000.000 y no hay pago real.

### 3.1 Camino principal: del inicio del ciclo al pago aceptado por el Banco

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 1 | CentralSys, `nomina1` | En la sección «Iniciar nómina» (solo la ven los analistas de nómina y el administrador), dejar el ciclo «Quincenal» y el periodo y la fecha de corte que propone la página (la quincena en curso). No marcar ninguna casilla de «Simular una excepción (demostración)». Pulsar «Iniciar ciclo». | El mensaje «Ciclo de nómina iniciado: NOM-…» con la clave del caso. En «Mis tareas» aparecen dos tareas «Revisar novedad y soporte», una por cada empleado con novedad. En Cockpit, el proceso «Corocora Benz - Gestión de Nómina» tiene una instancia en «Validar cada novedad de empleado» y hay dos instancias del subproceso «Corocora Benz - Validación de Novedad». |
| 2 | CentralSys, `nomina1` | Abrir cada tarea «Revisar novedad y soporte» con «Reclamar y abrir». El formulario muestra el empleado, el tipo de novedad, el valor y si tiene soporte; la casilla «Novedad válida» viene marcada. Pulsar «Completar tarea» en las dos. | «Tarea completada.» en cada una. Al terminar la segunda, el sistema calcula la liquidación y aparece la tarea «Revisar liquidación». |
| 3 | CentralSys, `nomina1` | Abrir «Revisar liquidación». El formulario muestra el periodo, el resumen y el «Total calculado» (1.000.000); «Liquidación consistente» viene marcada. Pulsar «Completar tarea». | «Tarea completada.». En Cockpit, la instancia está en «Aprobar liquidación». |
| 4 | CentralSys, `jefatura1` | Abrir «Aprobar liquidación». El formulario muestra el resumen y el «Total a pagar»; «Aprobar liquidación» viene marcada. Pulsar «Completar tarea». | «Tarea completada.». En Cockpit, la instancia está en «Preparar lote de dispersión». |
| 5 | CentralSys, `tesoreria1` | Abrir «Preparar lote de dispersión». El formulario muestra el «Total aprobado» y propone el «ID del lote» y la «Cuenta de origen» `000-123`. Pulsar «Completar tarea». | «Tarea completada.». El motor envía la dispersión al Banco; en Cockpit la instancia queda esperando en «Respuesta del Banco». Unos 10 segundos después el Banco acepta (la cuenta tiene formato válido), el proceso registra el pago, genera los comprobantes y termina en «Nómina pagada y comprobantes emitidos»: la instancia deja de aparecer en Cockpit y no queda ninguna tarea del caso en ninguna bandeja. |

El Banco no tiene pantalla. Si se quiere mostrar la dispersión aceptada y la referencia bancaria hay que consultar su API con la credencial interna (`GET http://localhost:9004/api/dispersiones?businessKey=NOM-…` con el encabezado `X-CB-Internal`) o la tabla `banco_dispersion` de la base `corocora`. No hace falta para la demostración: que el caso termine sin tarea de corrección ya indica que el Banco aceptó.

Corresponde al caso CP-NOM-06.

### 3.2 Excepción: las casillas «Simular una excepción (demostración)»

Al iniciar la nómina, cada casilla lleva el ciclo por una ruta de excepción distinta. La tabla de decisión de nómina es la que elige la ruta. Después de iniciar, la página desmarca las casillas para que la simulación no se repita por descuido.

| Casilla marcada en el paso 1 | Quién recibe la tarea | Qué se hace | Cómo continúa |
|---|---|---|---|
| «Simular: periodo cerrado» | `talento1`: «Corregir novedades o reabrir periodo» | El formulario muestra el «Motivo de la devolución» `REQUIERE_REAPERTURA`. Elegir en «Acción realizada» la opción «Reabrir el periodo» y pulsar «Completar tarea». | El sistema vuelve a recolectar las novedades, ya sin la excepción, y `nomina1` recibe las dos tareas «Revisar novedad y soporte». Sigue como la sección 3.1 desde el paso 2. |
| «Simular: soportes incompletos» | `talento1`: «Corregir novedades o reabrir periodo» | El motivo es `SOPORTE_INCOMPLETO`. Dejar la acción «Completar soportes o corregir novedades» y pulsar «Completar tarea». | Igual que la anterior. |
| «Simular: novedades fuera de corte» | `jefatura1`: «Aprobar novedades fuera de corte» | El formulario muestra la novedad tardía: «EMP-003 · Horas extra · 4 h registradas después de la fecha de corte». Con «Aprobar las novedades» marcada, pulsar «Completar tarea». | `nomina1` recibe **tres** tareas «Revisar novedad y soporte» en lugar de dos. Si Jefatura desmarca «Aprobar las novedades», la tarea «Corregir novedades o reabrir periodo» llega a `talento1` y, tras completarla, el ciclo continúa con dos novedades. |

En Cockpit, la instancia se ve detenida en «Corregir novedades o reabrir periodo» o en «Aprobar novedades fuera de corte» en lugar de avanzar a la validación de novedades.

Para una demostración corta basta con «Simular: periodo cerrado». Corresponden a los casos CP-NOM-02 a CP-NOM-05, que se ejecutan por la REST del motor.

### 3.3 Excepción: el Banco rechaza una cuenta con formato inválido

Sigue la sección 3.1 hasta el paso 4 y continúa así:

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 5 | CentralSys, `tesoreria1` | En «Preparar lote de dispersión», cambiar la «Cuenta de origen» por `123` (un formato que el Banco no acepta) y pulsar «Completar tarea». | «Tarea completada.». Unos 10 segundos después el Banco rechaza la dispersión y aparece en la bandeja de `tesoreria1` la tarea «Corregir lote o consultar al Banco». En Cockpit, la instancia pasa de «Respuesta del Banco» a esa tarea. |
| 6 | CentralSys, `tesoreria1` | Abrir «Corregir lote o consultar al Banco». El formulario muestra en «Código de respuesta del Banco» el código `R03-CUENTA-INVALIDA` y pide, como campo obligatorio, la «Cuenta de origen corregida». Como el proceso ya tiene una cuenta, el campo debe traer la que se envió (`123`) y no el valor de demostración: cambiarla por `000-123` y pulsar «Completar tarea». | «Tarea completada.». El motor reenvía el lote con la cuenta corregida; unos 10 segundos después el Banco acepta y el proceso termina en «Nómina pagada y comprobantes emitidos». |

Corresponde al caso CP-NOM-07. En ese caso la prueba responde por el Banco a mano; el rechazo automático por el formato de la cuenta está comprobado en las pruebas del módulo del Banco. La cadena completa en las pantallas, con el Banco respondiendo solo, conviene ensayarla antes.

### 3.4 Otras devoluciones que se pueden mostrar desde las pantallas

- **Novedad no válida.** En el paso 2, `nomina1` desmarca «Novedad válida» y escribe el motivo en «Observación». La tarea «Corregir novedad y adjuntar soporte» llega a `talento1`, que ve el empleado y la «Observación del analista» y registra la corrección; la novedad vuelve a `nomina1` para una nueva revisión.
- **Liquidación inconsistente.** En el paso 3, `nomina1` desmarca «Liquidación consistente». El sistema recalcula y la tarea «Revisar liquidación» vuelve a aparecer.
- **Jefatura devuelve la liquidación.** En el paso 4, `jefatura1` desmarca «Aprobar liquidación» y escribe el motivo en «Motivo o devolución». La tarea «Revisar liquidación» vuelve a `nomina1`, que ve ese texto en «Motivo de la devolución de Jefatura».

En los tres casos, cuando la tarea vuelve, la casilla que se desmarcó debe seguir desmarcada (el formulario muestra el valor que ya tiene el proceso): hay que marcarla para continuar. Las tres devoluciones las cubre el caso CP-NOM-01.

### 3.5 El Banco no responde en 2 horas

Esta excepción no se puede mostrar solo con pantallas: hay que poner el Banco en modo manual por su API (`PUT http://localhost:9004/api/simulacion` con `{"respuestaAutomatica":false}` y el encabezado `X-CB-Internal`) para que no responda, y vencer el temporizador `bankTimeout` con la receta de la sección 5. Entonces `tesoreria1` recibe «Corregir lote o consultar al Banco» con el código `SIN-RESPUESTA-2H` en lugar de un código de rechazo. Al terminar hay que devolver el Banco al modo automático con el mismo `PUT` y `true`. Si no se quiere usar la API en la presentación, la evidencia de este camino es el caso CP-NOM-01.

## 4. Proceso 3: postventa y garantías

Qué hace el proceso: el cliente pide un servicio para su vehículo; el asesor registra la solicitud; el sistema consulta el vehículo y una tabla de decisión determina si lo cubre una campaña, la garantía o si requiere cotización; el analista de garantías aprueba la cobertura o, si no hay cobertura, el cliente recibe una cotización que acepta o rechaza; si la reparación queda autorizada, se reservan los repuestos y se agenda la cita al mismo tiempo; el taller diagnostica, repara y pasa la inspección de calidad; el asesor entrega el vehículo.

Vehículos de muestra: `CB0TEST0000000001` tiene una campaña activa. `WDB0000000000000B` no tiene campaña: la garantía lo cubre hasta 60.000 km y con más kilometraje recibe cotización. La cotización simulada es siempre de 850.000 pesos.

### 4.1 Camino principal: vehículo en campaña, reparado y entregado

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 1 | Portal del cliente, `cliente1` | En «Nueva solicitud», dejar los valores propuestos (cliente «Ana Pérez», VIN `CB0TEST0000000001`, tipo de servicio «Garantía», 15000 km y el síntoma de la cámara de reversa) y pulsar «Enviar solicitud». | El mensaje «Solicitud enviada. Tu número de caso es PV-…» y, en «Mis solicitudes», el caso con la etiqueta «Solicitud recibida». En Cockpit, el proceso «Corocora Benz - Postventa y Garantías» tiene una instancia en «Registrar solicitud y asignar taller». |
| 2 | CentralSys, `asesor1` | Abrir «Registrar solicitud y asignar taller» con «Reclamar y abrir». El formulario muestra, en solo lectura, los datos que escribió el cliente y propone el «Taller asignado» «Taller Bogotá Norte». Pulsar «Completar tarea». | «Tarea completada.». El sistema consulta el vehículo en Taller-Bodega y la tabla de decisión reconoce la campaña. En el Portal del cliente (botón «Actualizar») el caso pasa a «En revisión de garantía» y muestra «Cobertura: campaña activa». |
| 3 | CentralSys, `garantia1` | Abrir «Revisar cobertura y evidencia». El formulario muestra «Resultado DMN» `CAMPANA_ACTIVA` y su motivo; «Aprobar atención por garantía o campaña» viene marcada. Pulsar «Completar tarea». | «Tarea completada.». El proceso se divide en dos ramas paralelas: la reserva de repuestos, que hace el sistema, y la cita, que agenda una persona. En el Portal del cliente el caso pasa a «Pendiente de agendar cita». |
| 4 | CentralSys, `coordinador1` | Abrir «Agendar cita en el taller». El formulario muestra el taller y propone la «Fecha y hora de la cita» y el «Número de orden de taller». Pulsar «Completar tarea». | «Tarea completada.». Con la cita agendada y los repuestos reservados empieza el subproceso de taller. El Portal del cliente muestra «En atención de taller», con la cita y la orden. En Cockpit, la instancia está en «Atender orden de taller» y hay una instancia de «Corocora Benz - Orden de Taller». |
| 5 | CentralSys, `tecnico1` | Abrir «Recibir y diagnosticar vehículo»; dejar sin marcar «Requiere repuesto adicional» y pulsar «Completar tarea». Después abrir «Reparar vehículo», que muestra el diagnóstico y propone el trabajo y el costo, y pulsar «Completar tarea». | «Tarea completada.» en las dos. |
| 6 | CentralSys, `calidad1` | Abrir «Inspeccionar reparación». Muestra el trabajo realizado; «Inspección aprobada» viene marcada. Pulsar «Completar tarea». | «Tarea completada.». El Portal del cliente muestra «Pendiente de entrega». |
| 7 | CentralSys, `asesor1` | Abrir «Entregar vehículo y cerrar caso». Muestra la orden, el trabajo y el costo, y pide la «Satisfacción del cliente (1 a 5)». Pulsar «Completar tarea». | «Tarea completada.». En el Portal del cliente el caso queda con la etiqueta «Vehículo entregado» y muestra el «Trabajo realizado», la cita y la orden. En Cockpit la instancia ya no está en ejecución: terminó en «Vehículo entregado y caso cerrado». |

Corresponde al caso CP-PV-07.

### 4.2 Camino alterno: vehículo fuera de garantía, el cliente rechaza la cotización

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 1 | Portal del cliente, `cliente1` | En «Nueva solicitud», cambiar el VIN por `WDB0000000000000B` y el kilometraje por `80000`, y pulsar «Enviar solicitud». | «Solicitud enviada. Tu número de caso es PV-…». |
| 2 | CentralSys, `asesor1` | Abrir y completar «Registrar solicitud y asignar taller». | «Tarea completada.». Como el vehículo supera los 60.000 km, la tabla de decisión responde que requiere cotización y el sistema la crea en Taller-Bodega. No hay tarea para el analista de garantías. En Cockpit, la instancia queda esperando en «Respuesta a la cotización». |
| 3 | Portal del cliente, `cliente1` | Pulsar «Actualizar». | El caso con la etiqueta «Esperando tu respuesta a la cotización», la línea «Cobertura: requiere cotización», la cotización `COT-…` por 850.000 pesos con la nota «(simulada)» y los botones «Aceptar» y «Rechazar». |
| 4 | Portal del cliente, `cliente1` | Pulsar «Rechazar» y aceptar la pregunta de confirmación del navegador, que advierte que el caso se cerrará sin reparar el vehículo. | El mensaje «Cotización rechazada para PV-…» y el caso con la etiqueta «Cotización rechazada; caso finalizado». El proceso avisa al cliente del cierre (línea `[correo simulado]` en la ventana del motor) y termina en el fin «Reparación no autorizada»; en Cockpit la instancia deja de estar en ejecución. |

Corresponde al caso CP-PV-08.

### 4.3 Camino alterno: el cliente acepta la cotización y el taller pide un repuesto adicional

Para que la entrega del repuesto la haga una persona, entra antes a CentralSys como `bodega1` y, en «Bodega», «Simulación de la bodega», desmarca «Respuesta automática (entrega los repuestos sola)»; aparece el mensaje «Simulación de la bodega actualizada.». Si se deja marcada, la bodega entrega el repuesto sola a los 10 segundos y el paso 6 no hace falta.

| Paso | Aplicación y cuenta | Qué se hace | Qué debe verse |
|---|---|---|---|
| 1 a 3 | Portal del cliente y CentralSys | Los pasos 1 a 3 de la sección 4.2. | El caso «Esperando tu respuesta a la cotización». |
| 4 | Portal del cliente, `cliente1` | Pulsar «Aceptar». | El mensaje «Cotización aceptada para PV-…» y el caso con la etiqueta «Pendiente de agendar cita». |
| 5 | CentralSys, `coordinador1` y luego `tecnico1` | `coordinador1` completa «Agendar cita en el taller». `tecnico1` abre «Recibir y diagnosticar vehículo», marca «Requiere repuesto adicional», deja el «Código del repuesto» `CAM-123` y pulsa «Completar tarea». | «Tarea completada.». La tarea «Reparar vehículo» todavía no aparece: el taller espera el repuesto. En Cockpit, la instancia de «Corocora Benz - Orden de Taller» está en «Repuesto recibido». |
| 6 | CentralSys, `bodega1` | En «Bodega», tabla «Repuestos solicitados por el taller», localizar la fila del caso con el repuesto `CAM-123` y pulsar «Entregar». | El mensaje «Repuesto CAM-123 entregado al taller.» y la fila desaparece de la tabla. Si la página responde que la operación aún no se puede hacer, el proceso todavía no esperaba el aviso: se reintenta a los pocos segundos. |
| 7 | CentralSys, `tecnico1`, `calidad1` y `asesor1` | Los pasos 5 (solo «Reparar vehículo»), 6 y 7 de la sección 4.1. | El caso termina con la etiqueta «Vehículo entregado» en el Portal del cliente. |
| 8 | CentralSys, `bodega1` | Volver a marcar «Respuesta automática (entrega los repuestos sola)». | «Simulación de la bodega actualizada.». |

Corresponde al caso CP-PV-06.

### 4.4 Otras variantes que se pueden mostrar desde las pantallas

- **Garantía no aprobada.** Con el VIN `WDB0000000000000B` y 15000 km, la tabla de decisión responde que la garantía cubre el vehículo y `garantia1` recibe «Revisar cobertura y evidencia» con el resultado `CUBIERTO_POR_GARANTIA`. Si desmarca «Aprobar atención por garantía o campaña», el caso pasa a cotización y el cliente ve «Cobertura: garantía no aprobada; requiere cotización». Lo cubre el caso CP-PV-03.
- **Inspección no aprobada.** Si `calidad1` desmarca «Inspección aprobada», la tarea «Reparar vehículo» vuelve a `tecnico1`, que ve el «Resultado de la última inspección». En la segunda inspección `calidad1` debe marcar la casilla de nuevo. Lo cubre el caso CP-PV-01.
- **Espera de repuestos.** Si `bodega1` desmarca «Repuestos disponibles al reservar», la siguiente solicitud autorizada queda esperando: el cliente ve «Esperando aviso de repuestos disponibles» y, aunque `coordinador1` agende la cita, el taller no empieza. `bodega1` ve el caso en la tabla «Reservas pendientes de repuestos» y, al pulsar «Avisar disponibilidad», aparece «Se avisó al proceso PV-… que los repuestos están disponibles.» y comienza el taller. Hay que volver a marcar la casilla al terminar. Lo cubre el caso CP-PV-05.

### 4.5 El cliente no responde la cotización en 48 horas

Necesita la receta de la sección 5. Con un caso en «Esperando tu respuesta a la cotización» (paso 3 de la sección 4.2), vence el temporizador `quoteExpired`. Al pulsar «Actualizar», el cliente ve la etiqueta «Cotización vencida; caso finalizado» y el proceso termina en «Reparación no autorizada». Lo cubre el caso CP-PV-04.

## 5. Vencer un plazo sin esperar (el único punto que no se hace con pantallas)

Los cuatro temporizadores de los procesos son reales y ningún portal permite adelantarlos; Swagger tampoco. Para mostrarlos se ejecuta en el motor el trabajo pendiente del temporizador, que es lo mismo que hace la suite `e2e`. Todas las llamadas llevan el encabezado `X-CB-Internal` con la credencial interna (por defecto `cb-local-interno-demo`).

| Temporizador | Plazo | Cuándo existe | Qué ocurre al vencerlo |
|---|---|---|---|
| `slaTimer` | 2, 8 o 24 horas, según la prioridad | Mientras soporte tiene pendiente la resolución del incidente | Se cancela la tarea de soporte y `responsable1` recibe «Revisar incidente con plazo vencido» |
| `noResponse` | 24 horas | Mientras el ticket espera la confirmación del usuario | El ticket se cierra por falta de respuesta |
| `quoteExpired` | 48 horas | Mientras la cotización espera la respuesta del cliente | El caso se cierra sin reparación |
| `bankTimeout` | 2 horas | Mientras el proceso espera la respuesta del Banco | `tesoreria1` recibe la corrección del lote con el código `SIN-RESPUESTA-2H` |

Las tres llamadas:

1. `GET http://localhost:9000/engine-rest/process-instance?businessKey={clave del caso}` devuelve las instancias del caso. Los temporizadores pertenecen a la instancia raíz (la del proceso principal, que se reconoce por su `definitionId`), no a las de los subprocesos.
2. `GET http://localhost:9000/engine-rest/job?processInstanceId={id de la instancia raíz}&timers=true` lista sus temporizadores pendientes.
3. `POST http://localhost:9000/engine-rest/job/{id del job}/execute` vence el temporizador en ese momento.

Las mismas tres llamadas en una sola orden de PowerShell, para pegar en una terminal. Recorre todas las instancias del caso y vence el temporizador indicado, igual que la suite `e2e`; esta forma concreta de escribirlas no se ejecutó al redactar el guion, así que hay que ensayarla una vez:

```powershell
$h = @{ 'X-CB-Internal' = 'cb-local-interno-demo' }
$motor = 'http://localhost:9000/engine-rest'
$caso = 'TI-XXXXXXXX'        # la clave que mostró el portal
$temporizador = 'slaTimer'   # o noResponse, quoteExpired, bankTimeout
foreach ($i in Invoke-RestMethod -Headers $h "$motor/process-instance?businessKey=$caso") {
  foreach ($j in Invoke-RestMethod -Headers $h "$motor/job?processInstanceId=$($i.id)&activityId=$temporizador&timers=true") {
    Invoke-RestMethod -Method Post -Headers $h "$motor/job/$($j.id)/execute"
  }
}
```

Como alternativa sin terminal, Cockpit permite cambiar la fecha de vencimiento de un temporizador a la hora actual desde la vista de la instancia, en la pestaña Jobs. Esa vía **no se ha verificado** en esta demostración.

## 6. Dónde ver la persistencia

Con el arranque por defecto (`.\iniciar-sistemas.bat`, sin argumento) los datos se guardan en PostgreSQL, en dos bases:

| Base | Qué guarda |
|---|---|
| `corocora_camunda` | Todo lo del motor: instancias de proceso, tareas, variables, historial, temporizadores y las cuentas y grupos de demostración (tablas `ACT_…` de Camunda) |
| `corocora` | Los datos de negocio de cada aplicación: `ti_ticket` (tickets del Portal TI), `pc_solicitud` (solicitudes del Portal del cliente y su dueño), `cs_actividad` (actividad de cada empleado en CentralSys), `banco_dispersion` (dispersiones del Banco), y `tb_cotizacion`, `tb_reserva`, `tb_solicitud_repuesto` y `tb_vehiculo` (Taller-Bodega) |

Cómo mostrarla:

1. Deja casos a medias: un incidente reportado sin triaje (ticket «En atención» y tarea pendiente para `mesa1`), una solicitud de postventa recién creada («Solicitud recibida») y, si se quiere, un ciclo de nómina con tareas pendientes. Deja también algún caso terminado.
2. Cierra las seis ventanas de los sistemas. Con eso se detienen.
3. Vuelve a arrancar con `.\iniciar-sistemas.bat` y espera a que respondan.
4. Vuelve a iniciar sesión en cada portal y comprueba que todo sigue igual: `USR-01` ve en «Mis tickets» los mismos tickets con su estado; `cliente1` ve en «Mis solicitudes» las mismas solicitudes con su etiqueta; `mesa1` encuentra en «Mis tareas» la misma tarea pendiente; «Mi actividad reciente» conserva las acciones anteriores de cada empleado; y Cockpit muestra las mismas instancias detenidas en la misma actividad. Los casos pueden continuarse desde donde quedaron.

Lo que no se conserva, y es normal:

- **Las sesiones.** Viven en la memoria de cada portal: después del reinicio hay que iniciar sesión otra vez.
- **Los interruptores de los simuladores.** El Banco y la Bodega vuelven a su configuración de arranque (respuesta automática y repuestos disponibles).
- **Todo, si se arrancó con `h2`.** Ese perfil usa bases en memoria que se borran al cerrar cada ventana.

Qué está comprobado: el 19 de noviembre de 2026, con los sistemas sobre PostgreSQL, `cliente1` vio en el Portal del cliente sus solicitudes anteriores con su estado. El reinicio seguido de la comparación de datos no forma parte de las pruebas automatizadas, así que conviene ensayarlo una vez antes de presentarlo.

## 7. Qué cuenta atiende qué tarea

La cuenta que atiende cada tarea sale del grupo candidato que la tarea tiene en el diagrama BPMN y del grupo de cada cuenta de demostración. La cuenta `demo`, por ser administradora, ve en CentralSys todas las tareas y puede atender cualquiera; sirve como recurso si algo se atasca, pero no muestra la separación de funciones.

### Acciones que inician o responden un caso

| Acción | Aplicación | Cuenta |
|---|---|---|
| Reportar un incidente | Portal TI, «Reportar incidente» | `USR-01` (o cualquier empleado) |
| Confirmar o rechazar la solución | Portal TI, «Mis tickets» | Quien reportó el incidente (`USR-01`) |
| Iniciar la nómina | CentralSys, «Iniciar nómina» | `nomina1` |
| Crear una solicitud de servicio | Portal del cliente, «Nueva solicitud» | `cliente1` o `cliente2` |
| Aceptar o rechazar la cotización | Portal del cliente, «Mis solicitudes» | El cliente dueño de la solicitud |
| Entregar un repuesto, avisar disponibilidad o cambiar la simulación | CentralSys, «Bodega» | `bodega1` |

### Tareas de la bandeja de CentralSys

| Proceso | Tarea | Grupo candidato | Cuenta |
|---|---|---|---|
| Incidentes de TI | Validar clasificación y asignar grupo | `mesa-servicio` | `mesa1` |
| Incidentes de TI | Diagnosticar y resolver | `soporte-ti-n1` | `soporte1` |
| Incidentes de TI | Analizar causa raíz y resolver | `soporte-ti-especializado` | `especialista1` |
| Incidentes de TI | Revisar incidente con plazo vencido | `responsables-ti` | `responsable1` |
| Incidentes de TI | Confirmar solución del incidente | Asignada a quien reportó | `USR-01`, desde el Portal TI |
| Nómina | Revisar novedad y soporte | `analistas-nomina` | `nomina1` |
| Nómina | Corregir novedad y adjuntar soporte | `gestion-talento-humano` | `talento1` |
| Nómina | Corregir novedades o reabrir periodo | `gestion-talento-humano` | `talento1` |
| Nómina | Aprobar novedades fuera de corte | `jefatura-nomina` | `jefatura1` |
| Nómina | Revisar liquidación | `analistas-nomina` | `nomina1` |
| Nómina | Aprobar liquidación | `jefatura-nomina` | `jefatura1` |
| Nómina | Preparar lote de dispersión | `tesoreria` | `tesoreria1` |
| Nómina | Corregir lote o consultar al Banco | `tesoreria` | `tesoreria1` |
| Postventa | Registrar solicitud y asignar taller | `asesores-servicio` | `asesor1` |
| Postventa | Revisar cobertura y evidencia | `analistas-garantia` | `garantia1` |
| Postventa | Agendar cita en el taller | `coordinadores-postventa` | `coordinador1` |
| Postventa | Recibir y diagnosticar vehículo | `tecnicos-taller` | `tecnico1` |
| Postventa | Reparar vehículo | `tecnicos-taller` | `tecnico1` |
| Postventa | Inspeccionar reparación | `inspectores-calidad` | `calidad1` |
| Postventa | Entregar vehículo y cerrar caso | `asesores-servicio` | `asesor1` |

## 8. Qué está comprobado y qué conviene ensayar

| Parte del guion | Respaldo |
|---|---|
| Envío del reporte en el Portal TI; triaje en CentralSys con P3 y PT24H y «Tarea completada.»; envío de una solicitud y lista de solicitudes en el Portal del cliente | Comprobado a mano en el navegador el 19 de noviembre de 2026 (comprobaciones PM-01 a PM-03 de `docs/CASOS-DE-PRUEBA.md`) |
| Caminos 2.1, 2.2, 3.1, 4.1, 4.2 y 4.3, y el rechazo del Banco de 3.3 | Recorridos por la suite `portales` con la sesión de cada persona y por las mismas API que usan las páginas (casos CP-TI-03, CP-TI-04, CP-NOM-06, CP-NOM-07, CP-PV-06, CP-PV-07 y CP-PV-08, todos correctos). En esa suite el Banco y la Bodega se responden a mano; en el guion responden solos |
| Excepciones de nómina (3.2 y 3.4), incidente de seguridad y especializado (2.3), plazos vencidos (2.4, 3.5 y 4.5) y variantes de postventa (4.4) | Recorridas por la suite `e2e` por la REST del motor (casos CP-TI-02, CP-NOM-01 a CP-NOM-05 y CP-PV-01 a CP-PV-05, todos correctos). No se recorrieron haciendo clic en las pantallas |
| Textos de botones, campos y mensajes | Tomados del código de las páginas y de los formularios desplegados |
| Lo que se indica para Cockpit, la orden de PowerShell de la sección 5 y el reinicio de la sección 6 | Derivado de los diagramas y de la documentación; no comprobado en esta sesión. Ensayar antes de presentar |
