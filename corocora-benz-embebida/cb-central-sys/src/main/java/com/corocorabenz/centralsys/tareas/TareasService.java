package com.corocorabenz.centralsys.tareas;

import com.corocorabenz.centralsys.actividad.Accion;
import com.corocorabenz.centralsys.actividad.RegistroActividad;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.portal.UsuarioPortal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Acciones sobre una tarea concreta. Todas empiezan por {@link AccesoTareas}. */
@Service
public class TareasService {

  private static final int NO_ENCONTRADO = 404;

  private final MotorClient motor;
  private final MotorTareas motorTareas;
  private final AccesoTareas acceso;
  private final Reasignacion reasignacion;
  private final RegistroActividad registro;

  public TareasService(
      MotorClient motor,
      MotorTareas motorTareas,
      AccesoTareas acceso,
      Reasignacion reasignacion,
      RegistroActividad registro) {
    this.motor = motor;
    this.motorTareas = motorTareas;
    this.acceso = acceso;
    this.reasignacion = reasignacion;
    this.registro = registro;
  }

  public AsignacionTarea reclamar(String tareaId, UsuarioPortal usuario) {
    PermisoTarea permiso = acceso.cargar(tareaId, usuario);
    Tarea tarea = permiso.tarea();
    if (permiso.asignadaAMi()) {
      return new AsignacionTarea(tarea.id(), usuario.user());
    }
    if (!permiso.puedeReclamar()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta tarea no es de ninguno de tus grupos");
    }
    if (!permiso.sinAsignar()) {
      throw yaLaTieneOtraPersona();
    }
    try {
      motor.post("/task/" + tarea.id() + "/claim", Map.of("userId", usuario.user()));
    } catch (MotorException e) {
      // Dos personas reclamando a la vez: el motor rechaza a la segunda.
      if (laTieneOtraPersona(tarea.id(), usuario)) {
        throw yaLaTieneOtraPersona();
      }
      throw e;
    }
    registrar(usuario, Accion.RECLAMAR, tarea, null);
    return new AsignacionTarea(tarea.id(), usuario.user());
  }

  public AsignacionTarea soltar(String tareaId, UsuarioPortal usuario) {
    PermisoTarea permiso = acceso.cargar(tareaId, usuario);
    Tarea tarea = permiso.tarea();
    if (!permiso.esResponsable()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo quien tiene la tarea puede soltarla");
    }
    if (permiso.sinAsignar()) {
      return new AsignacionTarea(tarea.id(), null);
    }
    if (permiso.gruposCandidatos().isEmpty()) {
      // Una tarea asignada por el proceso a una persona (sin grupo) quedaría fuera de toda bandeja.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Esta tarea está asignada directamente a una persona y no se puede soltar");
    }
    try {
      motor.post("/task/" + tarea.id() + "/unclaim", null);
    } catch (MotorException e) {
      throw rechazoDelMotor(tarea, e);
    }
    registrar(usuario, Accion.SOLTAR, tarea, null);
    return new AsignacionTarea(tarea.id(), null);
  }

  public JsonNode formulario(String tareaId, UsuarioPortal usuario) {
    PermisoTarea permiso = acceso.conPermiso(tareaId, usuario);
    JsonNode esquema = motorTareas.formularioDesplegado(permiso.tarea().id());
    if (esquema == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "La tarea no tiene un formulario desplegado");
    }
    return esquema;
  }

  public JsonNode variablesDelFormulario(String tareaId, UsuarioPortal usuario) {
    return leerVariables(tareaId, usuario, "form-variables");
  }

  public JsonNode variables(String tareaId, UsuarioPortal usuario) {
    return leerVariables(tareaId, usuario, "variables");
  }

  /**
   * Completa la tarea solo con las variables de los campos editables de su formulario,
   * con el tipo que fija el servidor.
   */
  public ResultadoCompletar completar(String tareaId, JsonNode cuerpo, UsuarioPortal usuario) {
    PermisoTarea permiso = acceso.cargar(tareaId, usuario);
    if (!permiso.esResponsable()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Solo quien tiene asignada la tarea puede completarla");
    }
    Tarea tarea = permiso.tarea();
    FormularioTarea formulario = FormularioTarea.de(motorTareas.formularioDesplegado(tarea.id()));
    JsonNode enviadas = cuerpo == null ? null : cuerpo.get("variables");
    Map<String, Object> variables = formulario.variablesAceptadas(enviadas);

    // Se lee antes de completar: si era la última tarea, después la instancia ya no existe.
    String businessKey = motorTareas.claveDeNegocio(tarea.processInstanceId());
    try {
      motor.post("/task/" + tarea.id() + "/complete", Map.of("variables", MotorClient.tipar(variables)));
    } catch (MotorException e) {
      throw rechazoDelMotor(tarea, e);
    }

    Boolean asignada = reasignar(tarea, businessKey, variables);
    registro.deTarea(usuario, Accion.COMPLETAR, tarea.name(), tarea.id(), businessKey, detalleDe(asignada, variables));
    return new ResultadoCompletar(true, asignada);
  }

  /** @return {@code null} si la tarea no pide reasignar; si la pide, si se pudo o no */
  private Boolean reasignar(Tarea tarea, String businessKey, Map<String, Object> variables) {
    if (!Reasignacion.TAREA_REVISION.equals(tarea.taskDefinitionKey())) {
      return null;
    }
    String nuevoResponsable = nuevoResponsable(variables);
    if (nuevoResponsable == null) {
      return null;
    }
    return reasignacion.asignarSiguiente(businessKey, nuevoResponsable);
  }

  private static String nuevoResponsable(Map<String, Object> variables) {
    Object valor = variables.get(Reasignacion.VARIABLE_NUEVO_RESPONSABLE);
    if (valor == null || valor.toString().isBlank()) {
      return null;
    }
    return valor.toString().trim();
  }

  private static String detalleDe(Boolean asignada, Map<String, Object> variables) {
    if (asignada == null) {
      return null;
    }
    String nuevoResponsable = nuevoResponsable(variables);
    if (asignada) {
      return "Siguiente tarea asignada a " + nuevoResponsable;
    }
    return "No se pudo asignar la siguiente tarea a " + nuevoResponsable;
  }

  private JsonNode leerVariables(String tareaId, UsuarioPortal usuario, String recurso) {
    PermisoTarea permiso = acceso.conPermiso(tareaId, usuario);
    // Sin deserializar: los objetos Java viajan como texto y el motor no necesita sus clases.
    JsonNode variables = motor.get(
        "/task/" + permiso.tarea().id() + "/" + recurso, Map.of("deserializeValues", false));
    return variables != null ? variables : JsonNodeFactory.instance.objectNode();
  }

  /**
   * Camunda responde 500, y no 404, al actuar sobre una tarea que desapareció entre la consulta y
   * la acción (doble clic, otra pestaña, alguien que la completó antes). Se vuelve a buscar la
   * tarea para distinguir ese caso de un rechazo real del motor.
   *
   * @return el 404 del contrato si la tarea ya no existe; si sigue ahí, el rechazo original
   */
  private RuntimeException rechazoDelMotor(Tarea tarea, MotorException rechazo) {
    if (rechazo.getStatus() == NO_ENCONTRADO) {
      return MotorTareas.tareaInexistente();
    }
    try {
      motorTareas.buscar(tarea.id());
    } catch (ResponseStatusException tareaInexistente) {
      return tareaInexistente;
    } catch (RuntimeException motorSinRespuesta) {
      // No se pudo comprobar: se informa del rechazo que sí se recibió.
      return rechazo;
    }
    return rechazo;
  }

  private boolean laTieneOtraPersona(String tareaId, UsuarioPortal usuario) {
    Tarea actual = motorTareas.buscar(tareaId);
    return actual.assignee() != null && !actual.assignee().equals(usuario.user());
  }

  private void registrar(UsuarioPortal usuario, Accion accion, Tarea tarea, String detalle) {
    String businessKey = null;
    try {
      businessKey = motorTareas.claveDeNegocio(tarea.processInstanceId());
    } catch (RuntimeException e) {
      // La acción ya se hizo; la businessKey solo adorna el registro.
    }
    registro.deTarea(usuario, accion, tarea.name(), tarea.id(), businessKey, detalle);
  }

  private static ResponseStatusException yaLaTieneOtraPersona() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Otra persona ya tiene esta tarea");
  }
}
