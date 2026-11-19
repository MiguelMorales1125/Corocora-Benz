package com.corocorabenz.centralsys.tareas;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Lecturas del motor que comparten la bandeja, los permisos y las acciones sobre tareas. */
@Component
public class MotorTareas {

  private static final int NO_ENCONTRADO = 404;
  private static final int SOLICITUD_INCORRECTA = 400;
  private static final int LOTE_INSTANCIAS = 50;

  // El identificador llega del navegador: lo que no parezca un id de Camunda ni se consulta.
  private static final Pattern ID_VALIDO = Pattern.compile("[A-Za-z0-9:_-]{1,64}");

  private final MotorClient motor;

  // Una definición desplegada no cambia: su id incluye la versión.
  private final Map<String, DefinicionProceso> definiciones = new ConcurrentHashMap<>();

  public MotorTareas(MotorClient motor) {
    this.motor = motor;
  }

  /** @throws ResponseStatusException 404 si la tarea no existe (o ya se completó) */
  public Tarea buscar(String id) {
    if (id == null || !ID_VALIDO.matcher(id).matches()) {
      throw tareaInexistente();
    }
    JsonNode nodo;
    try {
      nodo = motor.get("/task/" + id);
    } catch (MotorException e) {
      if (e.getStatus() == NO_ENCONTRADO) {
        throw tareaInexistente();
      }
      throw e;
    }
    if (nodo == null || nodo.path("id").isMissingNode()) {
      throw tareaInexistente();
    }
    return Tarea.de(nodo);
  }

  public List<Tarea> consultar(Map<String, ?> filtros) {
    List<Tarea> tareas = new ArrayList<>();
    JsonNode respuesta = motor.get("/task", filtros);
    if (respuesta == null) {
      return tareas;
    }
    for (JsonNode nodo : respuesta) {
      tareas.add(Tarea.de(nodo));
    }
    return tareas;
  }

  public List<String> gruposCandidatos(String tareaId) {
    List<String> grupos = new ArrayList<>();
    JsonNode enlaces = motor.get("/task/" + tareaId + "/identity-links", Map.of("type", "candidate"));
    if (enlaces == null) {
      return grupos;
    }
    for (JsonNode enlace : enlaces) {
      String grupo = Tarea.texto(enlace, "groupId");
      if (grupo != null && !grupos.contains(grupo)) {
        grupos.add(grupo);
      }
    }
    return grupos;
  }

  /** businessKey de cada instancia, pidiéndolas al motor por lotes y no una a una. */
  public Map<String, String> clavesDeNegocio(Collection<String> instancias) {
    Map<String, String> claves = new HashMap<>();
    List<String> pendientes = new ArrayList<>(instancias);
    for (int inicio = 0; inicio < pendientes.size(); inicio += LOTE_INSTANCIAS) {
      int fin = Math.min(inicio + LOTE_INSTANCIAS, pendientes.size());
      Map<String, Object> filtros = new LinkedHashMap<>();
      filtros.put("processInstanceIds", pendientes.subList(inicio, fin));
      JsonNode respuesta = motor.get("/process-instance", filtros);
      if (respuesta == null) {
        continue;
      }
      for (JsonNode instancia : respuesta) {
        claves.put(Tarea.texto(instancia, "id"), Tarea.texto(instancia, "businessKey"));
      }
    }
    return claves;
  }

  public String claveDeNegocio(String instancia) {
    if (instancia == null) {
      return null;
    }
    return clavesDeNegocio(List.of(instancia)).get(instancia);
  }

  public DefinicionProceso definicion(String id) {
    if (id == null) {
      return DefinicionProceso.DESCONOCIDA;
    }
    DefinicionProceso conocida = definiciones.get(id);
    if (conocida != null) {
      return conocida;
    }
    DefinicionProceso leida = leerDefinicion(id);
    definiciones.put(id, leida);
    return leida;
  }

  /** @return el esquema del formulario desplegado, o {@code null} si la tarea no tiene uno */
  public JsonNode formularioDesplegado(String tareaId) {
    try {
      return motor.get("/task/" + tareaId + "/deployed-form");
    } catch (MotorException e) {
      // Camunda responde 404 si no hay formulario desplegado y 400 si la tarea no usa uno.
      if (e.getStatus() == NO_ENCONTRADO || e.getStatus() == SOLICITUD_INCORRECTA) {
        return null;
      }
      throw e;
    }
  }

  private DefinicionProceso leerDefinicion(String id) {
    try {
      JsonNode nodo = motor.get("/process-definition/" + id);
      if (nodo == null) {
        return DefinicionProceso.DESCONOCIDA;
      }
      return new DefinicionProceso(Tarea.texto(nodo, "key"), Tarea.texto(nodo, "name"));
    } catch (MotorException e) {
      if (e.getStatus() == NO_ENCONTRADO) {
        return DefinicionProceso.DESCONOCIDA;
      }
      throw e;
    }
  }

  static ResponseStatusException tareaInexistente() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "La tarea no existe o ya fue completada");
  }
}
