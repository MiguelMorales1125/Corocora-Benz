package com.corocorabenz.tallerbodega.vehiculo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Historial y campañas por VIN. Lo llama el motor en "Consultar historial y campañas del VIN". */
@RestController
@RequestMapping("/api/vehiculos")
public class VehiculoController {

  /** La garantía de fábrica cubre hasta este kilometraje. */
  static final long GARANTIA_MAX_KM = 60_000;

  private static final String FORMATO_VIN = "[A-HJ-NPR-Z0-9]{17}";

  private final VehiculoRepository repository;

  public VehiculoController(VehiculoRepository repository) {
    this.repository = repository;
  }

  /** Un VIN bien formado pero desconocido no es un error: se responde como vehículo no registrado y sin garantía. */
  @GetMapping("/{vin}")
  public Map<String, Object> consultar(@PathVariable String vin, @RequestParam(defaultValue = "0") long km) {
    if (!vin.matches(FORMATO_VIN) || km < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VIN o kilometraje inválido");
    }
    Vehiculo vehiculo = repository.findById(vin).orElse(null);
    boolean registrado = vehiculo != null;
    String campana = registrado ? vehiculo.getCampanaActiva() : null;
    Map<String, Object> respuesta = new LinkedHashMap<>();
    respuesta.put("vin", vin);
    respuesta.put("registrado", registrado);
    respuesta.put("modelo", registrado ? vehiculo.getModelo() : null);
    respuesta.put("historyRecordId", "HIST-" + vin);
    respuesta.put("warrantyEligible", registrado && km <= GARANTIA_MAX_KM);
    respuesta.put("campaignActive", campana != null);
    respuesta.put("campaignId", campana);
    return respuesta;
  }
}
