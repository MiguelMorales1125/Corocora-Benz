package com.corocorabenz.centralsys.tareas;

import com.corocorabenz.comun.portal.Permisos;
import com.corocorabenz.comun.portal.UsuarioPortal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Único punto por el que pasa todo endpoint que recibe el id de una tarea:
 * una tarea inexistente es 404 y una ajena es 403.
 */
@Component
public class AccesoTareas {

  private final MotorTareas motorTareas;

  public AccesoTareas(MotorTareas motorTareas) {
    this.motorTareas = motorTareas;
  }

  /** Carga la tarea y lo que la persona es respecto a ella, sin exigir todavía nada sobre la tarea. */
  public PermisoTarea cargar(String tareaId, UsuarioPortal usuario) {
    Permisos.exigirEmpleado(usuario);
    Tarea tarea = motorTareas.buscar(tareaId);
    List<String> candidatos = motorTareas.gruposCandidatos(tarea.id());
    return new PermisoTarea(tarea, candidatos, usuario);
  }

  /** @throws ResponseStatusException 403 si la persona no tiene permiso sobre la tarea */
  public PermisoTarea conPermiso(String tareaId, UsuarioPortal usuario) {
    PermisoTarea permiso = cargar(tareaId, usuario);
    if (!permiso.conPermiso()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso sobre esta tarea");
    }
    return permiso;
  }
}
