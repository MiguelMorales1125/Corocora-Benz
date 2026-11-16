package com.corocorabenz.portalti.ticket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TicketRepository extends JpaRepository<Ticket, Long> {
  Optional<Ticket> findByBusinessKey(String businessKey);
  List<Ticket> findByRequesterIdOrderByIdDesc(String requesterId);
}
