package com.corocorabenz.tallerbodega.cotizacion;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cotizaciones. El motor la pide en "Enviar cotización al cliente". */
@RestController
@RequestMapping("/api/cotizaciones")
public class CotizacionController {

  private static final Logger LOG = LoggerFactory.getLogger(CotizacionController.class);

  /** Valor de la cotización estándar de diagnóstico y reparación (simulado). */
  static final long MONTO_ESTANDAR = 850_000;

  private final CotizacionRepository repository;

  public CotizacionController(CotizacionRepository repository) {
    this.repository = repository;
  }

  public record NuevaCotizacion(String businessKey, String vin, String customerName, String symptom) {}

  @PostMapping
  public ResponseEntity<Map<String, Object>> crear(@RequestBody NuevaCotizacion nueva) {
    Cotizacion c = new Cotizacion();
    c.setBusinessKey(nueva.businessKey());
    c.setVin(nueva.vin());
    c.setCustomerName(nueva.customerName());
    c.setSymptom(nueva.symptom());
    c.setMonto(MONTO_ESTANDAR);
    c = repository.save(c);
    LOG.info("Cotización {} por {} para el vehículo {} (proceso {})", c.getQuoteId(), c.getMonto(), c.getVin(),
        c.getBusinessKey());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("quoteId", c.getQuoteId(), "quoteAmount", c.getMonto()));
  }

  @GetMapping
  public List<Cotizacion> listar(@RequestParam(required = false) String businessKey) {
    return businessKey == null ? repository.findAllByOrderByIdDesc()
        : repository.findByBusinessKeyOrderByIdDesc(businessKey);
  }
}
