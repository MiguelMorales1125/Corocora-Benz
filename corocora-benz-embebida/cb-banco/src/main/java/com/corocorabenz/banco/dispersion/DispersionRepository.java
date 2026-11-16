package com.corocorabenz.banco.dispersion;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispersionRepository extends JpaRepository<Dispersion, Long> {

  List<Dispersion> findByBusinessKeyOrderByIdDesc(String businessKey);

  List<Dispersion> findByBusinessKeyAndEstado(String businessKey, EstadoDispersion estado);

  List<Dispersion> findByEstadoAndRecibidaEnBefore(EstadoDispersion estado, Instant limite);

  List<Dispersion> findAllByOrderByIdDesc();
}
