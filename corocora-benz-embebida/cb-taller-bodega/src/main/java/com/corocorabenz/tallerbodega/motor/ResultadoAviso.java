package com.corocorabenz.tallerbodega.motor;

/** Qué pasó con un aviso de la bodega al motor (sección 7 del contrato de integración). */
public enum ResultadoAviso {

  /** El proceso recibió el aviso. */
  ENTREGADO,

  /** El proceso sigue activo pero todavía no espera el aviso: se reintenta. */
  AUN_NO_ESPERADO,

  /** Ya no hay un proceso activo con esa businessKey: no se reintenta más. */
  SIN_PROCESO,

  /** El motor no respondió: se reintenta. */
  MOTOR_NO_DISPONIBLE,

  /** No se envió nada porque el registro ya no estaba pendiente (otro aviso llegó antes). */
  NO_PENDIENTE
}
