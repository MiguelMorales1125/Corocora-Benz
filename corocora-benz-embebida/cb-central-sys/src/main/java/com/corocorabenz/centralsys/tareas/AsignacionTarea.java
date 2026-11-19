package com.corocorabenz.centralsys.tareas;

/** Respuesta de reclamar y soltar: quién tiene ahora la tarea ({@code null} si quedó libre). */
public record AsignacionTarea(String id, String assignee) {
}
