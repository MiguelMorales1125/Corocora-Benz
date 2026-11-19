package com.corocorabenz.tallerbodega.motor;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.corocorabenz.comun.motor.ResultadoMensaje;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Envía al motor las respuestas asíncronas de la bodega y decide, con la regla del
 * contrato, si un aviso que no fue aceptado se debe reintentar o ya no tiene destinatario.
 */
@Component
public class AvisoAlMotor {

  private final MotorClient motor;

  public AvisoAlMotor(MotorClient motor) {
    this.motor = motor;
  }

  /**
   * @throws com.corocorabenz.comun.motor.MotorException si el motor falla por algo distinto
   *     de "nadie espera ese mensaje" (por ejemplo, la credencial interna mal configurada)
   */
  public ResultadoAviso enviar(String mensaje, String businessKey, Map<String, Object> variables) {
    if (businessKey == null || businessKey.isBlank()) {
      // Sin businessKey el motor entregaría el mensaje a cualquier proceso que lo esté esperando.
      return ResultadoAviso.SIN_PROCESO;
    }
    ResultadoMensaje resultado = motor.enviarMensaje(mensaje, businessKey, variables);
    if (resultado == ResultadoMensaje.ENTREGADO) {
      return ResultadoAviso.ENTREGADO;
    }
    if (resultado == ResultadoMensaje.MOTOR_NO_DISPONIBLE) {
      return ResultadoAviso.MOTOR_NO_DISPONIBLE;
    }
    return siNadieLoEspera(businessKey);
  }

  /**
   * El motor responde lo mismo cuando el proceso aún no llega al punto de espera
   * y cuando ya terminó; solo la existencia de una instancia activa los distingue.
   */
  private ResultadoAviso siNadieLoEspera(String businessKey) {
    try {
      if (motor.existeInstanciaActiva(businessKey)) {
        return ResultadoAviso.AUN_NO_ESPERADO;
      }
      return ResultadoAviso.SIN_PROCESO;
    } catch (MotorNoDisponibleException e) {
      return ResultadoAviso.MOTOR_NO_DISPONIBLE;
    }
  }
}
