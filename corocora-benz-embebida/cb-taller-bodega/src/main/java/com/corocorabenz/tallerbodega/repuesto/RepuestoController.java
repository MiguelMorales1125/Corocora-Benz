package com.corocorabenz.tallerbodega.repuesto;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bodega de repuestos.
 * <ul>
 *   <li>{@code POST /api/reservas}: el motor reserva los repuestos ("Reservar repuestos").</li>
 *   <li>{@code POST /api/solicitudes-repuesto}: el motor pide un repuesto adicional ("Solicitar repuesto a bodega").</li>
 *   <li>{@code POST /api/solicitudes-repuesto/{id}/entregar}: entrega manual; avisa al motor con RepuestoRecibido.</li>
 * </ul>
 */
@RestController
public class RepuestoController {

  private static final Logger LOG = LoggerFactory.getLogger(RepuestoController.class);

  private final ReservaRepository reservas;
  private final SolicitudRepuestoRepository solicitudes;
  private final BodegaService bodega;

  public RepuestoController(ReservaRepository reservas, SolicitudRepuestoRepository solicitudes, BodegaService bodega) {
    this.reservas = reservas;
    this.solicitudes = solicitudes;
    this.bodega = bodega;
  }

  public record NuevaReserva(String businessKey, String vin) {}

  @PostMapping("/api/reservas")
  public ResponseEntity<Map<String, Object>> reservar(@RequestBody NuevaReserva nueva) {
    Reserva r = new Reserva();
    r.setBusinessKey(nueva.businessKey());
    r.setVin(nueva.vin());
    r = reservas.save(r);
    LOG.info("Repuestos reservados para el vehículo {} (reserva {}, proceso {})", r.getVin(), r.getId(),
        r.getBusinessKey());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("reservationId", r.getId(), "partsReservationStatus", r.getEstado()));
  }

  @GetMapping("/api/reservas")
  public List<Reserva> listarReservas() {
    return reservas.findAllByOrderByIdDesc();
  }

  public record NuevaSolicitud(String businessKey, String vin, String partCode) {}

  @PostMapping("/api/solicitudes-repuesto")
  public ResponseEntity<Map<String, Object>> solicitar(@RequestBody NuevaSolicitud nueva) {
    SolicitudRepuesto s = new SolicitudRepuesto();
    s.setBusinessKey(nueva.businessKey());
    s.setVin(nueva.vin());
    s.setPartCode(nueva.partCode() == null || nueva.partCode().isBlank() ? "SIN-CODIGO" : nueva.partCode());
    s = solicitudes.save(s);
    LOG.info("Repuesto {} solicitado para el vehículo {} ({}, proceso {})", s.getPartCode(), s.getVin(),
        s.getPartRequestId(), s.getBusinessKey());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("partRequestId", s.getPartRequestId(), "estado", s.getEstado().name()));
  }

  @GetMapping("/api/solicitudes-repuesto")
  public List<SolicitudRepuesto> listarSolicitudes(@RequestParam(required = false) String businessKey) {
    return businessKey == null ? solicitudes.findAllByOrderByIdDesc()
        : solicitudes.findByBusinessKeyOrderByIdDesc(businessKey);
  }

  @PostMapping("/api/solicitudes-repuesto/{id}/entregar")
  public ResponseEntity<?> entregar(@PathVariable Long id) {
    SolicitudRepuesto s = solicitudes.findById(id).orElse(null);
    if (s == null) {
      return ResponseEntity.notFound().build();
    }
    if (s.getEstado() != SolicitudRepuesto.Estado.SOLICITADA) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "La solicitud ya está " + s.getEstado()));
    }
    if (!bodega.entregar(s)) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", "El proceso " + s.getBusinessKey() + " todavía no espera el repuesto"));
    }
    return ResponseEntity.ok(s);
  }
}
