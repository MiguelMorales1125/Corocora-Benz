package com.corocorabenz.tallerbodega.repuesto;

import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.tallerbodega.motor.AvisoAlMotor;
import com.corocorabenz.tallerbodega.motor.ResultadoAviso;
import com.corocorabenz.tallerbodega.simulacion.Simulacion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Avisos de la bodega al motor: RepuestoRecibido cuando entrega un repuesto solicitado y
 * RepuestosDisponibles cuando llegan los repuestos de una reserva pendiente.
 *
 * <p>Un registro solo cambia de estado cuando el motor acepta el aviso. Si el proceso sigue
 * activo pero aún no lo espera, o el motor no responde, queda pendiente para reintentar;
 * si el proceso ya no existe pasa a SIN_PROCESO (sección 7 del contrato de integración).
 */
@Service
public class BodegaService {

  static final String MENSAJE_REPUESTO_RECIBIDO = "RepuestoRecibido";
  static final String MENSAJE_REPUESTOS_DISPONIBLES = "RepuestosDisponibles";

  private static final Logger LOG = LoggerFactory.getLogger(BodegaService.class);
  private static final int INTENTOS_ENTRE_ADVERTENCIAS = 60;

  private final SolicitudRepuestoRepository solicitudes;
  private final ReservaRepository reservas;
  private final AvisoAlMotor avisos;
  private final Simulacion simulacion;

  // El ciclo automático y los endpoints manuales pueden intentar el mismo aviso a la vez.
  // Se atienden de uno en uno y cada uno relee el registro, para no enviar dos veces el
  // mensaje ni pisar con datos viejos una entrega que ya quedó registrada.
  private final Object turno = new Object();

  public BodegaService(SolicitudRepuestoRepository solicitudes, ReservaRepository reservas, AvisoAlMotor avisos,
      Simulacion simulacion) {
    this.solicitudes = solicitudes;
    this.reservas = reservas;
    this.avisos = avisos;
    this.simulacion = simulacion;
  }

  /**
   * Entrega al taller el repuesto de una solicitud y avisa al motor.
   *
   * @param idSolicitud id de una solicitud que existe
   * @throws MotorException si el motor rechaza el aviso por un error que no es de negocio
   */
  public ResultadoAviso entregar(Long idSolicitud) {
    synchronized (turno) {
      SolicitudRepuesto solicitud = solicitudes.findById(idSolicitud).orElseThrow();
      if (solicitud.getEstado() != SolicitudRepuesto.Estado.SOLICITADA) {
        return ResultadoAviso.NO_PENDIENTE;
      }
      String remision = "REM-" + solicitud.getId();
      ResultadoAviso resultado = avisos.enviar(
          MENSAJE_REPUESTO_RECIBIDO,
          solicitud.getBusinessKey(),
          Map.of("partReceivedRef", remision));
      registrar(solicitud, remision, resultado);
      return resultado;
    }
  }

  /**
   * Avisa al motor que ya llegaron los repuestos de una reserva pendiente.
   *
   * @param idReserva id de una reserva que existe
   * @throws MotorException si el motor rechaza el aviso por un error que no es de negocio
   */
  public ResultadoAviso avisarDisponibilidad(Long idReserva) {
    synchronized (turno) {
      Reserva reserva = reservas.findById(idReserva).orElseThrow();
      if (!Reserva.PENDIENTE.equals(reserva.getEstado())) {
        return ResultadoAviso.NO_PENDIENTE;
      }
      ResultadoAviso resultado = avisos.enviar(
          MENSAJE_REPUESTOS_DISPONIBLES,
          reserva.getBusinessKey(),
          Map.of("partsReservationStatus", Reserva.RESERVADA));
      registrar(reserva, resultado);
      return resultado;
    }
  }

  @Scheduled(fixedDelayString = "${corocora.simulacion.intervalo-ms:2000}")
  public void entregarAutomaticamente() {
    if (!simulacion.isRespuestaAutomatica()) {
      return;
    }
    Instant limite = Instant.now().minusSeconds(simulacion.getRetardoSegundos());
    List<SolicitudRepuesto> pendientes =
        solicitudes.findByEstadoAndSolicitadaEnBefore(SolicitudRepuesto.Estado.SOLICITADA, limite);
    for (SolicitudRepuesto pendiente : pendientes) {
      ResultadoAviso resultado = entregarSinInterrumpirElCiclo(pendiente);
      if (resultado == ResultadoAviso.MOTOR_NO_DISPONIBLE) {
        // Las demás fallarían igual; se dejan para el siguiente ciclo.
        return;
      }
    }
  }

  /** Un rechazo del motor para una solicitud no debe impedir la entrega de las siguientes. */
  private ResultadoAviso entregarSinInterrumpirElCiclo(SolicitudRepuesto pendiente) {
    try {
      return entregar(pendiente.getId());
    } catch (MotorException e) {
      LOG.warn("El motor rechazó la entrega de {} (HTTP {}); se volverá a intentar",
          pendiente.getPartRequestId(), e.getStatus());
      return ResultadoAviso.AUN_NO_ESPERADO;
    }
  }

  private void registrar(SolicitudRepuesto solicitud, String remision, ResultadoAviso resultado) {
    if (resultado == ResultadoAviso.ENTREGADO) {
      solicitud.setEstado(SolicitudRepuesto.Estado.ENTREGADA);
      solicitud.setRemision(remision);
      solicitud.setEntregadaEn(Instant.now());
      LOG.info("Repuesto {} entregado al taller (remisión {}, proceso {})",
          solicitud.getPartCode(), remision, solicitud.getBusinessKey());
    } else if (resultado == ResultadoAviso.SIN_PROCESO) {
      solicitud.setEstado(SolicitudRepuesto.Estado.SIN_PROCESO);
      LOG.warn("La solicitud {} queda sin proceso: ya no hay una instancia activa con la clave {}",
          solicitud.getPartRequestId(), solicitud.getBusinessKey());
    } else {
      contarIntento(solicitud);
    }
    solicitudes.save(solicitud);
  }

  private void contarIntento(SolicitudRepuesto solicitud) {
    solicitud.setIntentosRespuesta(solicitud.getIntentosRespuesta() + 1);
    if (solicitud.getIntentosRespuesta() % INTENTOS_ENTRE_ADVERTENCIAS == 0) {
      LOG.warn("La solicitud {} lleva {} intentos de entrega sin que el motor la acepte; se volverá a intentar",
          solicitud.getPartRequestId(), solicitud.getIntentosRespuesta());
    }
  }

  private void registrar(Reserva reserva, ResultadoAviso resultado) {
    if (resultado == ResultadoAviso.ENTREGADO) {
      reserva.setEstado(Reserva.RESERVADA);
      LOG.info("Repuestos disponibles para la reserva {} (proceso {})", reserva.getId(), reserva.getBusinessKey());
    } else if (resultado == ResultadoAviso.SIN_PROCESO) {
      reserva.setEstado(Reserva.SIN_PROCESO);
      LOG.warn("La reserva {} queda sin proceso: ya no hay una instancia activa con la clave {}",
          reserva.getId(), reserva.getBusinessKey());
    } else {
      // Sigue PENDIENTE: no hay nada que guardar.
      return;
    }
    reservas.save(reserva);
  }
}
