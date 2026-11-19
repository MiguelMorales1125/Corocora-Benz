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
  /** Al responder, ya no había un proceso activo con esa businessKey; no se reintenta más. */
  SIN_PROCESO
}
