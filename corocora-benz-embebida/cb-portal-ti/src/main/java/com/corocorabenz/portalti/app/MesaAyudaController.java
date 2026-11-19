package com.corocorabenz.portalti.app;

import com.corocorabenz.comun.portal.UsuarioPortal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de la mesa de ayuda para el navegador (sección 5.2 del contrato). Solo empleados y
 * administradores: {@link SoloEmpleadosInterceptor} responde 403 a una cuenta de cliente
 * antes de llegar aquí.
 *
 * <p>Quién reporta y de quién son los tickets sale siempre de la sesión, nunca del cuerpo.
 */
@RestController
@RequestMapping("/api/app/ti")
public class MesaAyudaController {

  private final MesaAyudaService mesaAyuda;

  public MesaAyudaController(MesaAyudaService mesaAyuda) {
    this.mesaAyuda = mesaAyuda;
  }

  public record ReporteRecibido(String businessKey) {
  }

  public record ConfirmacionRegistrada(String ticketId, boolean confirmed) {
  }

  @PostMapping("/reports")
  public ReporteRecibido reportar(
      @AuthenticationPrincipal UsuarioPortal usuario,
      @Valid @RequestBody ReporteIncidente reporte) {
    return new ReporteRecibido(mesaAyuda.reportar(usuario.user(), reporte));
  }

  @GetMapping("/tickets")
  public List<TicketVista> tickets(@AuthenticationPrincipal UsuarioPortal usuario) {
    return mesaAyuda.ticketsDe(usuario.user());
  }

  @PostMapping("/tickets/{ticketId}/confirm")
  public ConfirmacionRegistrada confirmar(
      @AuthenticationPrincipal UsuarioPortal usuario,
      @PathVariable String ticketId,
      @Valid @RequestBody Confirmacion confirmacion) {
    mesaAyuda.confirmar(usuario.user(), ticketId, confirmacion.confirmed());
    return new ConfirmacionRegistrada(ticketId, confirmacion.confirmed());
  }
}
