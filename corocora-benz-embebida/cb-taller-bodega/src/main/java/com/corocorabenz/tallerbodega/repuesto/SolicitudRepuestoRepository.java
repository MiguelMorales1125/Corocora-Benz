package com.corocorabenz.tallerbodega.repuesto;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SolicitudRepuestoRepository extends JpaRepository<SolicitudRepuesto, Long> {

  List<SolicitudRepuesto> findByBusinessKeyOrderByIdDesc(String businessKey);

  List<SolicitudRepuesto> findByEstadoAndSolicitadaEnBefore(SolicitudRepuesto.Estado estado, Instant limite);

  List<SolicitudRepuesto> findAllByOrderByIdDesc();
}
