package com.corocorabenz.centralsys.actividad;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActividadRepository extends JpaRepository<Actividad, Long> {

  List<Actividad> findTop20ByUsuarioOrderByFechaDescIdDesc(String usuario);
}
