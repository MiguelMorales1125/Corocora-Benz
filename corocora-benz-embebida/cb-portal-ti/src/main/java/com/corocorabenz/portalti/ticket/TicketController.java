package com.corocorabenz.portalti.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API interna de tickets. La llama el motor con sus HTTP Connector y exige la credencial
 * interna (propiedad {@code corocora.rutas-internas}); el navegador no la usa.
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

  private final TicketRepository repository;
  private final EstadoTicketService estados;

  public TicketController(TicketRepository repository, EstadoTicketService estados) {
    this.repository = repository;
    this.estados = estados;
  }

  public record Nuevo(
      @NotBlank(message = "La businessKey es obligatoria")
      @Size(max = 255, message = "La businessKey admite hasta 255 caracteres")
      String businessKey,
      @NotBlank(message = "El usuario que reporta es obligatorio")
      @Size(max = 255, message = "El usuario que reporta admite hasta 255 caracteres")
      String requesterId,
      @NotBlank(message = "El sistema afectado es obligatorio")
      @Size(max = 255, message = "El sistema afectado admite hasta 255 caracteres")
      String systemName,
      @NotBlank(message = "La descripción es obligatoria")
      @Size(max = 4000, message = "La descripción admite hasta 4000 caracteres")
      String description,
      @NotBlank(message = "El impacto es obligatorio")
      @Size(max = 255, message = "El impacto admite hasta 255 caracteres")
      String impact,
      @NotBlank(message = "La urgencia es obligatoria")
      @Size(max = 255, message = "La urgencia admite hasta 255 caracteres")
      String urgency,
      @NotNull(message = "Indica si afecta la seguridad")
      Boolean safetyImpact,
      @NotNull(message = "Los equipos afectados son obligatorios")
      @Min(value = 1, message = "Los equipos afectados deben ser al menos 1")
      Integer affectedTeams) {
  }

  public record Solucion(
      @NotBlank(message = "La causa raíz es obligatoria")
      @Size(max = 4000, message = "La causa raíz admite hasta 4000 caracteres")
      String rootCause,
      @NotBlank(message = "El resumen de la solución es obligatorio")
      @Size(max = 4000, message = "El resumen de la solución admite hasta 4000 caracteres")
      String resolutionSummary) {
  }

  /**
   * Crea el ticket de una businessKey. Es idempotente: si el motor reintenta la llamada con
   * los mismos datos recibe el ticket que ya existe; con datos distintos, 409.
   */
  // synchronized: dos reintentos simultáneos no deben insertar dos veces la misma businessKey.
  @PostMapping
  public synchronized Map<String, Object> crear(@Valid @RequestBody Nuevo nuevo) {
    Ticket ticket = repository.findByBusinessKey(nuevo.businessKey()).orElse(null);
    if (ticket == null) {
      ticket = repository.save(desde(nuevo));
    } else if (!mismosDatos(ticket, nuevo)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave ya corresponde a otro ticket");
    }
    Map<String, Object> respuesta = new LinkedHashMap<>();
    respuesta.put("ticketId", ticket.getTicketId());
    respuesta.put("ticketStatus", ticket.getStatus());
    return respuesta;
  }

  @GetMapping
  public List<Ticket> listar(@RequestParam String requesterId) {
    return repository.findByRequesterIdOrderByIdDesc(requesterId).stream()
        .map(estados::actualizar)
        .toList();
  }

  @PostMapping("/{ticketId}/solucion")
  public Map<String, String> solucionar(@PathVariable String ticketId, @Valid @RequestBody Solucion solucion) {
    Ticket ticket = buscar(ticketId);
    exigirQueNoEsteCerrado(ticket);
    ticket.setRootCause(solucion.rootCause());
    ticket.setResolutionSummary(solucion.resolutionSummary());
    ticket.setStatus(Ticket.RESUELTO);
    repository.save(ticket);
    return Map.of("knowledgeArticleId", "KB-" + ticket.getTicketId());
  }

  @PostMapping("/{ticketId}/cerrar")
  public Map<String, String> cerrar(@PathVariable String ticketId) {
    Ticket ticket = buscar(ticketId);
    ticket.setStatus(Ticket.CERRADO);
    repository.save(ticket);
    return Map.of("ticketStatus", ticket.getStatus());
  }

  @PostMapping("/{ticketId}/reabrir")
  public Map<String, String> reabrir(@PathVariable String ticketId) {
    Ticket ticket = buscar(ticketId);
    exigirQueNoEsteCerrado(ticket);
    ticket.setStatus(Ticket.ABIERTO);
    repository.save(ticket);
    return Map.of("ticketStatus", ticket.getStatus());
  }

  private static Ticket desde(Nuevo nuevo) {
    Ticket ticket = new Ticket();
    ticket.setBusinessKey(nuevo.businessKey());
    ticket.setRequesterId(nuevo.requesterId());
    ticket.setSystemName(nuevo.systemName());
    ticket.setDescription(nuevo.description());
    ticket.setImpact(nuevo.impact());
    ticket.setUrgency(nuevo.urgency());
    ticket.setSafetyImpact(nuevo.safetyImpact());
    ticket.setAffectedTeams(nuevo.affectedTeams());
    return ticket;
  }

  private static boolean mismosDatos(Ticket ticket, Nuevo nuevo) {
    return Objects.equals(ticket.getRequesterId(), nuevo.requesterId())
        && Objects.equals(ticket.getSystemName(), nuevo.systemName())
        && Objects.equals(ticket.getDescription(), nuevo.description())
        && Objects.equals(ticket.getImpact(), nuevo.impact())
        && Objects.equals(ticket.getUrgency(), nuevo.urgency())
        && Objects.equals(ticket.getSafetyImpact(), nuevo.safetyImpact())
        && Objects.equals(ticket.getAffectedTeams(), nuevo.affectedTeams());
  }

  private static void exigirQueNoEsteCerrado(Ticket ticket) {
    if (Ticket.CERRADO.equals(ticket.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El ticket está cerrado");
    }
  }

  private Ticket buscar(String ticketId) {
    return Ticket.idInterno(ticketId)
        .flatMap(repository::findById)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no encontrado"));
  }
}
