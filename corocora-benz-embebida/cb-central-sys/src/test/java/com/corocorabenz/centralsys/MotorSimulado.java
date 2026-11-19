package com.corocorabenz.centralsys;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Motor de mentira para las pruebas: guarda tareas en memoria y responde como la REST de
 * Camunda a las pocas rutas que usa CentralSys. Se conecta a un MotorClient simulado con Mockito.
 */
class MotorSimulado {

  static final String CLAVE = "demo";
  static final String DEFINICION_TI = "CB_GestionIncidenteTI:1:def";

  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, List<String>> gruposPorUsuario = new LinkedHashMap<>();
  private final Map<String, ObjectNode> tareas = new LinkedHashMap<>();
  private final Map<String, List<String>> candidatos = new HashMap<>();
  private final Map<String, JsonNode> formularios = new HashMap<>();
  private final Map<String, String> clavesDeNegocio = new HashMap<>();
  private final Set<String> seEsfuman = new HashSet<>();
  private final Set<String> fallanAlCompletar = new HashSet<>();

  /** Variables con las que se completó cada tarea, tal como llegaron al motor. */
  final Map<String, Map<String, Map<String, Object>>> completadas = new LinkedHashMap<>();
  final List<String> llamadas = new ArrayList<>();
  Map<String, Object> ultimaNomina;

  /** Si es verdadero, completar reviewSla crea la siguiente tarea de soporte, como hace el proceso. */
  boolean creaTareaDeSoporte = true;

  void conectar(MotorClient motor) {
    when(motor.get(anyString())).thenAnswer(llamada -> get(llamada.getArgument(0), Map.of()));
    when(motor.get(anyString(), anyMap())).thenAnswer(llamada -> get(llamada.getArgument(0), llamada.getArgument(1)));
    when(motor.post(anyString(), any())).thenAnswer(llamada -> post(llamada.getArgument(0), llamada.getArgument(1)));
  }

  void usuario(String id, String... grupos) {
    gruposPorUsuario.put(id, List.of(grupos));
  }

  void tarea(String id, String claveActividad, String asignada, String businessKey, String... gruposCandidatos) {
    String instancia = "inst-" + id;
    ObjectNode tarea = json.createObjectNode();
    tarea.put("id", id);
    tarea.put("name", "Tarea " + claveActividad);
    tarea.put("taskDefinitionKey", claveActividad);
    tarea.put("assignee", asignada);
    tarea.put("created", "2026-11-17T10:00:0" + tareas.size() + ".000+0000");
    tarea.put("processInstanceId", instancia);
    tarea.put("processDefinitionId", DEFINICION_TI);
    tareas.put(id, tarea);
    candidatos.put(id, List.of(gruposCandidatos));
    clavesDeNegocio.put(instancia, businessKey);
  }

  void formulario(String tareaId, String esquema) throws Exception {
    formularios.put(tareaId, json.readTree(esquema));
  }

  /** La tarea se consulta con normalidad, pero otra persona la completa justo antes de actuar. */
  void seEsfumaAntesDeActuar(String tareaId) {
    seEsfuman.add(tareaId);
  }

  /** El motor rechaza completar la tarea (por ejemplo, falla el paso siguiente) y la tarea sigue ahí. */
  void fallaAlCompletar(String tareaId) {
    fallanAlCompletar.add(tareaId);
  }

  String asignadaA(String tareaId) {
    JsonNode asignada = tareas.get(tareaId).path("assignee");
    return asignada.isNull() ? null : asignada.asText();
  }

  boolean existe(String tareaId) {
    return tareas.containsKey(tareaId);
  }

  // ------------------------------------------------------------------- rutas

  private JsonNode get(String ruta, Map<String, ?> parametros) {
    llamadas.add("GET " + ruta);
    String[] partes = ruta.substring(1).split("/");
    switch (partes[0]) {
      case "group":
        return gruposDe(String.valueOf(parametros.get("member")));
      case "user":
        return usuariosCon(String.valueOf(parametros.get("id")));
      case "process-instance":
        return instancias((Collection<?>) parametros.get("processInstanceIds"));
      case "process-definition":
        return json.createObjectNode()
            .put("id", partes[1])
            .put("key", "CB_GestionIncidenteTI")
            .put("name", "Gestión de incidentes de TI");
      case "task":
        return partes.length == 1 ? consultarTareas(parametros) : recursoDeTarea(partes[1], partes);
      default:
        throw new MotorException(404, "Ruta no simulada: " + ruta);
    }
  }

  private JsonNode post(String ruta, Object cuerpo) {
    llamadas.add("POST " + ruta);
    if (ruta.equals("/identity/verify")) {
      return verificar((Map<?, ?>) cuerpo);
    }
    if (ruta.startsWith("/process-definition/key/")) {
      return iniciarProceso((Map<?, ?>) cuerpo);
    }
    String[] partes = ruta.substring(1).split("/");
    if (seEsfuman.remove(partes[1])) {
      tareas.remove(partes[1]);
      // Camunda no responde 404 a una acción sobre una tarea que ya no existe, sino 500.
      throw new MotorException(500, "Cannot find task with id " + partes[1]);
    }
    ObjectNode tarea = tareaExistente(partes[1]);
    switch (partes[2]) {
      case "claim":
        return reclamar(tarea, (Map<?, ?>) cuerpo);
      case "unclaim":
        tarea.putNull("assignee");
        return null;
      case "assignee":
        tarea.put("assignee", String.valueOf(((Map<?, ?>) cuerpo).get("userId")));
        return null;
      case "complete":
        return completar(tarea, (Map<?, ?>) cuerpo);
      default:
        throw new MotorException(404, "Ruta no simulada: " + ruta);
    }
  }

  private JsonNode verificar(Map<?, ?> credenciales) {
    boolean correcta = gruposPorUsuario.containsKey(credenciales.get("username"))
        && CLAVE.equals(credenciales.get("password"));
    return json.createObjectNode().put("authenticated", correcta);
  }

  private JsonNode gruposDe(String usuario) {
    ArrayNode grupos = json.createArrayNode();
    for (String grupo : gruposPorUsuario.getOrDefault(usuario, List.of())) {
      grupos.addObject().put("id", grupo).put("name", grupo).put("type", "WORKFLOW");
    }
    return grupos;
  }

  private JsonNode usuariosCon(String id) {
    ArrayNode usuarios = json.createArrayNode();
    if (gruposPorUsuario.containsKey(id)) {
      usuarios.addObject().put("id", id).put("firstName", id);
    }
    return usuarios;
  }

  private JsonNode instancias(Collection<?> ids) {
    ArrayNode instancias = json.createArrayNode();
    for (Object id : ids) {
      instancias.addObject()
          .put("id", String.valueOf(id))
          .put("businessKey", clavesDeNegocio.get(String.valueOf(id)));
    }
    return instancias;
  }

  private JsonNode consultarTareas(Map<String, ?> parametros) {
    ArrayNode resultado = json.createArrayNode();
    for (ObjectNode tarea : tareas.values()) {
      if (cumple(tarea, parametros)) {
        resultado.add(tarea);
      }
    }
    return resultado;
  }

  private boolean cumple(ObjectNode tarea, Map<String, ?> parametros) {
    String id = tarea.get("id").asText();
    String asignada = tarea.path("assignee").isNull() ? null : tarea.get("assignee").asText();
    if (parametros.containsKey("assignee") && !parametros.get("assignee").equals(asignada)) {
      return false;
    }
    if (parametros.containsKey("candidateGroups")) {
      Collection<?> grupos = (Collection<?>) parametros.get("candidateGroups");
      boolean esCandidata = candidatos.get(id).stream().anyMatch(grupos::contains);
      // Camunda solo devuelve por grupo candidato las tareas sin asignar.
      if (asignada != null || !esCandidata) {
        return false;
      }
    }
    if (parametros.containsKey("processInstanceBusinessKey")) {
      String clave = clavesDeNegocio.get(tarea.get("processInstanceId").asText());
      if (!parametros.get("processInstanceBusinessKey").equals(clave)) {
        return false;
      }
    }
    if (parametros.containsKey("taskDefinitionKeyIn")) {
      Collection<?> claves = (Collection<?>) parametros.get("taskDefinitionKeyIn");
      return claves.contains(tarea.get("taskDefinitionKey").asText());
    }
    return true;
  }

  private JsonNode recursoDeTarea(String id, String[] partes) {
    ObjectNode tarea = tareaExistente(id);
    if (partes.length == 2) {
      return tarea;
    }
    switch (partes[2]) {
      case "identity-links":
        ArrayNode enlaces = json.createArrayNode();
        for (String grupo : candidatos.get(id)) {
          enlaces.addObject().putNull("userId").put("groupId", grupo).put("type", "candidate");
        }
        return enlaces;
      case "deployed-form":
        if (!formularios.containsKey(id)) {
          throw new MotorException(404, "La tarea no tiene formulario");
        }
        return formularios.get(id);
      case "variables":
      case "form-variables":
        ObjectNode variables = json.createObjectNode();
        variables.putObject("ticketId").put("value", "TI-0001").put("type", "String");
        return variables;
      default:
        throw new MotorException(404, "Ruta no simulada");
    }
  }

  private ObjectNode tareaExistente(String id) {
    ObjectNode tarea = tareas.get(id);
    if (tarea == null) {
      throw new MotorException(404, "{\"type\":\"RestException\",\"message\":\"No matching task with id " + id + "\"}");
    }
    return tarea;
  }

  private JsonNode reclamar(ObjectNode tarea, Map<?, ?> cuerpo) {
    if (!tarea.path("assignee").isNull()) {
      throw new MotorException(500, "TaskAlreadyClaimedException");
    }
    tarea.put("assignee", String.valueOf(cuerpo.get("userId")));
    return null;
  }

  @SuppressWarnings("unchecked")
  private JsonNode completar(ObjectNode tarea, Map<?, ?> cuerpo) {
    String id = tarea.get("id").asText();
    if (fallanAlCompletar.contains(id)) {
      throw new MotorException(500, "Cannot complete task " + id);
    }
    completadas.put(id, (Map<String, Map<String, Object>>) cuerpo.get("variables"));
    tareas.remove(id);
    boolean esRevision = "reviewSla".equals(tarea.get("taskDefinitionKey").asText());
    if (esRevision && creaTareaDeSoporte) {
      String businessKey = clavesDeNegocio.get(tarea.get("processInstanceId").asText());
      tarea("soporte-" + id, "resolveN1", null, businessKey, "soporte-ti-n1");
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private JsonNode iniciarProceso(Map<?, ?> cuerpo) {
    ultimaNomina = (Map<String, Object>) cuerpo;
    return json.createObjectNode()
        .put("id", "instancia-nomina")
        .put("businessKey", String.valueOf(cuerpo.get("businessKey")));
  }
}
