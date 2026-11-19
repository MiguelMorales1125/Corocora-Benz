package com.corocorabenz.centralsys.actividad;

import com.corocorabenz.comun.portal.UsuarioPortal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Guarda y consulta las acciones de los empleados. */
@Service
public class RegistroActividad {

  private static final Logger LOG = LoggerFactory.getLogger(RegistroActividad.class);

  private final ActividadRepository actividades;

  public RegistroActividad(ActividadRepository actividades) {
    this.actividades = actividades;
  }

  /** Acción sobre una tarea del motor. */
  public void deTarea(
      UsuarioPortal usuario, Accion accion, String tarea, String tareaId, String businessKey, String detalle) {
    guardar(new Actividad(
        usuario.user(),
        accion.name(),
        recortar(tarea),
        tareaId,
        recortar(businessKey),
        recortar(detalle)));
  }

  /** Acción que no es sobre una tarea: iniciar la nómina o atender la bodega. */
  public void general(UsuarioPortal usuario, Accion accion, String businessKey, String detalle) {
    deTarea(usuario, accion, null, null, businessKey, detalle);
  }

  public List<Actividad> ultimasDe(UsuarioPortal usuario) {
    return actividades.findTop20ByUsuarioOrderByFechaDescIdDesc(usuario.user());
  }

  private void guardar(Actividad actividad) {
    // La acción ya se hizo en el motor o en la bodega: si el registro falla
    // no se le puede responder a la persona que su acción no se hizo.
    try {
      actividades.save(actividad);
    } catch (RuntimeException e) {
      LOG.warn("No fue posible registrar la acción {} de {}", actividad.getAccion(), actividad.getUsuario(), e);
    }
  }

  private static String recortar(String texto) {
    if (texto == null || texto.length() <= Actividad.LARGO_TEXTO) {
      return texto;
    }
    return texto.substring(0, Actividad.LARGO_TEXTO);
  }
}
