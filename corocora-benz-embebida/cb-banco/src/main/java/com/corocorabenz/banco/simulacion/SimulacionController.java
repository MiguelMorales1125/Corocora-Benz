package com.corocorabenz.banco.simulacion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulacion")
public class SimulacionController {

  private final Simulacion simulacion;

  public SimulacionController(Simulacion simulacion) {
    this.simulacion = simulacion;
  }

  @GetMapping
  public Simulacion ver() {
    return simulacion;
  }

  public record Cambio(Boolean respuestaAutomatica, Integer retardoSegundos) {
  }

  @PutMapping
  public Simulacion cambiar(@RequestBody Cambio cambio) {
    if (cambio.respuestaAutomatica() != null) {
      simulacion.setRespuestaAutomatica(cambio.respuestaAutomatica());
    }
    if (cambio.retardoSegundos() != null) {
      simulacion.setRetardoSegundos(cambio.retardoSegundos());
    }
    return simulacion;
  }
}
