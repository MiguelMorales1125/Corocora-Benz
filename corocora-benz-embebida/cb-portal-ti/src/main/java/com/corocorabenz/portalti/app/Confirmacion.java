package com.corocorabenz.portalti.app;

import jakarta.validation.constraints.NotNull;

/** Cuerpo de {@code POST /api/app/ti/tickets/{ticketId}/confirm}. */
public record Confirmacion(
    @NotNull(message = "Indica si la solución resolvió el incidente")
    Boolean confirmed) {
}
