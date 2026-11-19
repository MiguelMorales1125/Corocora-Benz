package com.corocorabenz.tallerbodega.repuesto;

import com.corocorabenz.comun.web.ErroresApi;
import com.corocorabenz.tallerbodega.motor.ResultadoAviso;
import com.corocorabenz.tallerbodega.simulacion.Simulacion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Bodega de repuestos.
 * <ul>
 *   <li>{@code POST /api/reservas}: el motor reserva los repuestos ("Reservar repuestos").</li>
 *   <li>{@code POST /api/reservas/{id}/disponibles}: aviso manual de que llegaron los repuestos de una
 *       reserva pendiente; avisa al motor con RepuestosDisponibles.</li>
 *   <li>{@code POST /api/solicitudes-repuesto}: el motor pide un repuesto adicional ("Solicitar repuesto a bodega").</li>
 *   <li>{@code POST /api/solicitudes-repuesto/{id}/entregar}: entrega manual; avisa al motor con RepuestoRecibido.</li>
 * </ul>
 * Los dos avisos manuales responden 409 mientras el proceso no los espere y 502 si el motor no responde.
 */
@RestController
public class RepuestoController {

  private static final Logger LOG = LoggerFactory.getLogger(RepuestoController.class);
  private static final String SIN_CODIGO = "SIN-CODIGO";

  private final ReservaRepository reservas;
  private final SolicitudRepuestoRepository solicitudes;
  private final BodegaService bodega;
  private final Simulacion simulacion;

  public RepuestoController(ReservaRepository reservas, SolicitudRepuestoRepository solicitudes, BodegaService bodega,
      Simulacion simulacion) {
    this.reservas = reservas;
    this.solicitudes = solicitudes;
    this.bodega = bodega;
    this.simulacion = simulacion;
  }

  public record NuevaReserva(
      @NotBlank(message = "businessKey es obligatoria")
      @Size(max = 255, message = "businessKey admite máximo 255 caracteres")
      String businessKey,
      @NotBlank(message = "vin es obligatorio")
      @Size(max = 255, message = "vin admite máximo 255 caracteres")
      String vin) {
  }

  /**
   * El motor reintenta el HTTP Connector si no recibe la respuesta: la misma petición
   * devuelve la reserva que ya existe en vez de crear otra. Es {@code synchronized}
   * para que dos reintentos simultáneos no pasen ambos la comprobación.
   */
  @PostMapping("/api/reservas")
  public synchronized ResponseEntity<Map<String, Object>> reservar(@Valid @RequestBody NuevaReserva nueva) {
    Optional<Reserva> vigente = reservaVigente(nueva.businessKey());
    if (vigente.isPresent()) {
      Reserva anterior = vigente.get();
      if (!nueva.vin().equals(anterior.getVin())) {
        throw conflicto("La solicitud ya tiene una reserva de otro vehículo");
      }
      return ResponseEntity.ok(resumen(anterior));
    }
    Reserva reserva = new Reserva();
    reserva.setBusinessKey(nueva.businessKey());
    reserva.setVin(nueva.vin());
    reserva.setEstado(simulacion.isRepuestosDisponibles() ? Reserva.RESERVADA : Reserva.PENDIENTE);
    reserva = reservas.save(reserva);
    LOG.info("Reserva {} en estado {} para el vehículo {} (proceso {})",
        reserva.getId(), reserva.getEstado(), reserva.getVin(), reserva.getBusinessKey());
    return ResponseEntity.status(HttpStatus.CREATED).body(resumen(reserva));
  }

  /** Simula el aviso de Bodega: la instancia solo continúa si está esperando el mensaje. */
  @PostMapping("/api/reservas/{id}/disponibles")
  public Reserva avisarDisponibilidad(@PathVariable Long id) {
    exigirReserva(id);
    ResultadoAviso resultado = bodega.avisarDisponibilidad(id);
    Reserva reserva = exigirReserva(id);
    boolean yaAvisada = resultado == ResultadoAviso.NO_PENDIENTE && Reserva.RESERVADA.equals(reserva.getEstado());
    if (resultado == ResultadoAviso.ENTREGADO || yaAvisada) {
      return reserva;
    }
    throw rechazoDelAviso(reserva, resultado);
  }

  @GetMapping("/api/reservas")
  public List<Reserva> listarReservas() {
    return reservas.findAllByOrderByIdDesc();
  }

  public record NuevaSolicitud(
      @NotBlank(message = "businessKey es obligatoria")
      @Size(max = 255, message = "businessKey admite máximo 255 caracteres")
      String businessKey,
      @Size(max = 255, message = "vin admite máximo 255 caracteres")
      String vin,
      @Size(max = 255, message = "partCode admite máximo 255 caracteres")
      String partCode) {
  }

  @PostMapping("/api/solicitudes-repuesto")
  public ResponseEntity<Map<String, Object>> solicitar(@Valid @RequestBody NuevaSolicitud nueva) {
    SolicitudRepuesto solicitud = new SolicitudRepuesto();
    solicitud.setBusinessKey(nueva.businessKey());
    solicitud.setVin(nueva.vin());
    solicitud.setPartCode(nueva.partCode() == null || nueva.partCode().isBlank() ? SIN_CODIGO : nueva.partCode());
    solicitud = solicitudes.save(solicitud);
    LOG.info("Repuesto {} solicitado para el vehículo {} ({}, proceso {})",
        solicitud.getPartCode(), solicitud.getVin(), solicitud.getPartRequestId(), solicitud.getBusinessKey());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("partRequestId", solicitud.getPartRequestId(), "estado", solicitud.getEstado().name()));
  }

  @GetMapping("/api/solicitudes-repuesto")
  public List<SolicitudRepuesto> listarSolicitudes(@RequestParam(required = false) String businessKey) {
    if (businessKey == null) {
      return solicitudes.findAllByOrderByIdDesc();
    }
    return solicitudes.findByBusinessKeyOrderByIdDesc(businessKey);
  }

  @PostMapping("/api/solicitudes-repuesto/{id}/entregar")
  public SolicitudRepuesto entregar(@PathVariable Long id) {
    exigirSolicitud(id);
    ResultadoAviso resultado = bodega.entregar(id);
    SolicitudRepuesto solicitud = exigirSolicitud(id);
    if (resultado == ResultadoAviso.ENTREGADO) {
      return solicitud;
    }
    throw rechazoDeLaEntrega(solicitud, resultado);
  }

  /**
   * Una reserva que quedó SIN_PROCESO pertenece a un proceso que ya no existe: si otro
   * proceso llega con la misma clave se le crea una reserva nueva, porque el BPMN
   * solo sabe continuar con RESERVED o PENDIENTE.
   */
  private Optional<Reserva> reservaVigente(String businessKey) {
    return reservas.findFirstByBusinessKeyOrderByIdDesc(businessKey)
        .filter(reserva -> !Reserva.SIN_PROCESO.equals(reserva.getEstado()));
  }

  private Reserva exigirReserva(Long id) {
    return reservas.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "La reserva " + id + " no existe"));
  }

  private SolicitudRepuesto exigirSolicitud(Long id) {
    return solicitudes.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "La solicitud de repuesto " + id + " no existe"));
  }

  private static ResponseStatusException rechazoDelAviso(Reserva reserva, ResultadoAviso resultado) {
    if (resultado == ResultadoAviso.MOTOR_NO_DISPONIBLE) {
      return motorCaido();
    }
    if (resultado == ResultadoAviso.AUN_NO_ESPERADO) {
      return conflicto("El proceso todavía no espera el aviso de Bodega");
    }
    if (resultado == ResultadoAviso.SIN_PROCESO) {
      return conflicto("El proceso " + reserva.getBusinessKey() + " ya no existe; la reserva quedó sin proceso");
    }
    return conflicto("La reserva está " + reserva.getEstado() + " y ya no admite el aviso");
  }

  private static ResponseStatusException rechazoDeLaEntrega(SolicitudRepuesto solicitud, ResultadoAviso resultado) {
    if (resultado == ResultadoAviso.MOTOR_NO_DISPONIBLE) {
      return motorCaido();
    }
    if (resultado == ResultadoAviso.AUN_NO_ESPERADO) {
      return conflicto("El proceso " + solicitud.getBusinessKey() + " todavía no espera el repuesto");
    }
    if (resultado == ResultadoAviso.SIN_PROCESO) {
      return conflicto("El proceso " + solicitud.getBusinessKey() + " ya no existe; la solicitud quedó sin proceso");
    }
    return conflicto("La solicitud ya está " + solicitud.getEstado());
  }

  private static ResponseStatusException conflicto(String mensaje) {
    return new ResponseStatusException(HttpStatus.CONFLICT, mensaje);
  }

  private static ResponseStatusException motorCaido() {
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ErroresApi.MENSAJE_MOTOR_CAIDO);
  }

  private static Map<String, Object> resumen(Reserva reserva) {
    return Map.of("reservationId", reserva.getId(), "partsReservationStatus", reserva.getEstado());
  }
}
