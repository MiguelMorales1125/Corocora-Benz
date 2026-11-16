package com.corocorabenz.tallerbodega.cotizacion;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CotizacionRepository extends JpaRepository<Cotizacion, Long> {

  List<Cotizacion> findByBusinessKeyOrderByIdDesc(String businessKey);

  List<Cotizacion> findAllByOrderByIdDesc();
}
