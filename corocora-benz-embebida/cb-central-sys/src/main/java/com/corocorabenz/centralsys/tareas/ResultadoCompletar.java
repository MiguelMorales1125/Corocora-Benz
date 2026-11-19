package com.corocorabenz.centralsys.tareas;

/**
 * Respuesta de completar una tarea.
 *
 * @param assigned solo tras {@code reviewSla} con nuevo responsable: {@code true} si se le asignó
 *     la siguiente tarea de soporte y {@code false} si no se pudo; {@code null} en cualquier otro caso
 */
public record ResultadoCompletar(boolean completed, Boolean assigned) {
}
