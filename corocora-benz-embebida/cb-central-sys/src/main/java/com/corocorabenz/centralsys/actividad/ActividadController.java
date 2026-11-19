package com.corocorabenz.centralsys.actividad;

import com.corocorabenz.comun.portal.Permisos;
import com.corocorabenz.comun.portal.UsuarioPortal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActividadController {

  private final RegistroActividad registro;

  public ActividadController(RegistroActividad registro) {
    this.registro = registro;
  }

  /** Últimas 20 acciones del usuario de la sesión. */
  @GetMapping("/api/app/central/actividad")
  public List<Actividad> ultimas(@AuthenticationPrincipal UsuarioPortal usuario) {
    Permisos.exigirEmpleado(usuario);
    return registro.ultimasDe(usuario);
  }
}
