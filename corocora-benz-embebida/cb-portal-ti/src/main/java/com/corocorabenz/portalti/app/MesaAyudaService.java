package com.corocorabenz.portalti.app;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.corocorabenz.portalti.ticket.EstadoTicketService;
import com.corocorabenz.portalti.ticket.Ticket;
import com.corocorabenz.portalti.ticket.TicketRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lo que una persona hace en la mesa de ayuda: reportar un incidente, ver sus tickets y
 * confirmar la solución. Habla con el motor siempre a través de {@link MotorClient}.
 */
@Service
public class MesaAyudaService {

  static final String PROCESO = "CB_GestionIncidenteTI";
  static final String TAREA_CONFIRMAR = "confirmSolution";
  static final String PREFIJO_CLAVE = "TI-";

  private static final Logger LOG = LoggerFactory.getLogger(MesaAyudaService.class);
  private static final int NO_ENCONTRADO = 404;
  private static final int LARGO_CLAVE = 8;
  // Sin 0, O, 1 ni I, que se confunden al leer o dictar la clave.
  private static final String ALFABETO_CLAVE = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final String SIN_CONFIRMACION_PENDIENTE = "Este ticket no tiene una confirmación pendiente";

  private final MotorClient motor;
  private final TicketRepository tickets;
  private final EstadoTicketService estados;
  private final SecureRandom azar = new SecureRandom();

  public MesaAyudaService(MotorClient motor, TicketRepository tickets, EstadoTicketService estados) {
    this.motor = motor;
    this.tickets = tickets;
    this.estados = estados;
  }

  /**
   * Inicia el proceso de incidentes a nombre de esa persona. El ticket no se crea aquí:
   * lo crea el motor un momento después, llamando a la API interna de tickets.
   *
   * @return la businessKey del incidente
   */
  public String reportar(String usuario, ReporteIncidente reporte) {
    String businessKey = nuevaClave();

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("requesterId", usuario);
    variables.put("systemName", reporte.systemName());
    variables.put("description", reporte.description());
    variables.put("impact", reporte.impact());
    variables.put("urgency", reporte.urgency());
    variables.put("safetyImpact", reporte.safetyImpact());
    variables.put("affectedTeams", reporte.affectedTeams().longValueExact());
    if (reporte.tieneCorreo()) {
      variables.put("notificationEmail", reporte.notificationEmail().trim());
    }

    Map<String, Object> cuerpo = new LinkedHashMap<>();
    cuerpo.put("businessKey", businessKey);
    cuerpo.put("variables", MotorClient.tipar(variables));
    motor.post("/process-definition/key/" + PROCESO + "/start", cuerpo);

    LOG.info("Incidente {} reportado por {}", businessKey, usuario);
    return businessKey;
  }

  public List<TicketVista> ticketsDe(String usuario) {
    List<Ticket> suyos = tickets.findByRequesterIdOrderByIdDesc(usuario).stream()
        .map(estados::actualizar)
        .toList();
    if (suyos.isEmpty()) {
      return List.of();
    }
    Set<String> porConfirmar = clavesPorConfirmar(usuario);
    return suyos.stream()
        .map(ticket -> TicketVista.de(ticket, porConfirmar.contains(ticket.getBusinessKey())))
        .toList();
  }

  /**
   * Completa la tarea "Confirmar solución" del ticket con la respuesta de la persona.
   *
   * @throws ResponseStatusException 404 si el ticket no existe o es de otra persona;
   *     409 si el proceso no espera esa confirmación
   */
  public void confirmar(String usuario, String ticketId, boolean confirmado) {
    // Un ticket ajeno responde igual que uno inexistente: no se revela que existe.
    Ticket ticket = Ticket.idInterno(ticketId)
        .flatMap(tickets::findById)
        .filter(candidato -> usuario.equals(candidato.getRequesterId()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no encontrado"));

    String tarea = tareaDeConfirmacion(usuario, ticket.getBusinessKey())
        .orElseThrow(MesaAyudaService::sinConfirmacionPendiente);

    Map<String, Object> respuesta = new LinkedHashMap<>();
    respuesta.put("userConfirmed", confirmado);
    Map<String, Object> cuerpo = new LinkedHashMap<>();
    cuerpo.put("variables", MotorClient.tipar(respuesta));
    try {
      motor.post("/task/" + tarea + "/complete", cuerpo);
    } catch (MotorException e) {
      // Entre la consulta y este punto la tarea pudo cerrarse (doble clic, vencimiento de 24 h).
      if (!existeTarea(tarea)) {
        throw sinConfirmacionPendiente();
      }
      throw e;
    }
    LOG.info("{} respondió {} a la solución de {}", usuario, confirmado, ticket.getBusinessKey());
  }

  /**
   * businessKey de los incidentes cuya tarea "Confirmar solución" está asignada a esa persona.
   * Son dos consultas en total, sin importar cuántos tickets tenga.
   */
  private Set<String> clavesPorConfirmar(String usuario) {
    try {
      Map<String, Object> filtros = new LinkedHashMap<>();
      filtros.put("assignee", usuario);
      filtros.put("taskDefinitionKey", TAREA_CONFIRMAR);
      List<String> instancias = textos(motor.get("/task", filtros), "processInstanceId");
      if (instancias.isEmpty()) {
        return Set.of();
      }
      JsonNode procesos = motor.get("/process-instance", Map.of("processInstanceIds", instancias));
      return new HashSet<>(textos(procesos, "businessKey"));
    } catch (MotorException | MotorNoDisponibleException e) {
      // Los tickets viven en este portal: sin motor se muestran igual, solo que sin botones de confirmar.
      LOG.warn("No se pudieron consultar las confirmaciones pendientes de {}: {}", usuario, e.getMessage());
      return Set.of();
    }
  }

  private Optional<String> tareaDeConfirmacion(String usuario, String businessKey) {
    Map<String, Object> filtros = new LinkedHashMap<>();
    filtros.put("assignee", usuario);
    filtros.put("taskDefinitionKey", TAREA_CONFIRMAR);
    filtros.put("processInstanceBusinessKey", businessKey);
    return textos(motor.get("/task", filtros), "id").stream().findFirst();
  }

  private boolean existeTarea(String tarea) {
    try {
      motor.get("/task/" + tarea);
      return true;
    } catch (MotorException e) {
      if (e.getStatus() == NO_ENCONTRADO) {
        return false;
      }
      throw e;
    }
  }

  /** Valores no vacíos de un campo en una lista JSON; lista vacía si la respuesta no es una lista. */
  private static List<String> textos(JsonNode lista, String campo) {
    List<String> valores = new ArrayList<>();
    if (lista == null || !lista.isArray()) {
      return valores;
    }
    for (JsonNode elemento : lista) {
      String valor = elemento.path(campo).asText("");
      if (!valor.isEmpty()) {
        valores.add(valor);
      }
    }
    return valores;
  }

  private String nuevaClave() {
    String clave = claveAlAzar();
    // El ticket guarda la businessKey como única: se descarta una que ya esté en uso.
    while (tickets.findByBusinessKey(clave).isPresent()) {
      clave = claveAlAzar();
    }
    return clave;
  }

  private String claveAlAzar() {
    StringBuilder clave = new StringBuilder(PREFIJO_CLAVE);
    for (int posicion = 0; posicion < LARGO_CLAVE; posicion++) {
      clave.append(ALFABETO_CLAVE.charAt(azar.nextInt(ALFABETO_CLAVE.length())));
    }
    return clave.toString();
  }

  private static ResponseStatusException sinConfirmacionPendiente() {
    return new ResponseStatusException(HttpStatus.CONFLICT, SIN_CONFIRMACION_PENDIENTE);
  }
}
