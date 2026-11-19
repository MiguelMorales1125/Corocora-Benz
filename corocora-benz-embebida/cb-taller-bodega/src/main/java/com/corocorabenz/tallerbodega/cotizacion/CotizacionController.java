package com.corocorabenz.tallerbodega.cotizacion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.web.server.ResponseStatusException;

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
    this.repository = repository;
    this.estados = estados;
  }

  public record NuevaCotizacion(
      @NotBlank(message = "businessKey es obligatoria")
      @Size(max = 255, message = "businessKey admite máximo 255 caracteres")
      String businessKey,
      // @NotNull y no @NotBlank: un VIN vacío ya lo rechaza el patrón, y así cada caso tiene un solo mensaje.
      @NotNull(message = "vin es obligatorio")
      @Pattern(regexp = "[A-HJ-NPR-Z0-9]{17}", message = "vin debe tener 17 letras mayúsculas o dígitos, sin I, O ni Q")
      String vin,
      @NotBlank(message = "customerName es obligatorio")
      @Size(max = 255, message = "customerName admite máximo 255 caracteres")
      String customerName,
      @NotBlank(message = "symptom es obligatorio")
      @Size(max = 255, message = "symptom admite máximo 255 caracteres")
      String symptom) {
  }

  /**
   * El motor reintenta el HTTP Connector si no recibe la respuesta: la misma petición
   * devuelve la cotización que ya existe en vez de crear otra. Es {@code synchronized}
   * para que dos reintentos simultáneos no pasen ambos la comprobación.
   */
  @PostMapping
  public synchronized ResponseEntity<Map<String, Object>> crear(@Valid @RequestBody NuevaCotizacion nueva) {
    List<Cotizacion> existentes = repository.findByBusinessKeyOrderByIdDesc(nueva.businessKey());
    if (!existentes.isEmpty()) {
      Cotizacion anterior = existentes.get(0);
      if (!tieneLosMismosDatos(anterior, nueva)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud ya tiene otra cotización");
      }
      return ResponseEntity.ok(resumen(anterior));
    }
    Cotizacion cotizacion = new Cotizacion();
    cotizacion.setBusinessKey(nueva.businessKey());
    cotizacion.setVin(nueva.vin());
    cotizacion.setCustomerName(nueva.customerName());
    cotizacion.setSymptom(nueva.symptom());
    cotizacion.setMonto(MONTO_ESTANDAR);
    cotizacion = repository.save(cotizacion);
    LOG.info("Cotización {} por {} para el vehículo {} (proceso {})",
        cotizacion.getQuoteId(), cotizacion.getMonto(), cotizacion.getVin(), cotizacion.getBusinessKey());
    return ResponseEntity.status(HttpStatus.CREATED).body(resumen(cotizacion));
  }

  /** Antes de responder pone al día las cotizaciones que el cliente ya aceptó, rechazó o dejó vencer. */
  @GetMapping
  public List<Cotizacion> listar(@RequestParam(required = false) String businessKey) {
    List<Cotizacion> cotizaciones = businessKey == null
        ? repository.findAllByOrderByIdDesc()
        : repository.findByBusinessKeyOrderByIdDesc(businessKey);
    return estados.actualizar(cotizaciones);
  }

  private static boolean tieneLosMismosDatos(Cotizacion anterior, NuevaCotizacion nueva) {
    return Objects.equals(anterior.getVin(), nueva.vin())
        && Objects.equals(anterior.getCustomerName(), nueva.customerName())
        && Objects.equals(anterior.getSymptom(), nueva.symptom());
  }

  private static Map<String, Object> resumen(Cotizacion cotizacion) {
    return Map.of("quoteId", cotizacion.getQuoteId(), "quoteAmount", cotizacion.getMonto());
  }
}
