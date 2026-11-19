package com.corocorabenz.portalti.app;

import com.corocorabenz.comun.portal.Permisos;
import com.corocorabenz.comun.portal.UsuarioPortal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Cierra la mesa de ayuda a las cuentas de cliente (403).
 *
 * <p>Va en un interceptor y no en cada método del controlador porque así se comprueba antes
 * de leer y validar el cuerpo: un cliente recibe 403 aunque envíe datos incompletos, no 400.
 */
public class SoloEmpleadosInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Permisos.exigirEmpleado(usuarioDeLaSesion());
    return true;
  }

  private static UsuarioPortal usuarioDeLaSesion() {
    Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
    if (autenticacion != null && autenticacion.getPrincipal() instanceof UsuarioPortal usuario) {
      return usuario;
    }
    return null;
  }
}
