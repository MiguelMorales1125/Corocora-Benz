package com.corocorabenz.tallerbodega.simulacion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cómo responde la bodega simulada. Con respuesta automática, cada repuesto solicitado se entrega solo
 * después del retardo. Sin ella, se entrega a mano con el endpoint entregar
 * (así lo usan las pruebas e2e). Se puede cambiar en caliente con {@code PUT /api/simulacion}.
 */
@Component
public class Simulacion {

  private volatile boolean respuestaAutomatica;
  private volatile int retardoSegundos;
  private volatile boolean repuestosDisponibles = true;

  public Simulacion(@Value("${corocora.simulacion.respuesta-automatica:true}") boolean respuestaAutomatica,
      @Value("${corocora.simulacion.retardo-segundos:10}") int retardoSegundos) {
    this.respuestaAutomatica = respuestaAutomatica;
    this.retardoSegundos = retardoSegundos;
  }

  public boolean isRepuestosDisponibles() {
    return repuestosDisponibles;
  }

  public void setRepuestosDisponibles(boolean disponible) {
    repuestosDisponibles = disponible;
  }

  public boolean isRespuestaAutomatica() {
    return respuestaAutomatica;
  }

  public void setRespuestaAutomatica(boolean respuestaAutomatica) {
    this.respuestaAutomatica = respuestaAutomatica;
  }

  public int getRetardoSegundos() {
    return retardoSegundos;
  }

  public void setRetardoSegundos(int retardoSegundos) {
    this.retardoSegundos = Math.max(0, retardoSegundos);
  }
}
