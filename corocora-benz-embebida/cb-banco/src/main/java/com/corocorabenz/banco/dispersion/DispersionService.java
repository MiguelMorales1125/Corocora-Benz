package com.corocorabenz.banco.dispersion;

import com.corocorabenz.banco.simulacion.Simulacion;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.corocorabenz.comun.motor.ResultadoMensaje;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lógica del banco: recibe dispersiones del motor y le responde con DispersionAceptada o
 * DispersionRechazada. La respuesta solo se da por hecha cuando el motor la entrega a un proceso.
 * Si el proceso todavía no la espera (por ejemplo, porque su transacción no ha terminado) se
 * reintenta mientras exista una instancia activa con esa businessKey; si ya no existe, la
 * dispersión queda {@link EstadoDispersion#SIN_PROCESO} (sección 7 del contrato de integración).
 */
@Service
public class DispersionService {

  private static final Logger LOG = LoggerFactory.getLogger(DispersionService.class);

  /** Cuentas válidas: tres dígitos, guion y al menos tres dígitos (p. ej. 000-123). */
  static final Pattern CUENTA_VALIDA = Pattern.compile("^\\d{3}-\\d{3,}$");
  static final String CODIGO_APROBADA = "00-APROBADA";
  static final String CODIGO_CUENTA_INVALIDA = "R03-CUENTA-INVALIDA";

  /** Un lote reenviado es "el mismo" solo si la dispersión anterior sigue pendiente o fue aceptada. */
  private static final Set<EstadoDispersion> ESTADOS_VIGENTES =
      EnumSet.of(EstadoDispersion.RECIBIDA, EstadoDispersion.ACEPTADA);

  /** Con un ciclo cada 2 s, un aviso en el log cada dos minutos por dispersión sin entregar. */
  private static final int INTENTOS_ENTRE_AVISOS = 60;

  private final DispersionRepository repository;
  private final MotorClient motor;
  private final Simulacion simulacion;
  private final TransactionTemplate transaccion;

  /** Serializa las escrituras sobre las dispersiones (ver {@link #recibir} y {@link #actualizar}). */
  private final Object candado = new Object();

  public DispersionService(DispersionRepository repository, MotorClient motor, Simulacion simulacion,
      TransactionTemplate transaccion) {
    this.repository = repository;
    this.motor = motor;
    this.simulacion = simulacion;
    this.transaccion = transaccion;
  }

  public record NuevaDispersion(String businessKey, String paymentBatchId, String bankAccount,
      Long payrollTotal, String payPeriodId) {}

  /** Qué pasó al intentar entregar al motor la respuesta de una dispersión. */
  public enum ResultadoRespuesta {
    /** El motor entregó la respuesta al proceso; la dispersión quedó aceptada o rechazada. */
    ENTREGADA,
    /** El proceso existe pero todavía no espera la respuesta; se puede reintentar. */
    PROCESO_NO_ESPERA,
    /** Ya no hay un proceso activo con esa businessKey; la dispersión quedó SIN_PROCESO. */
    SIN_PROCESO,
    /** El motor no respondió; se puede reintentar. */
    MOTOR_NO_DISPONIBLE
  }

  /** Lo que el banco le contesta al proceso y cómo queda registrada la dispersión al entregarlo. */
  private record RespuestaBanco(String mensaje, EstadoDispersion estado, String codigo, String referencia) {

    Map<String, Object> variables() {
      Map<String, Object> variables = new LinkedHashMap<>();
      variables.put("bankResponseCode", codigo);
      if (referencia != null) {
        variables.put("bankReference", referencia);
      }
      return variables;
    }
  }

  /**
   * Registra la dispersión que envía el motor. Es idempotente: si el motor reenvía el mismo lote
   * (por ejemplo, porque reintentó el HTTP Connector) se devuelve la dispersión que ya existía.
   */
  public Dispersion recibir(NuevaDispersion nueva) {
    // El candado se toma fuera de la transacción y se suelta después del commit. Si se soltara
    // antes (synchronized y @Transactional en el mismo método), otra petición con el mismo lote
    // podría entrar sin ver todavía la dispersión recién guardada y registrarla dos veces.
    synchronized (candado) {
      return transaccion.execute(estado -> buscarORegistrar(nueva));
    }
  }

  private Dispersion buscarORegistrar(NuevaDispersion nueva) {
    List<Dispersion> delProceso = repository.findByBusinessKeyOrderByIdDesc(nueva.businessKey());
    for (Dispersion existente : delProceso) {
      if (esElMismoLote(existente, nueva)) {
        return existente;
      }
    }
    // Si el proceso envía un lote distinto, la dispersión anterior sin responder ya no aplica.
    for (Dispersion anterior : delProceso) {
      if (anterior.getEstado() == EstadoDispersion.RECIBIDA) {
        anterior.setEstado(EstadoDispersion.REEMPLAZADA);
      }
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

  private static boolean esElMismoLote(Dispersion existente, NuevaDispersion nueva) {
    return ESTADOS_VIGENTES.contains(existente.getEstado())
        && Objects.equals(existente.getPaymentBatchId(), nueva.paymentBatchId())
        && Objects.equals(existente.getBankAccount(), nueva.bankAccount())
        && Objects.equals(existente.getPayrollTotal(), nueva.payrollTotal())
        && Objects.equals(existente.getPayPeriodId(), nueva.payPeriodId());
  }

  /**
   * Responde al proceso que el banco aceptó la dispersión.
   *
   * @param referencia referencia bancaria; si viene vacía se genera una con el id de la dispersión
   */
  public ResultadoRespuesta aceptar(Dispersion d, String referencia) {
    String ref = tieneTexto(referencia) ? referencia : "BANCO-" + d.getId();
    return responder(d, new RespuestaBanco("DispersionAceptada", EstadoDispersion.ACEPTADA, CODIGO_APROBADA, ref));
  }

  /**
   * Responde al proceso que el banco rechazó la dispersión.
   *
   * @param codigo código de rechazo; si viene vacío se usa el de cuenta inválida
   */
  public ResultadoRespuesta rechazar(Dispersion d, String codigo) {
    String cod = tieneTexto(codigo) ? codigo : CODIGO_CUENTA_INVALIDA;
    return responder(d, new RespuestaBanco("DispersionRechazada", EstadoDispersion.RECHAZADA, cod, null));
  }

  private ResultadoRespuesta responder(Dispersion d, RespuestaBanco respuesta) {
    ResultadoMensaje resultado = motor.enviarMensaje(respuesta.mensaje(), d.getBusinessKey(), respuesta.variables());
    return switch (resultado) {
      case ENTREGADO -> registrarEntrega(d, respuesta);
      case NO_ESPERADO -> reintentarODescartar(d);
      case MOTOR_NO_DISPONIBLE -> anotarIntentoFallido(d, ResultadoRespuesta.MOTOR_NO_DISPONIBLE);
    };
  }

  private ResultadoRespuesta registrarEntrega(Dispersion d, RespuestaBanco respuesta) {
    // El motor ya entregó la respuesta al proceso: se registra sin mirar el estado actual.
    actualizar(d, actual -> {
      actual.setEstado(respuesta.estado());
      actual.setCodigoRespuesta(respuesta.codigo());
      actual.setReferenciaBancaria(respuesta.referencia());
      actual.setRespondidaEn(Instant.now());
    });
    LOG.info("Dispersión {} {} ({})", d.getId(), respuesta.estado(), respuesta.codigo());
    return ResultadoRespuesta.ENTREGADA;
  }

  /** Ningún proceso espera el mensaje: se sigue intentando solo si el proceso todavía existe. */
  private ResultadoRespuesta reintentarODescartar(Dispersion d) {
    boolean procesoActivo;
    try {
      procesoActivo = motor.existeInstanciaActiva(d.getBusinessKey());
    } catch (MotorNoDisponibleException e) {
      // Sin saber si el proceso existe no se puede descartar la dispersión.
      return anotarIntentoFallido(d, ResultadoRespuesta.MOTOR_NO_DISPONIBLE);
    }
    if (procesoActivo) {
      return anotarIntentoFallido(d, ResultadoRespuesta.PROCESO_NO_ESPERA);
    }
    actualizar(d, actual -> {
      actual.setIntentosRespuesta(actual.getIntentosRespuesta() + 1);
      if (actual.getEstado() == EstadoDispersion.RECIBIDA) {
        actual.setEstado(EstadoDispersion.SIN_PROCESO);
      }
    });
    LOG.warn("Dispersión {}: ya no hay un proceso activo con la clave {}; no se reintenta más",
        d.getId(), d.getBusinessKey());
    return ResultadoRespuesta.SIN_PROCESO;
  }

  private ResultadoRespuesta anotarIntentoFallido(Dispersion d, ResultadoRespuesta motivo) {
    actualizar(d, actual -> {
      actual.setIntentosRespuesta(actual.getIntentosRespuesta() + 1);
      if (actual.getIntentosRespuesta() % INTENTOS_ENTRE_AVISOS == 0) {
        LOG.warn("Dispersión {}: {} intentos sin poder entregar la respuesta al proceso {} ({})",
            actual.getId(), actual.getIntentosRespuesta(), actual.getBusinessKey(), motivo);
      }
    });
    return motivo;
  }

  /**
   * Aplica el cambio sobre la dispersión tal como está ahora en la base de datos y no sobre la
   * copia de quien llama: mientras se hablaba con el motor pudo llegar un lote nuevo (y esta
   * quedar REEMPLAZADA) o responderse por otro camino, y guardar la copia pisaría ese estado.
   */
  private void actualizar(Dispersion copia, Consumer<Dispersion> cambio) {
    synchronized (candado) {
      transaccion.executeWithoutResult(estado -> repository.findById(copia.getId()).ifPresent(cambio));
    }
  }

  /** Respuesta automática del banco: rechaza las cuentas con formato inválido y acepta el resto. */
  @Scheduled(fixedDelayString = "${corocora.simulacion.intervalo-ms:2000}")
  public void responderAutomaticamente() {
    if (!simulacion.isRespuestaAutomatica()) {
      return;
    }
    Instant limite = Instant.now().minusSeconds(simulacion.getRetardoSegundos());
    for (Dispersion d : repository.findByEstadoAndRecibidaEnBefore(EstadoDispersion.RECIBIDA, limite)) {
      try {
        responderSegunLaCuenta(d);
      } catch (RuntimeException e) {
        // Un fallo con una dispersión no debe dejar sin respuesta a las demás; sigue RECIBIDA
        // y se vuelve a intentar en el siguiente ciclo.
        LOG.warn("Dispersión {}: no fue posible responder al proceso {}: {}",
            d.getId(), d.getBusinessKey(), e.getMessage());
      }
    }
  }

  private void responderSegunLaCuenta(Dispersion d) {
    boolean cuentaValida = d.getBankAccount() != null && CUENTA_VALIDA.matcher(d.getBankAccount()).matches();
    if (cuentaValida) {
      aceptar(d, null);
    } else {
      rechazar(d, CODIGO_CUENTA_INVALIDA);
    }
  }

  private static boolean tieneTexto(String texto) {
    return texto != null && !texto.isBlank();
  }
}
