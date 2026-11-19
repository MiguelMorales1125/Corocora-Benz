/*
 * Página de CentralSys: bandeja de tareas, formulario de la tarea abierta, inicio de nómina,
 * panel de bodega y actividad reciente.
 *
 * Solo habla con /api/app de este portal por medio de CB (auth.js). Los formularios se pintan
 * tal como están desplegados en el motor: aquí no se agregan, quitan ni cambian campos.
 */
(function () {
  "use strict";

  const INTERVALO_ACTUALIZACION_MS = 10000;
  const GRUPO_NOMINA = "analistas-nomina";
  // Casillas de "Iniciar nómina" que llevan el proceso por una ruta de excepción (demostración).
  const SIMULACIONES_NOMINA = ["simulatePeriodClosed", "simulateMissingSupports", "simulateLateChanges"];
  const GRUPO_BODEGA = "bodega";
  const ESTADO_RESERVA_PENDIENTE = "PENDIENTE";
  const ESTADO_SOLICITUD_PENDIENTE = "SOLICITADA";

  // Tras estos errores la tarea abierta ya no se puede trabajar: se cierra el panel.
  const ESTADOS_QUE_CIERRAN_LA_TAREA = [403, 404, 409];

  const NOMBRES_DE_ACCION = {
    RECLAMAR: "Reclamaste",
    SOLTAR: "Soltaste",
    COMPLETAR: "Completaste",
    INICIAR_NOMINA: "Iniciaste la nómina",
    AVISAR_DISPONIBILIDAD: "Avisaste disponibilidad de repuestos",
    ENTREGAR_REPUESTO: "Entregaste un repuesto",
    CAMBIAR_SIMULACION: "Cambiaste la simulación de la bodega"
  };

  // form-js trae sus avisos en inglés; aquí van los que usan los formularios del proyecto.
  const TEXTOS_FORMULARIO = {
    "Field is required.": "Este campo es obligatorio.",
    "Field must have minimum value of {value}.": "El valor mínimo es {value}.",
    "Field must have maximum value of {value}.": "El valor máximo es {value}.",
    "Value is not a number.": "El valor no es un número.",
    "Increment": "Aumentar",
    "Decrement": "Disminuir"
  };

  const FORMATO_FECHA = new Intl.DateTimeFormat("es-CO", {
    dateStyle: "short",
    timeStyle: "short"
  });

  const vista = {
    mensaje: document.getElementById("message"),
    actualizar: document.getElementById("actualizar"),
    tareas: document.getElementById("tareas"),
    sinTareas: document.getElementById("sin-tareas"),
    panelTarea: document.getElementById("panel-tarea"),
    tituloTarea: document.getElementById("titulo-tarea"),
    datosTarea: document.getElementById("datos-tarea"),
    formulario: document.getElementById("formulario"),
    completar: document.getElementById("completar"),
    soltar: document.getElementById("soltar"),
    cerrar: document.getElementById("cerrar"),
    seccionNomina: document.getElementById("seccion-nomina"),
    nomina: document.getElementById("nomina"),
    seccionBodega: document.getElementById("seccion-bodega"),
    actualizarBodega: document.getElementById("actualizar-bodega"),
    reservas: document.getElementById("reservas"),
    sinReservas: document.getElementById("sin-reservas"),
    solicitudes: document.getElementById("solicitudes"),
    sinSolicitudes: document.getElementById("sin-solicitudes"),
    simAutomatica: document.getElementById("sim-automatica"),
    simRepuestos: document.getElementById("sim-repuestos"),
    simNota: document.getElementById("sim-nota"),
    actividad: document.getElementById("actividad"),
    sinActividad: document.getElementById("sin-actividad")
  };

  let visor = null;
  let tareaAbierta = null;
  let ocupado = false;
  let temporizador = null;
  // Texto de lo último que se pintó: si la respuesta no cambió no se repinta la tabla.
  let huellaBandeja = null;
  let huellaBodega = null;

  // ---------------------------------------------------------------- utilidades

  function mostrar(texto, tipo, sinDesplazar) {
    vista.mensaje.className = tipo || "";
    // Vaciar primero hace que el lector de pantalla anuncie también un mensaje repetido.
    vista.mensaje.textContent = "";
    vista.mensaje.textContent = texto;
    // El mensaje está arriba de la página y los botones que lo provocan, más abajo.
    if (texto !== "" && !sinDesplazar) {
      vista.mensaje.scrollIntoView({ block: "nearest" });
    }
  }

  /** Traductor para form-js: recibe la plantilla en inglés y los valores de sus {marcadores}. */
  function traducir(plantilla, reemplazos) {
    const valores = reemplazos || {};
    const texto = TEXTOS_FORMULARIO[plantilla] || plantilla;
    return texto.replace(/{([^}]+)}/g, function (marcador, clave) {
      return clave in valores ? valores[clave] : marcador;
    });
  }

  function avisarError(fallo) {
    const texto = fallo && fallo.message ? fallo.message : "No fue posible completar la operación.";
    mostrar(texto, "error");
  }

  function puede(grupo) {
    const usuario = CB.user;
    if (!usuario) {
      return false;
    }
    return usuario.admin === true || usuario.groups.includes(grupo);
  }

  function haySesionActiva() {
    return CB.user !== null
      && document.documentElement.getAttribute("data-cb-sesion") === "activa";
  }

  function fecha(texto) {
    if (!texto) {
      return "";
    }
    // Camunda escribe la zona como +0000; los navegadores esperan +00:00.
    const normalizada = String(texto).replace(/([+-]\d{2})(\d{2})$/, "$1:$2");
    const instante = new Date(normalizada);
    if (Number.isNaN(instante.getTime())) {
      return String(texto);
    }
    return FORMATO_FECHA.format(instante);
  }

  function celda(contenido) {
    const td = document.createElement("td");
    td.append(contenido === null || contenido === undefined ? "" : contenido);
    return td;
  }

  function insignia(texto, clase) {
    const span = document.createElement("span");
    span.className = clase ? "insignia " + clase : "insignia";
    span.textContent = texto;
    return span;
  }

  function boton(texto, descripcion, alHacerClic) {
    const elemento = document.createElement("button");
    elemento.type = "button";
    elemento.textContent = texto;
    elemento.setAttribute("aria-label", texto + ": " + descripcion);
    elemento.addEventListener("click", alHacerClic);
    return elemento;
  }

  /** Ejecuta una acción de la persona evitando que se cruce con otra o con la actualización automática. */
  async function conAccion(accion) {
    if (ocupado) {
      return;
    }
    ocupado = true;
    try {
      await accion();
    } catch (fallo) {
      avisarError(fallo);
    } finally {
      ocupado = false;
    }
  }

  // ------------------------------------------------------------------- bandeja

  function rutaDeTarea(tarea, recurso) {
    return "/central/tasks/" + encodeURIComponent(tarea.id) + "/" + recurso;
  }

  function origenDeTarea(tarea) {
    if (tarea.assignee && tarea.assignee === CB.user.user) {
      return insignia("Asignada a mí", "info");
    }
    if (tarea.assignee) {
      return insignia("Asignada a " + tarea.assignee, "pendiente");
    }
    const grupos = tarea.candidateGroups || [];
    return grupos.length > 0 ? grupos.join(", ") : "Sin grupo";
  }

  function filaDeTarea(tarea) {
    const fila = document.createElement("tr");
    const texto = tarea.assignee ? "Abrir" : "Reclamar y abrir";
    const descripcion = (tarea.name || "tarea") + " del caso " + (tarea.businessKey || "sin clave");
    const abrir = boton(texto, descripcion, function () {
      conAccion(function () {
        return abrirTarea(tarea);
      });
    });
    abrir.dataset.tarea = tarea.id;
    fila.append(
      celda(tarea.name || tarea.taskDefinitionKey),
      celda(tarea.processName || tarea.processDefinitionKey),
      celda(tarea.businessKey),
      celda(origenDeTarea(tarea)),
      celda(fecha(tarea.created)),
      celda(abrir)
    );
    return fila;
  }

  function pintarBandeja(tareas) {
    // Si el foco estaba en el botón de una tarea, vuelve al mismo botón después de repintar.
    const enfocada = document.activeElement && document.activeElement.dataset
      ? document.activeElement.dataset.tarea
      : undefined;
    vista.tareas.replaceChildren(...tareas.map(filaDeTarea));
    vista.sinTareas.hidden = tareas.length > 0;
    if (enfocada) {
      const mismoBoton = Array.from(vista.tareas.querySelectorAll("button"))
        .find(function (candidato) {
          return candidato.dataset.tarea === enfocada;
        });
      if (mismoBoton) {
        mismoBoton.focus();
      }
    }
  }

  async function cargarBandeja() {
    const tareas = await CB.request("/central/tasks");
    const huella = JSON.stringify(tareas);
    if (huella === huellaBandeja) {
      return;
    }
    huellaBandeja = huella;
    pintarBandeja(tareas || []);
  }

  // --------------------------------------------------------------------- tarea

  /** De {nombre: {value, type}} (formato Camunda) a {nombre: valor}. */
  function valoresDe(variables) {
    const valores = {};
    for (const nombre of Object.keys(variables || {})) {
      const variable = variables[nombre];
      const esTipada = variable !== null && typeof variable === "object" && "value" in variable;
      valores[nombre] = esTipada ? variable.value : variable;
    }
    return valores;
  }

  // El tipo es orientativo: el servidor lo fija según el campo del formulario.
  function tipoDe(valor) {
    if (valor === null) {
      return "Null";
    }
    if (typeof valor === "boolean") {
      return "Boolean";
    }
    if (typeof valor === "number") {
      return Number.isInteger(valor) ? "Long" : "Double";
    }
    return "String";
  }

  function tipar(datos) {
    const variables = {};
    for (const nombre of Object.keys(datos || {})) {
      const valor = datos[nombre];
      if (valor === undefined) {
        continue;
      }
      variables[nombre] = { value: valor, type: tipoDe(valor) };
    }
    return variables;
  }

  function cerrarPanel() {
    if (visor) {
      visor.destroy();
      visor = null;
    }
    vista.formulario.replaceChildren();
    vista.panelTarea.hidden = true;
    tareaAbierta = null;
  }

  async function pintarFormulario(tarea, esquema, datos) {
    if (visor) {
      visor.destroy();
    }
    vista.formulario.replaceChildren();
    visor = new FormViewer.Form({
      container: vista.formulario,
      additionalModules: [{ translate: ["value", traducir] }]
    });
    // El esquema va tal cual, con sus valores por defecto: los datos del proceso tienen prioridad.
    await visor.importSchema(esquema, datos);

    vista.tituloTarea.textContent = tarea.name || "Tarea";
    const proceso = tarea.processName || tarea.processDefinitionKey || "Proceso";
    vista.datosTarea.textContent = proceso + " · caso " + (tarea.businessKey || "sin clave");
    // Una tarea sin grupo candidato (asignada por el proceso a una persona) no se puede soltar.
    vista.soltar.hidden = (tarea.candidateGroups || []).length === 0;
    vista.panelTarea.hidden = false;
    vista.panelTarea.scrollIntoView({ block: "start" });
    vista.tituloTarea.focus();
  }

  async function abrirTarea(tarea) {
    let reclamadaAqui = false;
    try {
      if (!tarea.assignee) {
        await CB.post(rutaDeTarea(tarea, "claim"), {});
        reclamadaAqui = true;
      }
      const respuestas = await Promise.all([
        CB.request(rutaDeTarea(tarea, "deployed-form")),
        CB.request(rutaDeTarea(tarea, "variables")),
        CB.request(rutaDeTarea(tarea, "form-variables"))
      ]);
      const esquema = respuestas[0];
      const datos = Object.assign({}, valoresDe(respuestas[1]), valoresDe(respuestas[2]));
      await pintarFormulario(tarea, esquema, datos);
      tareaAbierta = Object.assign({}, tarea, { assignee: tarea.assignee || CB.user.user });
      mostrar("", "");
    } catch (fallo) {
      cerrarPanel();
      if (reclamadaAqui) {
        // No se pudo abrir: la tarea vuelve a quedar libre para el grupo.
        try {
          await CB.post(rutaDeTarea(tarea, "unclaim"), {});
        } catch (falloAlSoltar) {
          // Se informa el primer error, que es el que explica lo ocurrido.
        }
      }
      avisarError(fallo);
    }
    await recargarTodo();
  }

  function mensajeTrasCompletar(respuesta, datos) {
    const nuevoResponsable = datos && datos.newOwner ? String(datos.newOwner) : "la persona indicada";
    if (respuesta && respuesta.assigned === true) {
      return {
        texto: "Tarea completada. La siguiente tarea de soporte quedó asignada a " + nuevoResponsable + ".",
        tipo: "ok"
      };
    }
    if (respuesta && respuesta.assigned === false) {
      return {
        texto: "Tarea completada, pero no fue posible asignar la siguiente tarea a " + nuevoResponsable
          + ". Revisa que el usuario exista y sea un empleado. La tarea quedó disponible para su grupo de soporte.",
        tipo: ""
      };
    }
    return { texto: "Tarea completada.", tipo: "ok" };
  }

  async function completarTarea() {
    if (!tareaAbierta || !visor) {
      return;
    }
    const resultado = visor.submit();
    if (Object.keys(resultado.errors || {}).length > 0) {
      // Sin desplazar: los campos con error están en el formulario, que debe seguir a la vista.
      mostrar("Revisa los campos señalados en el formulario.", "error", true);
      return;
    }
    let respuesta;
    try {
      respuesta = await CB.post(rutaDeTarea(tareaAbierta, "complete"), { variables: tipar(resultado.data) });
    } catch (fallo) {
      if (ESTADOS_QUE_CIERRAN_LA_TAREA.includes(fallo.status)) {
        cerrarPanel();
        await recargarTodo();
      }
      throw fallo;
    }
    const mensaje = mensajeTrasCompletar(respuesta, resultado.data);
    cerrarPanel();
    mostrar(mensaje.texto, mensaje.tipo);
    await recargarTodo();
  }

  async function soltarTarea() {
    if (!tareaAbierta) {
      return;
    }
    const tarea = tareaAbierta;
    await CB.post(rutaDeTarea(tarea, "unclaim"), {});
    cerrarPanel();
    mostrar("Soltaste la tarea «" + (tarea.name || "tarea") + "». Vuelve a estar disponible para su grupo.", "ok");
    await recargarTodo();
  }

  function cerrarSinCompletar() {
    if (!tareaAbierta) {
      return;
    }
    cerrarPanel();
    mostrar("Cerraste el formulario. La tarea sigue asignada a ti.", "");
    vista.actualizar.focus();
  }

  // -------------------------------------------------------------------- nómina

  function dosDigitos(numero) {
    return String(numero).padStart(2, "0");
  }

  /** Periodo y fecha de corte que corresponden hoy: la quincena o el mes en curso. */
  function nominaPorDefecto(ciclo, hoy) {
    const anio = hoy.getFullYear();
    const mes = dosDigitos(hoy.getMonth() + 1);
    const ultimoDia = new Date(anio, hoy.getMonth() + 1, 0).getDate();
    const finDeMes = anio + "-" + mes + "-" + dosDigitos(ultimoDia);
    if (ciclo === "MENSUAL") {
      return { periodo: anio + "-" + mes, corte: finDeMes };
    }
    if (hoy.getDate() <= 15) {
      return { periodo: anio + "-" + mes + "-Q1", corte: anio + "-" + mes + "-15" };
    }
    return { periodo: anio + "-" + mes + "-Q2", corte: finDeMes };
  }

  /** Propone valores sin pisar lo que la persona ya haya escrito. */
  function proponerNomina() {
    const campos = vista.nomina.elements;
    const propuesta = nominaPorDefecto(campos.payCycle.value, new Date());
    if (campos.payPeriodId.dataset.editado !== "si") {
      campos.payPeriodId.value = propuesta.periodo;
    }
    if (campos.cutoffDate.dataset.editado !== "si") {
      campos.cutoffDate.value = propuesta.corte;
    }
  }

  async function iniciarNomina() {
    const campos = vista.nomina.elements;
    const datos = {
      payPeriodId: campos.payPeriodId.value.trim(),
      payCycle: campos.payCycle.value,
      cutoffDate: campos.cutoffDate.value
    };
    for (const nombre of SIMULACIONES_NOMINA) {
      datos[nombre] = campos[nombre].checked;
    }
    const respuesta = await CB.post("/central/nomina", datos);
    // Se desmarcan para no repetir la simulación por descuido en el siguiente ciclo.
    for (const nombre of SIMULACIONES_NOMINA) {
      campos[nombre].checked = false;
    }
    mostrar("Ciclo de nómina iniciado: " + respuesta.businessKey + ".", "ok");
    await recargarTodo();
  }

  // -------------------------------------------------------------------- bodega

  function filaDeReserva(reserva) {
    const fila = document.createElement("tr");
    const avisar = boton("Avisar disponibilidad", "reserva " + reserva.id, function () {
      conAccion(async function () {
        await CB.post("/bodega/reservas/" + encodeURIComponent(reserva.id) + "/disponibles", {});
        mostrar("Se avisó al proceso " + (reserva.businessKey || "") + " que los repuestos están disponibles.", "ok");
        await recargarTodo();
      });
    });
    fila.append(
      celda(String(reserva.id)),
      celda(reserva.businessKey),
      celda(reserva.vin),
      celda(fecha(reserva.creadaEn)),
      celda(avisar)
    );
    return fila;
  }

  function filaDeSolicitud(solicitud) {
    const fila = document.createElement("tr");
    const nombre = solicitud.partRequestId || String(solicitud.id);
    const entregar = boton("Entregar", "solicitud " + nombre, function () {
      conAccion(async function () {
        await CB.post("/bodega/solicitudes/" + encodeURIComponent(solicitud.id) + "/entregar", {});
        mostrar("Repuesto " + (solicitud.partCode || "") + " entregado al taller.", "ok");
        await recargarTodo();
      });
    });
    fila.append(
      celda(nombre),
      celda(solicitud.businessKey),
      celda(solicitud.vin),
      celda(solicitud.partCode),
      celda(fecha(solicitud.solicitadaEn)),
      celda(entregar)
    );
    return fila;
  }

  function pintarSimulacion(simulacion) {
    if (!simulacion) {
      return;
    }
    vista.simAutomatica.checked = simulacion.respuestaAutomatica === true;
    vista.simRepuestos.checked = simulacion.repuestosDisponibles === true;
    if (typeof simulacion.retardoSegundos === "number") {
      vista.simNota.textContent = "Con respuesta automática, cada repuesto se entrega a los "
        + simulacion.retardoSegundos + " segundos de solicitarlo.";
    } else {
      vista.simNota.textContent = "";
    }
  }

  function pintarBodega(reservas, solicitudes, simulacion) {
    const pendientes = (reservas || []).filter(function (reserva) {
      return reserva.estado === ESTADO_RESERVA_PENDIENTE;
    });
    const porEntregar = (solicitudes || []).filter(function (solicitud) {
      return solicitud.estado === ESTADO_SOLICITUD_PENDIENTE;
    });
    vista.reservas.replaceChildren(...pendientes.map(filaDeReserva));
    vista.sinReservas.hidden = pendientes.length > 0;
    vista.solicitudes.replaceChildren(...porEntregar.map(filaDeSolicitud));
    vista.sinSolicitudes.hidden = porEntregar.length > 0;
    pintarSimulacion(simulacion);
  }

  async function cargarBodega() {
    if (!puede(GRUPO_BODEGA)) {
      return;
    }
    const respuestas = await Promise.all([
      CB.request("/bodega/reservas"),
      CB.request("/bodega/solicitudes"),
      CB.request("/bodega/simulacion")
    ]);
    const huella = JSON.stringify(respuestas);
    if (huella === huellaBodega) {
      return;
    }
    huellaBodega = huella;
    pintarBodega(respuestas[0], respuestas[1], respuestas[2]);
  }

  async function cambiarSimulacion(campo, casilla) {
    const cambio = {};
    cambio[campo] = casilla.checked;
    try {
      const simulacion = await CB.put("/bodega/simulacion", cambio);
      huellaBodega = null;
      pintarSimulacion(simulacion);
      mostrar("Simulación de la bodega actualizada.", "ok");
    } catch (fallo) {
      // El interruptor vuelve a mostrar lo que realmente tiene la bodega.
      casilla.checked = !casilla.checked;
      throw fallo;
    }
    await cargarActividad();
  }

  function alCambiarInterruptor(campo, casilla) {
    if (ocupado) {
      // Hay otra acción en curso y este cambio no se va a enviar: el interruptor no debe mentir.
      casilla.checked = !casilla.checked;
      return;
    }
    conAccion(function () {
      return cambiarSimulacion(campo, casilla);
    });
  }

  // ----------------------------------------------------------------- actividad

  function elementoDeActividad(actividad) {
    const elemento = document.createElement("li");
    const cuando = document.createElement("span");
    cuando.className = "actividad-fecha";
    cuando.textContent = fecha(actividad.fecha) + " · ";

    const partes = [NOMBRES_DE_ACCION[actividad.accion] || actividad.accion];
    if (actividad.tarea) {
      partes.push("«" + actividad.tarea + "»");
    }
    if (actividad.businessKey) {
      partes.push("(caso " + actividad.businessKey + ")");
    }
    let texto = partes.join(" ");
    if (actividad.detalle) {
      texto += ". " + actividad.detalle;
    }
    elemento.append(cuando, texto);
    return elemento;
  }

  async function cargarActividad() {
    const actividades = await CB.request("/central/actividad");
    vista.actividad.replaceChildren(...(actividades || []).map(elementoDeActividad));
    vista.sinActividad.hidden = (actividades || []).length > 0;
  }

  // -------------------------------------------------------------------- inicio

  async function recargarTodo() {
    await Promise.all([cargarBandeja(), cargarBodega(), cargarActividad()]);
  }

  function actualizarSola() {
    // Con un formulario abierto no se toca la página: la persona está escribiendo.
    if (!haySesionActiva() || document.hidden || tareaAbierta || ocupado) {
      return;
    }
    Promise.all([cargarBandeja(), cargarBodega()]).catch(function () {
      // Un fallo pasajero de la actualización automática no interrumpe a la persona.
    });
  }

  // Puede dispararse más de una vez (tras volver a entrar): deja la página como recién cargada.
  function alIniciarSesion() {
    cerrarPanel();
    huellaBandeja = null;
    huellaBodega = null;
    mostrar("", "");
    vista.seccionNomina.hidden = !puede(GRUPO_NOMINA);
    vista.seccionBodega.hidden = !puede(GRUPO_BODEGA);
    proponerNomina();
    recargarTodo().catch(avisarError);
    if (temporizador === null) {
      temporizador = window.setInterval(actualizarSola, INTERVALO_ACTUALIZACION_MS);
    }
  }

  function registrarEventos() {
    vista.actualizar.addEventListener("click", function () {
      conAccion(async function () {
        huellaBandeja = null;
        await Promise.all([cargarBandeja(), cargarActividad()]);
      });
    });
    vista.completar.addEventListener("click", function () {
      conAccion(completarTarea);
    });
    vista.soltar.addEventListener("click", function () {
      conAccion(soltarTarea);
    });
    vista.cerrar.addEventListener("click", cerrarSinCompletar);

    vista.nomina.addEventListener("submit", function (evento) {
      evento.preventDefault();
      conAccion(iniciarNomina);
    });
    vista.nomina.elements.payCycle.addEventListener("change", proponerNomina);
    for (const nombre of ["payPeriodId", "cutoffDate"]) {
      vista.nomina.elements[nombre].addEventListener("input", function (evento) {
        evento.target.dataset.editado = "si";
      });
    }

    vista.actualizarBodega.addEventListener("click", function () {
      conAccion(async function () {
        huellaBodega = null;
        await cargarBodega();
      });
    });
    vista.simAutomatica.addEventListener("change", function () {
      alCambiarInterruptor("respuestaAutomatica", vista.simAutomatica);
    });
    vista.simRepuestos.addEventListener("change", function () {
      alCambiarInterruptor("repuestosDisponibles", vista.simRepuestos);
    });

    window.addEventListener("cb-auth-ready", alIniciarSesion);
  }

  registrarEventos();
})();
