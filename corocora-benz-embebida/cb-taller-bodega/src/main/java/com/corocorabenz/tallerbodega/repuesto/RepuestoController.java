package com.corocorabenz.tallerbodega.repuesto;

import com.corocorabenz.tallerbodega.motor.MotorClient;
import com.corocorabenz.tallerbodega.simulacion.Simulacion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
  private final Simulacion simulacion;
  private final MotorClient motor;

  public RepuestoController(ReservaRepository reservas, SolicitudRepuestoRepository solicitudes, BodegaService bodega, Simulacion simulacion, MotorClient motor) {
    this.reservas = reservas;
    this.solicitudes = solicitudes;
    this.bodega = bodega; this.simulacion = simulacion; this.motor = motor;
  }

  public record NuevaReserva(@NotBlank String businessKey, @NotBlank String vin) {}

  @PostMapping("/api/reservas")
  public synchronized ResponseEntity<Map<String, Object>> reservar(@Valid @RequestBody NuevaReserva nueva) {
    var previa = reservas.findFirstByBusinessKeyOrderByIdDesc(nueva.businessKey());
    if (previa.isPresent()) {
      Reserva anterior = previa.get();
      if (!nueva.vin().equals(anterior.getVin())) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "La solicitud ya tiene una reserva de otro vehículo"));
      return ResponseEntity.ok(Map.of("reservationId", anterior.getId(), "partsReservationStatus", anterior.getEstado()));
    }
    Reserva r = new Reserva();
    r.setBusinessKey(nueva.businessKey());
    r.setVin(nueva.vin());
    r.setEstado(simulacion.isRepuestosDisponibles() ? "RESERVED" : "PENDIENTE");
    r = reservas.save(r);
    LOG.info("Repuestos reservados para el vehículo {} (reserva {}, proceso {})", r.getVin(), r.getId(),
        r.getBusinessKey());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("reservationId", r.getId(), "partsReservationStatus", r.getEstado()));
  }

  /** Simula el aviso de Bodega: la instancia solo continúa si está esperando el mensaje. */
  @PostMapping("/api/reservas/{id}/disponibles")
  public synchronized ResponseEntity<?> avisarDisponibilidad(@PathVariable Long id) {
    Reserva reserva = reservas.findById(id).orElse(null);
    if (reserva == null) return ResponseEntity.notFound().build();
    if ("RESERVED".equals(reserva.getEstado())) return ResponseEntity.ok(reserva);
    if (!"PENDIENTE".equals(reserva.getEstado())) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Estado de reserva no válido"));
    if (!motor.enviarMensaje("RepuestosDisponibles", reserva.getBusinessKey(), Map.of("partsReservationStatus", "RESERVED")))
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "El proceso todavía no espera el aviso de Bodega"));
    reserva.setEstado("RESERVED"); reservas.save(reserva);
    return ResponseEntity.ok(reserva);
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
