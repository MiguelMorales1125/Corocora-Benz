package com.corocorabenz.tallerbodega.vehiculo;

import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Carga los vehículos de prueba la primera vez que arranca el sistema. */
@Configuration
public class DatosIniciales {

  @Bean
  ApplicationRunner cargarVehiculos(VehiculoRepository repository) {
    return args -> {
      if (repository.count() > 0) {
        return;
      }
      repository.saveAll(List.of(
          new Vehiculo("CB0TEST0000000001", "Clase C 200", 2024, "RECALL-CAMARA-2026"),
          new Vehiculo("WDB0000000000000B", "GLA 200", 2019, null),
          new Vehiculo("WDB0000000000000C", "Clase A 200", 2023, null),
          new Vehiculo("WDB0000000000000D", "GLC 300", 2021, null)));
    };
  }
}
