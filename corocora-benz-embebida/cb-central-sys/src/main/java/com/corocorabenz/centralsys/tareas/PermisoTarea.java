package com.corocorabenz.centralsys.tareas;

import com.corocorabenz.comun.portal.UsuarioPortal;
import java.util.List;

/**
 * Relación entre una persona y una tarea. Aquí vive la regla del contrato
 * (sección 5.3) para que todos los endpoints decidan igual.
 */
public record PermisoTarea(Tarea tarea, List<String> gruposCandidatos, UsuarioPortal usuario) {

  public boolean asignadaAMi() {
    return usuario.user().equals(tarea.assignee());
  }

  public boolean sinAsignar() {
    return tarea.assignee() == null;
  }

  public boolean esCandidato() {
    return gruposCandidatos.stream().anyMatch(usuario::perteneceA);
  }

  /** Con permiso: la tiene asignada, o está libre y es de uno de sus grupos, o es admin. */
  public boolean conPermiso() {
    return usuario.admin() || asignadaAMi() || (sinAsignar() && esCandidato());
  }

  /** Quien puede completarla o soltarla: quien la tiene asignada, o un admin. */
  public boolean esResponsable() {
    return usuario.admin() || asignadaAMi();
  }

  /** Quien podría reclamarla si estuviera libre. */
  public boolean puedeReclamar() {
    return usuario.admin() || esCandidato();
  }
}
