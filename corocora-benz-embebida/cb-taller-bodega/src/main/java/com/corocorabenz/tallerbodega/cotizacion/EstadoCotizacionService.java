package com.corocorabenz.tallerbodega.cotizacion;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Reconcilia el estado con los eventos ya confirmados en Camunda, sin modificar los modelos. */
@Service
public class EstadoCotizacionService {
  private static final Logger LOG = LoggerFactory.getLogger(EstadoCotizacionService.class);
  private static final ParameterizedTypeReference<List<Map<String,Object>>> LISTA = new ParameterizedTypeReference<>() {};
  private final RestClient engine;
  private final CotizacionRepository repository;
  public EstadoCotizacionService(@Value("${corocora.engine-rest}") String url, CotizacionRepository repository) {
    this.engine = RestClient.create(url); this.repository = repository;
  }
  public Cotizacion actualizar(Cotizacion c) {
    if (!"ENVIADA".equals(c.getEstado()) || c.getBusinessKey() == null) return c;
    try {
      List<Map<String,Object>> instancias = engine.get().uri(u -> u.path("/history/process-instance")
          .queryParam("processInstanceBusinessKey", c.getBusinessKey())
          .queryParam("processDefinitionKey", "CB_PostventaGarantias").build()).retrieve().body(LISTA);
      if (instancias == null || instancias.size() != 1) return c;
      String id = String.valueOf(instancias.get(0).get("id"));
      List<Map<String,Object>> actividades = engine.get().uri(u -> u.path("/history/activity-instance")
          .queryParam("processInstanceId", id).queryParam("finished", true).build()).retrieve().body(LISTA);
      if (actividades == null) return c;
      for (Map<String,Object> a : actividades) {
        String estado = switch (String.valueOf(a.get("activityId"))) {
          case "quoteAccepted" -> "ACEPTADA";
          case "quoteRejected" -> "RECHAZADA";
          case "quoteExpired" -> "VENCIDA";
          default -> null;
        };
        if (estado != null) { c.setEstado(estado); return repository.save(c); }
      }
    } catch (RestClientException ex) { LOG.warn("No se pudo reconciliar cotización {}: {}", c.getQuoteId(), ex.getMessage()); }
    return c;
  }
}
