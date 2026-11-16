package com.corocorabenz.portalti.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
  private final TicketRepository repository;
  private final EstadoTicketService estados;
  public TicketController(TicketRepository repository, EstadoTicketService estados) { this.repository = repository; this.estados = estados; }
  public record Nuevo(@NotBlank @Size(max=255) String businessKey, @NotBlank @Size(max=255) String requesterId,
      @NotBlank @Size(max=255) String systemName, @NotBlank @Size(max=4000) String description,
      @NotBlank @Size(max=255) String impact, @NotBlank @Size(max=255) String urgency,
      @NotNull Boolean safetyImpact, @NotNull @Min(1) Integer affectedTeams) {}
  public record Solucion(@NotBlank @Size(max=4000) String rootCause, @NotBlank @Size(max=4000) String resolutionSummary) {}
  @PostMapping
  public synchronized Map<String, Object> crear(@Valid @RequestBody Nuevo n) {
    var encontrado = repository.findByBusinessKey(n.businessKey());
    if (encontrado.isPresent()) {
      Ticket anterior = encontrado.get();
      if (!java.util.Objects.equals(anterior.getRequesterId(), n.requesterId())
          || !java.util.Objects.equals(anterior.getSystemName(), n.systemName())
          || !java.util.Objects.equals(anterior.getDescription(), n.description())
          || !java.util.Objects.equals(anterior.getImpact(), n.impact())
          || !java.util.Objects.equals(anterior.getUrgency(), n.urgency())
          || !java.util.Objects.equals(anterior.getSafetyImpact(), n.safetyImpact())
          || !java.util.Objects.equals(anterior.getAffectedTeams(), n.affectedTeams()))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave ya corresponde a otro ticket");
    }
    Ticket t = encontrado.orElseGet(() -> {
      Ticket nuevo = new Ticket();
      nuevo.setBusinessKey(n.businessKey()); nuevo.setRequesterId(n.requesterId());
      nuevo.setSystemName(n.systemName()); nuevo.setDescription(n.description());
      nuevo.setImpact(n.impact()); nuevo.setUrgency(n.urgency());
      nuevo.setSafetyImpact(n.safetyImpact()); nuevo.setAffectedTeams(n.affectedTeams());
      return repository.save(nuevo);
    });
    return Map.of("ticketId", t.getTicketId(), "ticketStatus", t.getStatus());
  }
  @GetMapping
  public List<Ticket> listar(@RequestParam String requesterId) {
    return repository.findByRequesterIdOrderByIdDesc(requesterId).stream().map(estados::actualizar).toList();
  }
  @PostMapping("/{ticketId}/solucion")
  public Map<String, String> solucionar(@PathVariable String ticketId, @Valid @RequestBody Solucion s) {
    Ticket t = buscar(ticketId);
    if ("CLOSED".equals(t.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El ticket está cerrado"); t.setRootCause(s.rootCause()); t.setResolutionSummary(s.resolutionSummary());
    t.setStatus("RESOLVED"); repository.save(t);
    return Map.of("knowledgeArticleId", "KB-" + t.getTicketId());
  }
  @PostMapping("/{ticketId}/cerrar")
  public Map<String, String> cerrar(@PathVariable String ticketId) {
    Ticket t = buscar(ticketId); t.setStatus("CLOSED"); repository.save(t);
    return Map.of("ticketStatus", t.getStatus());
  }
  @PostMapping("/{ticketId}/reabrir")
  public Map<String, String> reabrir(@PathVariable String ticketId) {
    Ticket t = buscar(ticketId);
    if ("CLOSED".equals(t.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El ticket está cerrado");
    t.setStatus("OPEN"); repository.save(t); return Map.of("ticketStatus", t.getStatus());
  }
  private Ticket buscar(String ticketId) {
    try {
      if (!ticketId.startsWith("TI-")) throw new NumberFormatException();
      return repository.findById(Long.parseLong(ticketId.substring(3)))
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no encontrado"));
    } catch (NumberFormatException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no encontrado"); }
  }
}
