package com.corocorabenz.centralsys.tareas;

import com.corocorabenz.comun.portal.Permisos;
import com.corocorabenz.comun.portal.UsuarioPortal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bandeja y tareas de los empleados (sección 5.3 del contrato). */
@RestController
@RequestMapping("/api/app/central/tasks")
public class TareasController {

  private final BandejaService bandeja;
  private final TareasService tareas;

  public TareasController(BandejaService bandeja, TareasService tareas) {
    this.bandeja = bandeja;
    this.tareas = tareas;
  }

  @GetMapping
  public List<TareaBandeja> bandeja(@AuthenticationPrincipal UsuarioPortal usuario) {
    Permisos.exigirEmpleado(usuario);
    return bandeja.listar(usuario);
  }

  @PostMapping("/{id}/claim")
  public AsignacionTarea reclamar(@PathVariable String id, @AuthenticationPrincipal UsuarioPortal usuario) {
    return tareas.reclamar(id, usuario);
  }

  @PostMapping("/{id}/unclaim")
  public AsignacionTarea soltar(@PathVariable String id, @AuthenticationPrincipal UsuarioPortal usuario) {
    return tareas.soltar(id, usuario);
  }

  @GetMapping("/{id}/deployed-form")
  public JsonNode formulario(@PathVariable String id, @AuthenticationPrincipal UsuarioPortal usuario) {
    return tareas.formulario(id, usuario);
  }

  @GetMapping("/{id}/form-variables")
  public JsonNode variablesDelFormulario(
      @PathVariable String id, @AuthenticationPrincipal UsuarioPortal usuario) {
    return tareas.variablesDelFormulario(id, usuario);
  }

  @GetMapping("/{id}/variables")
  public JsonNode variables(@PathVariable String id, @AuthenticationPrincipal UsuarioPortal usuario) {
    return tareas.variables(id, usuario);
  }

  @PostMapping("/{id}/complete")
  public ResultadoCompletar completar(
      @PathVariable String id,
      @RequestBody(required = false) JsonNode cuerpo,
      @AuthenticationPrincipal UsuarioPortal usuario) {
    return tareas.completar(id, cuerpo, usuario);
  }
}
