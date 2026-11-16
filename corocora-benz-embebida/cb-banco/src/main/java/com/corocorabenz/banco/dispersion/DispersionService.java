package com.corocorabenz.banco.dispersion;

import com.corocorabenz.banco.motor.MotorClient;
import com.corocorabenz.banco.simulacion.Simulacion;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lógica del banco: recibe dispersiones del motor y le responde con DispersionAceptada o
 * DispersionRechazada. La respuesta solo se da por hecha cuando el motor la acepta: si el proceso
 * todavía no está esperando (por ejemplo, porque su transacción no ha terminado), se reintenta.
 */
@Service
public class DispersionService {

  private static final Logger LOG = LoggerFactory.getLogger(DispersionService.class);

  /** Cuentas válidas: tres dígitos, guion y al menos tres dígitos (p. ej. 000-123). */
  static final Pattern CUENTA_VALIDA = Pattern.compile("^\\d{3}-\\d{3,}$");
  static final String CODIGO_CUENTA_INVALIDA = "R03-CUENTA-INVALIDA";
  private static final int MAX_INTENTOS = 60;

  private final DispersionRepository repository;
  private final MotorClient motor;
  private final Simulacion simulacion;

  public DispersionService(DispersionRepository repository, MotorClient motor, Simulacion simulacion) {
    this.repository = repository;
    this.motor = motor;
    this.simulacion = simulacion;
  }

  public record NuevaDispersion(String businessKey, String paymentBatchId, String bankAccount,
      Long payrollTotal, String payPeriodId) {}

  @Transactional
  public Dispersion recibir(NuevaDispersion nueva) {
    // Si el proceso reenvía el lote, la dispersión anterior sin responder ya no aplica.
    for (Dispersion anterior : repository.findByBusinessKeyAndEstado(nueva.businessKey(), EstadoDispersion.RECIBIDA)) {
      anterior.setEstado(EstadoDispersion.REEMPLAZADA);
    }
    Dispersion d = new Dispersion();
    d.setBusinessKey(nueva.businessKey());
    d.setPaymentBatchId(nueva.paymentBatchId());
    d.setBankAccount(nueva.bankAccount());
    d.setPayrollTotal(nueva.payrollTotal());
    d.setPayPeriodId(nueva.payPeriodId());
    d = repository.save(d);
    LOG.info("Dispersión {} recibida: lote {} por {} desde la cuenta {} (proceso {})",
        d.getId(), d.getPaymentBatchId(), d.getPayrollTotal(), d.getBankAccount(), d.getBusinessKey());
    return d;
  }

  /** @return true si el motor recibió la respuesta. */
  public boolean aceptar(Dispersion d, String referencia) {
    String ref = referencia != null && !referencia.isBlank() ? referencia : "BANCO-" + d.getId();
    boolean ok = motor.enviarMensaje("DispersionAceptada", d.getBusinessKey(),
        Map.of("bankReference", ref, "bankResponseCode", "00-APROBADA"));
    return registrar(d, ok, EstadoDispersion.ACEPTADA, "00-APROBADA", ref);
  }

  /** @return true si el motor recibió la respuesta. */
  public boolean rechazar(Dispersion d, String codigo) {
    String cod = codigo != null && !codigo.isBlank() ? codigo : CODIGO_CUENTA_INVALIDA;
    boolean ok = motor.enviarMensaje("DispersionRechazada", d.getBusinessKey(), Map.of("bankResponseCode", cod));
    return registrar(d, ok, EstadoDispersion.RECHAZADA, cod, null);
  }

  private boolean registrar(Dispersion d, boolean ok, EstadoDispersion estado, String codigo, String referencia) {
    if (ok) {
      d.setEstado(estado);
      d.setCodigoRespuesta(codigo);
      d.setReferenciaBancaria(referencia);
      d.setRespondidaEn(Instant.now());
      LOG.info("Dispersión {} {} ({})", d.getId(), estado, codigo);
    } else {
      d.setIntentosRespuesta(d.getIntentosRespuesta() + 1);
      if (d.getIntentosRespuesta() >= MAX_INTENTOS) {
        d.setEstado(EstadoDispersion.SIN_PROCESO);
        LOG.warn("Dispersión {}: el proceso {} nunca esperó la respuesta", d.getId(), d.getBusinessKey());
      }
    }
    repository.save(d);
    return ok;
  }

  /** Respuesta automática del banco: rechaza las cuentas con formato inválido y acepta el resto. */
  @Scheduled(fixedDelayString = "${corocora.simulacion.intervalo-ms:2000}")
  public void responderAutomaticamente() {
    if (!simulacion.isRespuestaAutomatica()) {
      return;
    }
    Instant limite = Instant.now().minusSeconds(simulacion.getRetardoSegundos());
    for (Dispersion d : repository.findByEstadoAndRecibidaEnBefore(EstadoDispersion.RECIBIDA, limite)) {
      if (d.getBankAccount() != null && CUENTA_VALIDA.matcher(d.getBankAccount()).matches()) {
        aceptar(d, null);
      } else {
        rechazar(d, CODIGO_CUENTA_INVALIDA);
      }
    }
  }
}
