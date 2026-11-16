package com.corocorabenz.tallerbodega.repuesto;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservaRepository extends JpaRepository<Reserva, Long> {

  List<Reserva> findAllByOrderByIdDesc();
  java.util.Optional<Reserva> findFirstByBusinessKeyOrderByIdDesc(String businessKey);
}
