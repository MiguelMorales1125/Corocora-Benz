package com.corocorabenz.centralsys.tareas;

import java.util.List;

/** Una fila de la bandeja, con los nombres de campo del contrato. */
public record TareaBandeja(
    String id,
    String name,
    String taskDefinitionKey,
    String assignee,
    String created,
    String businessKey,
    String processDefinitionKey,
    String processName,
    List<String> candidateGroups) {
}
