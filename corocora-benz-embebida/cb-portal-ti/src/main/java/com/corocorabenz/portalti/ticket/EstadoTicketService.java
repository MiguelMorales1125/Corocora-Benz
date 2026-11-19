package com.corocorabenz.portalti.ticket;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pone al día el estado de un ticket resuelto que el proceso volvió a abrir.
 *
 * <p>Cuando la persona responde que la solución no sirvió, el proceso regresa a soporte
 * sin avisar a este portal. Se nota porque vuelve a haber una tarea de soporte para esa
 * businessKey mientras el ticket sigue figurando como resuelto.
 */
@Service
public class EstadoTicketService {

  private static final Logger LOG = LoggerFactory.getLogger(EstadoTicketService.class);
  private static final List<String> TAREAS_DE_SOPORTE = List.of("resolveN1", "resolveSpecialist", "reviewSla");

  private final MotorClient motor;
  private final TicketRepository repository;

  public EstadoTicketService(MotorClient motor, TicketRepository repository) {
    this.motor = motor;
    this.repository = repository;
  }

  public Ticket actualizar(Ticket ticket) {
    if (!Ticket.RESUELTO.equals(ticket.getStatus())) {
      return ticket;
    }
    try {
      if (!haySoportePendiente(ticket.getBusinessKey())) {
        return ticket;
      }
    } catch (MotorException | MotorNoDisponibleException e) {
      // Sin motor se conserva el último estado conocido: consultar tickets no debe fallar por eso.
      LOG.debug("No se pudo comprobar el estado de {}: {}", ticket.getBusinessKey(), e.getMessage());
      return ticket;
    }
    ticket.setStatus(Ticket.ABIERTO);
    return repository.save(ticket);
  }

  private boolean haySoportePendiente(String businessKey) {
    Map<String, Object> filtros = new LinkedHashMap<>();
    filtros.put("processInstanceBusinessKey", businessKey);
    filtros.put("taskDefinitionKeyIn", TAREAS_DE_SOPORTE);
    JsonNode tareas = motor.get("/task", filtros);
    return tareas != null && tareas.isArray() && !tareas.isEmpty();
  }
}
