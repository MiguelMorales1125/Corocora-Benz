package com.corocorabenz.centralsys.nomina;

import com.corocorabenz.centralsys.actividad.Accion;
import com.corocorabenz.centralsys.actividad.RegistroActividad;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.portal.Permisos;
import com.corocorabenz.comun.portal.UsuarioPortal;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Inicio del ciclo de nómina por un analista (sección 5.3 del contrato). */
@RestController
public class NominaController {

  static final String GRUPO_ANALISTAS = "analistas-nomina";

  private static final String PROCESO = "CB_GestionNomina";
  private static final Pattern PERIODO = Pattern.compile("[A-Za-z0-9-]{1,40}");
  private static final Pattern FECHA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Set<String> CICLOS = Set.of("QUINCENAL", "MENSUAL");
  private static final int LARGO_SUFIJO = 6;

  private final MotorClient motor;
  private final RegistroActividad registro;

  public NominaController(MotorClient motor, RegistroActividad registro) {
    this.motor = motor;
    this.registro = registro;
  }

  /**
   * Las tres banderas son opcionales y solo sirven para la demostración: hacen que el proceso
   * tome una ruta de excepción (las lee ObtenerNovedadesDelegate en el motor).
   */
  public record NuevaNomina(
      String payPeriodId,
      String payCycle,
      String cutoffDate,
      Boolean simulatePeriodClosed,
      Boolean simulateMissingSupports,
      Boolean simulateLateChanges) {
  }

  public record NominaIniciada(String businessKey, String id) {
  }

  @PostMapping("/api/app/central/nomina")
  public NominaIniciada iniciar(
      @RequestBody(required = false) NuevaNomina nomina, @AuthenticationPrincipal UsuarioPortal usuario) {
    // Primero el permiso y después los datos: quien no puede iniciar la nómina recibe 403
    // aunque envíe datos incorrectos. Por eso la validación no es con @Valid.
    Permisos.exigirEmpleado(usuario);
    Permisos.exigirGrupo(usuario, GRUPO_ANALISTAS);
    validar(nomina);

    String businessKey = "NOM-" + nomina.payPeriodId() + "-" + sufijo();
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("payPeriodId", nomina.payPeriodId());
    variables.put("payCycle", nomina.payCycle());
    variables.put("cutoffDate", nomina.cutoffDate());
    agregarSimulacion(variables, "simulatePeriodClosed", nomina.simulatePeriodClosed());
    agregarSimulacion(variables, "simulateMissingSupports", nomina.simulateMissingSupports());
    agregarSimulacion(variables, "simulateLateChanges", nomina.simulateLateChanges());

    Map<String, Object> cuerpo = new LinkedHashMap<>();
    cuerpo.put("businessKey", businessKey);
    cuerpo.put("variables", MotorClient.tipar(variables));
    JsonNode instancia = motor.post("/process-definition/key/" + PROCESO + "/start", cuerpo);

    registro.general(usuario, Accion.INICIAR_NOMINA, businessKey, "Periodo " + nomina.payPeriodId());
    String id = instancia == null ? null : instancia.path("id").asText(null);
    return new NominaIniciada(businessKey, id);
  }

  /** Una bandera de simulación solo viaja al motor cuando viene marcada. */
  private static void agregarSimulacion(Map<String, Object> variables, String nombre, Boolean marcada) {
    if (Boolean.TRUE.equals(marcada)) {
      variables.put(nombre, true);
    }
  }

  private static void validar(NuevaNomina nomina) {
    if (nomina == null) {
      throw invalido("Faltan los datos de la nómina");
    }
    if (nomina.payPeriodId() == null || !PERIODO.matcher(nomina.payPeriodId()).matches()) {
      throw invalido("El periodo debe tener entre 1 y 40 letras, números o guiones");
    }
    if (nomina.payCycle() == null || !CICLOS.contains(nomina.payCycle())) {
      throw invalido("El ciclo debe ser QUINCENAL o MENSUAL");
    }
    if (!esFecha(nomina.cutoffDate())) {
      throw invalido("La fecha de corte debe ser una fecha válida con formato aaaa-mm-dd");
    }
  }

  private static boolean esFecha(String texto) {
    if (texto == null || !FECHA.matcher(texto).matches()) {
      return false;
    }
    try {
      // Además del formato, descarta fechas que no existen, como 2026-02-30.
      LocalDate.parse(texto);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static String sufijo() {
    return UUID.randomUUID()
        .toString()
        .replace("-", "")
        .substring(0, LARGO_SUFIJO)
        .toUpperCase(Locale.ROOT);
  }

  private static ResponseStatusException invalido(String mensaje) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensaje);
  }
}
