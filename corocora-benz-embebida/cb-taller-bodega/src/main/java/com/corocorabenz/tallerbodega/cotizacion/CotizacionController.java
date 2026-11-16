package com.corocorabenz.tallerbodega.cotizacion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
  private final EstadoCotizacionService estados;

  public CotizacionController(CotizacionRepository repository, EstadoCotizacionService estados) {
    this.repository = repository; this.estados = estados;
  }

  public record NuevaCotizacion(@NotBlank @Size(max=255) String businessKey, @NotBlank @Pattern(regexp="[A-HJ-NPR-Z0-9]{17}") String vin, @NotBlank @Size(max=255) String customerName, @NotBlank @Size(max=255) String symptom) {}

  @PostMapping
  public synchronized ResponseEntity<Map<String, Object>> crear(@Valid @RequestBody NuevaCotizacion nueva) {
    List<Cotizacion> existentes = repository.findByBusinessKeyOrderByIdDesc(nueva.businessKey());
    if (!existentes.isEmpty()) {
      Cotizacion anterior = existentes.get(0);
      if (!java.util.Objects.equals(anterior.getVin(), nueva.vin()) || !java.util.Objects.equals(anterior.getCustomerName(), nueva.customerName()) || !java.util.Objects.equals(anterior.getSymptom(), nueva.symptom()))
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "La solicitud ya tiene otra cotización"));
      return ResponseEntity.ok(Map.of("quoteId", anterior.getQuoteId(), "quoteAmount", anterior.getMonto()));
    }
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
    List<Cotizacion> lista = businessKey == null ? repository.findAllByOrderByIdDesc()
        : repository.findByBusinessKeyOrderByIdDesc(businessKey);
    return lista.stream().map(estados::actualizar).toList();
  }
}
