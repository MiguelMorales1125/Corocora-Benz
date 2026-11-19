package com.corocorabenz.centralsys.tareas;

import com.corocorabenz.comun.portal.UsuarioPortal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Arma la bandeja de una persona: tareas libres de sus grupos y tareas que tiene asignadas. */
@Service
public class BandejaService {

  private final MotorTareas motorTareas;

  // Los grupos candidatos están escritos en el BPMN, así que son los mismos para todas las
  // tareas de una actividad de una definición. Guardarlos evita una consulta por tarea en
  // cada actualización de la bandeja. Los permisos NO usan esta memoria: leen la tarea real.
  private final Map<String, List<String>> gruposPorActividad = new ConcurrentHashMap<>();

  public BandejaService(MotorTareas motorTareas) {
    this.motorTareas = motorTareas;
  }

  public List<TareaBandeja> listar(UsuarioPortal usuario) {
    List<Tarea> tareas = new ArrayList<>(tareasDe(usuario).values());
    tareas.sort(Comparator.comparing(Tarea::created, Comparator.nullsLast(Comparator.reverseOrder())));

    Map<String, String> claves = motorTareas.clavesDeNegocio(instanciasDe(tareas));
    List<TareaBandeja> bandeja = new ArrayList<>();
    for (Tarea tarea : tareas) {
      DefinicionProceso definicion = motorTareas.definicion(tarea.processDefinitionId());
      bandeja.add(new TareaBandeja(
          tarea.id(),
          tarea.name(),
          tarea.taskDefinitionKey(),
          tarea.assignee(),
          tarea.created(),
          claves.get(tarea.processInstanceId()),
          definicion.key(),
          definicion.name() != null ? definicion.name() : definicion.key(),
          gruposCandidatos(tarea)));
    }
    return bandeja;
  }

  /** Por id, para que una tarea que salga en dos consultas aparezca una sola vez. */
  private Map<String, Tarea> tareasDe(UsuarioPortal usuario) {
    Map<String, Tarea> tareas = new LinkedHashMap<>();
    if (usuario.admin()) {
      agregar(tareas, motorTareas.consultar(Map.of()));
      return tareas;
    }
    agregar(tareas, motorTareas.consultar(Map.of("assignee", usuario.user())));
    if (!usuario.groups().isEmpty()) {
      // Camunda solo devuelve por grupo candidato las tareas que nadie ha reclamado.
      agregar(tareas, motorTareas.consultar(Map.of("candidateGroups", usuario.groups())));
    }
    return tareas;
  }

  private static void agregar(Map<String, Tarea> destino, List<Tarea> tareas) {
    for (Tarea tarea : tareas) {
      destino.putIfAbsent(tarea.id(), tarea);
    }
  }

  private static Set<String> instanciasDe(List<Tarea> tareas) {
    Set<String> instancias = new LinkedHashSet<>();
    for (Tarea tarea : tareas) {
      if (tarea.processInstanceId() != null) {
        instancias.add(tarea.processInstanceId());
      }
    }
    return instancias;
  }

  private List<String> gruposCandidatos(Tarea tarea) {
    if (tarea.processDefinitionId() == null || tarea.taskDefinitionKey() == null) {
      return motorTareas.gruposCandidatos(tarea.id());
    }
    String actividad = tarea.processDefinitionId() + "|" + tarea.taskDefinitionKey();
    List<String> conocidos = gruposPorActividad.get(actividad);
    if (conocidos != null) {
      return conocidos;
    }
    List<String> leidos = List.copyOf(motorTareas.gruposCandidatos(tarea.id()));
    gruposPorActividad.put(actividad, leidos);
    return leidos;
  }
}
