package com.corocorabenz.portalti.app;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Cuerpo de {@code POST /api/app/ti/reports}. No trae quién reporta: eso sale de la sesión.
 *
 * <p>Los valores permitidos son los mismos del formulario {@code ti-reporte} del proceso.
 */
public record ReporteIncidente(
    @NotNull(message = "El sistema afectado es obligatorio")
    @Pattern(
        regexp = "Software del vehículo|ERP|Portal de clientes|Correo",
        message = "El sistema afectado debe ser Software del vehículo, ERP, Portal de clientes o Correo")
    String systemName,

    @NotBlank(message = "La descripción es obligatoria")
    @Size(max = 4000, message = "La descripción admite hasta 4000 caracteres")
    String description,

    @NotNull(message = "El impacto es obligatorio")
    @Pattern(regexp = "Bajo|Medio|Alto", message = "El impacto debe ser Bajo, Medio o Alto")
    String impact,

    @NotNull(message = "La urgencia es obligatoria")
    @Pattern(regexp = "Baja|Media|Alta", message = "La urgencia debe ser Baja, Media o Alta")
    String urgency,

    // BigDecimal y no Integer: con Integer, Jackson recortaría 1.5 a 1 en silencio en vez de rechazarlo.
    // Las dos restricciones comparten mensaje para que 0.5, que incumple ambas, responda siempre igual.
    @NotNull(message = "Los equipos afectados son obligatorios")
    @Min(value = 1, message = ReporteIncidente.EQUIPOS_NO_VALIDOS)
    @Digits(integer = 9, fraction = 0, message = ReporteIncidente.EQUIPOS_NO_VALIDOS)
    BigDecimal affectedTeams,

    @NotNull(message = "Indica si el incidente afecta la seguridad")
    Boolean safetyImpact,

    @Email(message = "El correo para notificaciones no tiene un formato válido")
    @Size(max = 255, message = "El correo para notificaciones admite hasta 255 caracteres")
    String notificationEmail) {

  static final String EQUIPOS_NO_VALIDOS = "Los equipos afectados deben ser un número entero entre 1 y 999999999";

  /** El correo es opcional y el formulario lo envía vacío cuando no se escribe. */
  public boolean tieneCorreo() {
    return notificationEmail != null && !notificationEmail.isBlank();
  }
}
