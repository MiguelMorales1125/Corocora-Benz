package com.corocorabenz.banco.simulacion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cómo responde el banco simulado. Con respuesta automática, cada dispersión se contesta sola
 * después del retardo. Sin ella, se contesta a mano con los endpoints aceptar/rechazar
 * (así lo usan las pruebas e2e). Se puede cambiar en caliente con {@code PUT /api/simulacion}.
 */
@Component
public class Simulacion {

  private volatile boolean respuestaAutomatica;
  private volatile int retardoSegundos;

  public Simulacion(@Value("${corocora.simulacion.respuesta-automatica:true}") boolean respuestaAutomatica,
      @Value("${corocora.simulacion.retardo-segundos:10}") int retardoSegundos) {
    this.respuestaAutomatica = respuestaAutomatica;
    this.retardoSegundos = retardoSegundos;
  }

  public boolean isRespuestaAutomatica() { return respuestaAutomatica; }
  public void setRespuestaAutomatica(boolean respuestaAutomatica) { this.respuestaAutomatica = respuestaAutomatica; }
  public int getRetardoSegundos() { return retardoSegundos; }
  public void setRetardoSegundos(int retardoSegundos) { this.retardoSegundos = Math.max(0, retardoSegundos); }
}
