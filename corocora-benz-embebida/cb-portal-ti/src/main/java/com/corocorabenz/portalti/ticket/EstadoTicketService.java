package com.corocorabenz.portalti.ticket;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class EstadoTicketService {
  private final RestClient engine;
  private final TicketRepository repository;
  public EstadoTicketService(@Value("${corocora.engine-rest}") String url, TicketRepository repository) {
    this.engine = RestClient.create(url); this.repository = repository;
  }
  public Ticket actualizar(Ticket ticket) {
    if (!"RESOLVED".equals(ticket.getStatus())) return ticket;
    try {
      var tasks = engine.get().uri(u -> u.path("/task").queryParam("processInstanceBusinessKey", ticket.getBusinessKey()).build())
          .retrieve().body(new ParameterizedTypeReference<List<Map<String,Object>>>() {});
      if (tasks != null && tasks.stream().anyMatch(t -> List.of("resolveN1", "resolveSpecialist", "reviewSla").contains(t.get("taskDefinitionKey")))) {
        ticket.setStatus("OPEN"); return repository.save(ticket);
      }
    } catch (RestClientException ignored) { /* conservar el último estado conocido */ }
    return ticket;
  }
}
