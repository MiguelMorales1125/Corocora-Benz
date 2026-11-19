package com.corocorabenz.centralsys.tareas;

import com.fasterxml.jackson.databind.JsonNode;

/** Lo que CentralSys necesita de una tarea del motor. */
public record Tarea(
    String id,
    String name,
    String taskDefinitionKey,
    String assignee,
    String created,
    String processInstanceId,
    String processDefinitionId) {

  static Tarea de(JsonNode nodo) {
    return new Tarea(
        texto(nodo, "id"),
        texto(nodo, "name"),
        texto(nodo, "taskDefinitionKey"),
        texto(nodo, "assignee"),
        texto(nodo, "created"),
        texto(nodo, "processInstanceId"),
        texto(nodo, "processDefinitionId"));
  }

  /** Un campo ausente, nulo o vacío se trata igual: sin valor. */
  static String texto(JsonNode nodo, String campo) {
    JsonNode valor = nodo.path(campo);
    if (valor.isMissingNode() || valor.isNull()) {
      return null;
    }
    String contenido = valor.asText();
    return contenido.isBlank() ? null : contenido;
  }
}
