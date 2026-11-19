package com.corocorabenz.tallerbodega.cotizacion;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pone al día el estado de las cotizaciones con lo que ya ocurrió en el motor.
 *
 * <p>La respuesta del cliente llega al proceso por el Portal de Clientes, no a este sistema;
 * por eso el estado se deduce del historial de la instancia en vez de agregar al BPMN una
 * llamada de vuelta a la bodega.
 */
@Service
public class EstadoCotizacionService {

  private static final Logger LOG = LoggerFactory.getLogger(EstadoCotizacionService.class);

  private static final String PROCESO_POSTVENTA = "CB_PostventaGarantias";
  private static final String ENVIADA = "ENVIADA";

  /** Evento del BPMN que cierra cada desenlace de la cotización y el estado que le corresponde. */
  private static final Map<String, String> ESTADO_POR_EVENTO = Map.of(
      "quoteAccepted", "ACEPTADA",
      "quoteRejected", "RECHAZADA",
      "quoteExpired", "VENCIDA");

  private final MotorClient motor;
  private final CotizacionRepository repository;

  public EstadoCotizacionService(MotorClient motor, CotizacionRepository repository) {
    this.motor = motor;
    this.repository = repository;
  }

  /**
   * Actualiza las cotizaciones que siguen ENVIADA. Si el motor no responde se devuelven
   * con el último estado conocido: consultar cotizaciones no debe fallar por eso.
   */
  public List<Cotizacion> actualizar(List<Cotizacion> cotizaciones) {
    List<Cotizacion> actualizadas = new ArrayList<>();
    boolean motorDisponible = true;
    for (Cotizacion cotizacion : cotizaciones) {
      if (!motorDisponible || !esperaRespuesta(cotizacion)) {
        actualizadas.add(cotizacion);
        continue;
      }
      try {
        actualizadas.add(actualizar(cotizacion));
      } catch (MotorNoDisponibleException e) {
        LOG.warn("El motor no responde; las cotizaciones se devuelven con su último estado conocido");
        // No se insiste con las demás: cada intento esperaría el tiempo de conexión completo.
        motorDisponible = false;
        actualizadas.add(cotizacion);
      }
    }
    return actualizadas;
  }

  private static boolean esperaRespuesta(Cotizacion cotizacion) {
    return ENVIADA.equals(cotizacion.getEstado()) && cotizacion.getBusinessKey() != null;
  }

  private Cotizacion actualizar(Cotizacion cotizacion) {
    try {
      String estado = estadoSegunElMotor(cotizacion.getBusinessKey());
      if (estado == null) {
        return cotizacion;
      }
      cotizacion.setEstado(estado);
      return repository.save(cotizacion);
    } catch (MotorException e) {
      LOG.warn("No se pudo reconciliar la cotización {}: el motor respondió HTTP {}",
          cotizacion.getQuoteId(), e.getStatus());
      return cotizacion;
    }
  }

  /** @return el nuevo estado, o {@code null} si el cliente aún no responde o no se puede saber */
  private String estadoSegunElMotor(String businessKey) {
    Map<String, Object> filtroInstancias = new LinkedHashMap<>();
    filtroInstancias.put("processInstanceBusinessKey", businessKey);
    filtroInstancias.put("processDefinitionKey", PROCESO_POSTVENTA);
    JsonNode instancias = motor.get("/history/process-instance", filtroInstancias);
    // Con cero o varias instancias no se sabe a cuál pertenece la cotización.
    if (instancias == null || instancias.size() != 1) {
      return null;
    }
    Map<String, Object> filtroActividades = new LinkedHashMap<>();
    filtroActividades.put("processInstanceId", instancias.get(0).path("id").asText());
    filtroActividades.put("finished", true);
    JsonNode actividades = motor.get("/history/activity-instance", filtroActividades);
    if (actividades == null) {
      return null;
    }
    for (JsonNode actividad : actividades) {
      String estado = ESTADO_POR_EVENTO.get(actividad.path("activityId").asText());
      if (estado != null) {
        return estado;
      }
    }
    return null;
  }
}
