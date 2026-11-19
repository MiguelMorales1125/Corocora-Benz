package com.corocorabenz.centralsys.tareas;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.corocorabenz.comun.portal.UsuarioPortal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tras revisar un incidente con el plazo vencido (tarea {@code reviewSla}) el responsable de TI
 * puede nombrar a quien lo atenderá: a esa persona se le asigna la siguiente tarea de soporte
 * del mismo incidente.
 */
@Component
public class Reasignacion {

  static final String TAREA_REVISION = "reviewSla";
  static final String VARIABLE_NUEVO_RESPONSABLE = "newOwner";

  private static final Logger LOG = LoggerFactory.getLogger(Reasignacion.class);
  private static final List<String> TAREAS_DE_SOPORTE = List.of("resolveN1", "resolveSpecialist");
  private static final int LARGO_MAXIMO_USUARIO = 64;

  private final MotorClient motor;
  private final MotorTareas motorTareas;
  private final long esperaMs;
  private final long intervaloMs;

  public Reasignacion(
      MotorClient motor,
      MotorTareas motorTareas,
      @Value("${corocora.central.espera-reasignacion-ms:5000}") long esperaMs,
      @Value("${corocora.central.intervalo-sondeo-ms:250}") long intervaloMs) {
    this.motor = motor;
    this.motorTareas = motorTareas;
    this.esperaMs = esperaMs;
    this.intervaloMs = Math.max(10, intervaloMs);
  }

  /**
   * @return {@code true} si la siguiente tarea de soporte quedó asignada a esa persona. Nunca
   *     lanza: la tarea de revisión ya está completada y eso no se puede deshacer.
   */
  public boolean asignarSiguiente(String businessKey, String nuevoResponsable) {
    if (businessKey == null) {
      return false;
    }
    try {
      if (!esEmpleadoExistente(nuevoResponsable)) {
        LOG.info("No se reasigna {}: {} no existe o es una cuenta de cliente", businessKey, nuevoResponsable);
        return false;
      }
      String tareaId = esperarTareaDeSoporte(businessKey);
      if (tareaId == null) {
        LOG.info("No se reasigna {}: no apareció la siguiente tarea de soporte", businessKey);
        return false;
      }
      motor.post("/task/" + tareaId + "/assignee", Map.of("userId", nuevoResponsable));
      return true;
    } catch (MotorException | MotorNoDisponibleException e) {
      LOG.warn("No fue posible reasignar {} a {}: {}", businessKey, nuevoResponsable, e.getMessage());
      return false;
    }
  }

  private boolean esEmpleadoExistente(String usuario) {
    if (usuario.length() > LARGO_MAXIMO_USUARIO) {
      return false;
    }
    JsonNode usuarios = motor.get("/user", Map.of("id", usuario));
    if (usuarios == null || !usuarios.isArray() || usuarios.isEmpty()) {
      return false;
    }
    JsonNode grupos = motor.get("/group", Map.of("member", usuario));
    if (grupos == null) {
      return true;
    }
    for (JsonNode grupo : grupos) {
      if (UsuarioPortal.GRUPO_CLIENTES.equals(grupo.path("id").asText())) {
        return false;
      }
    }
    return true;
  }

  /** El motor puede tardar un momento en crear la tarea: se sondea hasta que aparezca o venza la espera. */
  private String esperarTareaDeSoporte(String businessKey) {
    long limite = System.nanoTime() + esperaMs * 1_000_000L;
    while (true) {
      String tareaId = tareaDeSoporte(businessKey);
      if (tareaId != null) {
        return tareaId;
      }
      if (System.nanoTime() >= limite) {
        return null;
      }
      try {
        Thread.sleep(intervaloMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
  }

  private String tareaDeSoporte(String businessKey) {
    Map<String, Object> filtros = new LinkedHashMap<>();
    filtros.put("processInstanceBusinessKey", businessKey);
    filtros.put("taskDefinitionKeyIn", TAREAS_DE_SOPORTE);
    // Si hubiera más de una, la más reciente es la que acaba de crear el proceso.
    filtros.put("sortBy", "created");
    filtros.put("sortOrder", "desc");
    List<Tarea> tareas = motorTareas.consultar(filtros);
    return tareas.isEmpty() ? null : tareas.get(0).id();
  }
}
