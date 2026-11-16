package com.corocorabenz.banco.dispersion;

public enum EstadoDispersion {
  /** Recibida del motor; falta responder. */
  RECIBIDA,
  /** El banco la aceptó y se lo informó al motor (DispersionAceptada). */
  ACEPTADA,
  /** El banco la rechazó y se lo informó al motor (DispersionRechazada). */
  RECHAZADA,
  /** Llegó otra dispersión del mismo proceso antes de responder esta. */
  REEMPLAZADA,
  /** Se intentó responder muchas veces y el proceso nunca la estuvo esperando. */
  SIN_PROCESO
}
