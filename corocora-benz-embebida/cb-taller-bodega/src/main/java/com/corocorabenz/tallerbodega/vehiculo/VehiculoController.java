package com.corocorabenz.tallerbodega.vehiculo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Historial y campañas por VIN. Lo llama el motor en "Consultar historial y campañas del VIN". */
@RestController
@RequestMapping("/api/vehiculos")
public class VehiculoController {

  /** La garantía de fábrica cubre hasta este kilometraje. */
  static final long GARANTIA_MAX_KM = 60_000;

  private final VehiculoRepository repository;

  public VehiculoController(VehiculoRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/{vin}")
  public Map<String, Object> consultar(@PathVariable String vin, @RequestParam(defaultValue = "0") long km) {
    Vehiculo v = repository.findById(vin).orElse(null);
    String campana = v != null ? v.getCampanaActiva() : null;
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("vin", vin);
    r.put("registrado", v != null);
    r.put("modelo", v != null ? v.getModelo() : null);
    r.put("historyRecordId", "HIST-" + vin);
    r.put("warrantyEligible", km <= GARANTIA_MAX_KM);
    r.put("campaignActive", campana != null);
    r.put("campaignId", campana);
    return r;
  }
}
