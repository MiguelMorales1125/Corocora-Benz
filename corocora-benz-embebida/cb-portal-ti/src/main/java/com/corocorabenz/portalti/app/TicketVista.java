package com.corocorabenz.portalti.app;

import com.corocorabenz.portalti.ticket.Ticket;

/**
 * Lo que la persona ve de uno de sus tickets en {@code GET /api/app/ti/tickets}.
 *
 * @param canConfirm el proceso espera que esta persona confirme si la solución sirvió
 */
public record TicketVista(
    String ticketId,
    String businessKey,
    String systemName,
    String description,
    String status,
    String rootCause,
    String resolutionSummary,
    boolean canConfirm) {

  public static TicketVista de(Ticket ticket, boolean canConfirm) {
    return new TicketVista(
        ticket.getTicketId(),
        ticket.getBusinessKey(),
        ticket.getSystemName(),
        ticket.getDescription(),
        ticket.getStatus(),
        ticket.getRootCause(),
        ticket.getResolutionSummary(),
        canConfirm);
  }
}
