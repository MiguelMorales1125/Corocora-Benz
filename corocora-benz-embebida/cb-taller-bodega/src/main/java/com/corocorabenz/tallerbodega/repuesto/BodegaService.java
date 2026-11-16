package com.corocorabenz.tallerbodega.repuesto;

import com.corocorabenz.tallerbodega.motor.MotorClient;
import com.corocorabenz.tallerbodega.simulacion.Simulacion;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Entrega de repuestos: cuando el repuesto llega, la bodega avisa al motor con el mensaje
 * RepuestoRecibido. La entrega solo se da por hecha cuando el motor la acepta; si el proceso
 * todavía no espera el repuesto, se reintenta.
 */
@Service
public class BodegaService {

  private static final Logger LOG = LoggerFactory.getLogger(BodegaService.class);
  private static final int MAX_INTENTOS = 60;

  private final SolicitudRepuestoRepository repository;
  private final MotorClient motor;
  private final Simulacion simulacion;

  public BodegaService(SolicitudRepuestoRepository repository, MotorClient motor, Simulacion simulacion) {
    this.repository = repository;
    this.motor = motor;
    this.simulacion = simulacion;
  }

  /** @return true si el motor recibió el aviso. */
  public boolean entregar(SolicitudRepuesto s) {
    String remision = "REM-" + s.getId();
    boolean ok = motor.enviarMensaje("RepuestoRecibido", s.getBusinessKey(), Map.of("partReceivedRef", remision));
    if (ok) {
      s.setEstado(SolicitudRepuesto.Estado.ENTREGADA);
      s.setRemision(remision);
      s.setEntregadaEn(Instant.now());
      LOG.info("Repuesto {} entregado al taller (remisión {}, proceso {})", s.getPartCode(), remision,
          s.getBusinessKey());
    } else {
      s.setIntentosRespuesta(s.getIntentosRespuesta() + 1);
      if (s.getIntentosRespuesta() % MAX_INTENTOS == 0) {
        LOG.warn("No fue posible entregar la respuesta al motor; se volverá a intentar");
      }
    }
    repository.save(s);
    return ok;
  }

  @Scheduled(fixedDelayString = "${corocora.simulacion.intervalo-ms:2000}")
  public void entregarAutomaticamente() {
    if (!simulacion.isRespuestaAutomatica()) {
      return;
    }
    Instant limite = Instant.now().minusSeconds(simulacion.getRetardoSegundos());
    repository.findByEstadoAndSolicitadaEnBefore(SolicitudRepuesto.Estado.SOLICITADA, limite).forEach(this::entregar);
  }
}
