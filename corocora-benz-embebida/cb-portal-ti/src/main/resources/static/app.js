/*
 * Mesa de ayuda TI: reportar un incidente, ver "Mis tickets" y confirmar la solución.
 * Solo habla con /api/app/ti de este portal, a través de CB (auth.js).
 */
(function () {
  "use strict";

  // El motor crea el ticket un momento después de recibir el reporte, así que la lista
  // se vuelve a pedir unas cuantas veces hasta que el cambio esperado aparece.
  const ESPERA_ENTRE_RECARGAS_MS = 2500;
  const RECARGAS_MAXIMAS = 6;

  const ESTADOS = {
    OPEN: { texto: "En atención", clase: "pendiente" },
    RESOLVED: { texto: "Solución registrada", clase: "info" },
    CLOSED: { texto: "Cerrado", clase: "ok" }
  };
  const ESTADO_POR_CONFIRMAR = { texto: "Esperando tu confirmación", clase: "info" };

  const mensaje = document.querySelector("#message");
  const formulario = document.querySelector("#report");
  const botonActualizar = document.querySelector("#refresh");
  const tabla = document.querySelector("#tickets-table");
  const filas = document.querySelector("#tickets");
  const avisoSinTickets = document.querySelector("#empty");

  let ultimaCarga = 0;
  let seguimiento = null;

  // ---------------------------------------------------------------- mensajes

  function mostrar(texto, tipo) {
    mensaje.className = tipo || "";
    mensaje.textContent = texto;
    // El mensaje está arriba de la página y los botones que lo provocan, más abajo.
    if (texto !== "") {
      mensaje.scrollIntoView({ block: "nearest" });
    }
  }

  function mostrarExito(texto) {
    mostrar(texto, "ok");
  }

  function mostrarError(fallo) {
    const texto = fallo && fallo.message ? fallo.message : "No fue posible completar la operación.";
    mostrar(texto, "error");
  }

  // ----------------------------------------------------------------- tickets

  async function cargarTickets() {
    const carga = ++ultimaCarga;
    const tickets = await CB.request("/ti/tickets");
    // Si mientras tanto se pidió otra carga, esta respuesta ya es vieja y no se pinta.
    if (carga === ultimaCarga) {
      pintarTickets(tickets);
    }
    return tickets;
  }

  function pintarTickets(tickets) {
    filas.replaceChildren();
    for (const ticket of tickets) {
      filas.append(filaDe(ticket));
    }
    tabla.hidden = tickets.length === 0;
    avisoSinTickets.hidden = tickets.length > 0;
  }

  // Mientras llega la primera respuesta no se muestra ni la tabla ni el aviso de "sin tickets".
  function vaciarTickets() {
    filas.replaceChildren();
    tabla.hidden = true;
    avisoSinTickets.hidden = true;
  }

  function filaDe(ticket) {
    const fila = document.createElement("tr");
    fila.append(
      celdaDeIdentificacion(ticket),
      celdaDeTexto(ticket.systemName),
      celdaDeTexto(ticket.description),
      celdaDeEstado(ticket),
      celdaDeSolucion(ticket),
      celdaDeAcciones(ticket)
    );
    return fila;
  }

  function celdaDeTexto(texto) {
    const celda = document.createElement("td");
    celda.textContent = texto || "—";
    return celda;
  }

  function celdaDeIdentificacion(ticket) {
    const celda = document.createElement("td");
    const numero = document.createElement("strong");
    numero.textContent = ticket.ticketId;
    const clave = document.createElement("div");
    clave.className = "nota";
    clave.textContent = ticket.businessKey;
    celda.append(numero, clave);
    return celda;
  }

  function celdaDeEstado(ticket) {
    const estado = estadoLegible(ticket);
    const insignia = document.createElement("span");
    insignia.className = "insignia " + estado.clase;
    insignia.textContent = estado.texto;
    const celda = document.createElement("td");
    celda.append(insignia);
    return celda;
  }

  function estadoLegible(ticket) {
    if (ticket.canConfirm) {
      return ESTADO_POR_CONFIRMAR;
    }
    return ESTADOS[ticket.status] || { texto: ticket.status || "Sin estado", clase: "" };
  }

  function celdaDeSolucion(ticket) {
    const celda = document.createElement("td");
    if (!ticket.resolutionSummary) {
      celda.textContent = "Pendiente";
      return celda;
    }
    const resumen = document.createElement("div");
    // Un ticket que volvió a soporte conserva la solución que no sirvió hasta que registren otra.
    resumen.textContent = ticket.status === "OPEN"
      ? "Solución anterior: " + ticket.resolutionSummary
      : ticket.resolutionSummary;
    celda.append(resumen);
    if (ticket.rootCause) {
      const causa = document.createElement("div");
      causa.className = "nota";
      causa.textContent = "Causa: " + ticket.rootCause;
      celda.append(causa);
    }
    return celda;
  }

  function celdaDeAcciones(ticket) {
    const celda = document.createElement("td");
    if (!ticket.canConfirm) {
      celda.textContent = "—";
      return celda;
    }
    const confirmar = botonDeRespuesta(ticket, "Confirmar solución", true);
    const rechazar = botonDeRespuesta(ticket, "No se resolvió", false);
    rechazar.className = "secundario";
    celda.append(confirmar, rechazar);
    return celda;
  }

  function botonDeRespuesta(ticket, texto, confirmado) {
    const boton = document.createElement("button");
    boton.type = "button";
    boton.textContent = texto;
    // La tabla repite los mismos dos botones en cada fila: el nombre accesible dice de cuál ticket son.
    boton.setAttribute("aria-label", texto + ": ticket " + ticket.ticketId);
    boton.addEventListener("click", function () {
      responder(ticket, confirmado, boton.closest("td"));
    });
    return boton;
  }

  async function responder(ticket, confirmado, celda) {
    const botones = celda.querySelectorAll("button");
    activar(botones, false);
    try {
      await CB.post("/ti/tickets/" + encodeURIComponent(ticket.ticketId) + "/confirm", { confirmed: confirmado });
    } catch (fallo) {
      mostrarError(fallo);
      activar(botones, true);
      // Un 409 significa que ya no hay nada que confirmar: la lista quedó vieja.
      if (fallo.status === 409 || fallo.status === 404) {
        recargarEnSilencio();
      }
      return;
    }
    mostrarExito(confirmado
      ? "Gracias. El ticket " + ticket.ticketId + " se cerrará en un momento."
      : "Entendido. El ticket " + ticket.ticketId + " vuelve a soporte.");
    await recargarEnSilencio();
    seguirHasta(function (tickets) {
      const actual = tickets.find(function (otro) {
        return otro.ticketId === ticket.ticketId;
      });
      return !actual || actual.status !== ticket.status;
    });
  }

  function activar(botones, activos) {
    for (const boton of botones) {
      boton.disabled = !activos;
    }
  }

  // ------------------------------------------------------------- seguimiento

  // Para las recargas que nadie pidió: un fallo no debe tapar el mensaje que ya se mostró.
  async function recargarEnSilencio() {
    try {
      return await cargarTickets();
    } catch (fallo) {
      return null;
    }
  }

  function seguirHasta(yaAparecio) {
    dejarDeSeguir();
    let recargas = 0;
    const revisar = async function () {
      recargas += 1;
      const tickets = await recargarEnSilencio();
      const terminado = tickets === null || yaAparecio(tickets) || recargas >= RECARGAS_MAXIMAS;
      seguimiento = terminado ? null : window.setTimeout(revisar, ESPERA_ENTRE_RECARGAS_MS);
    };
    seguimiento = window.setTimeout(revisar, ESPERA_ENTRE_RECARGAS_MS);
  }

  function dejarDeSeguir() {
    if (seguimiento !== null) {
      window.clearTimeout(seguimiento);
      seguimiento = null;
    }
  }

  // ----------------------------------------------------------------- reporte

  function leerReporte() {
    const datos = new FormData(formulario);
    const reporte = {
      systemName: datos.get("systemName"),
      description: datos.get("description").trim(),
      impact: datos.get("impact"),
      urgency: datos.get("urgency"),
      affectedTeams: Number(datos.get("affectedTeams")),
      safetyImpact: datos.has("safetyImpact")
    };
    const correo = datos.get("notificationEmail").trim();
    if (correo !== "") {
      reporte.notificationEmail = correo;
    }
    return reporte;
  }

  async function enviarReporte(evento) {
    evento.preventDefault();
    const boton = formulario.querySelector("button[type=submit]");
    boton.disabled = true;
    try {
      const resultado = await CB.post("/ti/reports", leerReporte());
      formulario.reset();
      mostrarExito("Reporte enviado con la clave " + resultado.businessKey
        + ". El ticket aparecerá en \"Mis tickets\" en unos segundos.");
      seguirHasta(function (tickets) {
        return tickets.some(function (ticket) {
          return ticket.businessKey === resultado.businessKey;
        });
      });
    } catch (fallo) {
      mostrarError(fallo);
    } finally {
      boton.disabled = false;
    }
  }

  async function actualizar() {
    botonActualizar.disabled = true;
    try {
      await cargarTickets();
      mostrarExito("Lista de tickets actualizada.");
    } catch (fallo) {
      mostrarError(fallo);
    } finally {
      botonActualizar.disabled = false;
    }
  }

  // ------------------------------------------------------------------ inicio

  formulario.addEventListener("submit", enviarReporte);
  botonActualizar.addEventListener("click", actualizar);

  // Puede dispararse más de una vez (al volver a entrar tras perder la sesión):
  // cada vez se parte de cero con los tickets de quien acaba de entrar.
  window.addEventListener("cb-auth-ready", function () {
    dejarDeSeguir();
    mostrar("", "");
    vaciarTickets();
    cargarTickets().catch(mostrarError);
  });
})();
