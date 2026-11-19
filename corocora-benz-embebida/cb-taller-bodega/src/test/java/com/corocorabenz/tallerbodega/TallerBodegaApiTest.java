package com.corocorabenz.tallerbodega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.corocorabenz.comun.motor.ResultadoMensaje;
import com.corocorabenz.tallerbodega.cotizacion.CotizacionRepository;
import com.corocorabenz.tallerbodega.repuesto.BodegaService;
import com.corocorabenz.tallerbodega.repuesto.Reserva;
import com.corocorabenz.tallerbodega.repuesto.ReservaRepository;
import com.corocorabenz.tallerbodega.repuesto.SolicitudRepuesto;
import com.corocorabenz.tallerbodega.repuesto.SolicitudRepuestoRepository;
import com.corocorabenz.tallerbodega.simulacion.Simulacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API de Taller-Bodega con la base H2 y el motor simulado: credencial interna, consulta por VIN,
 * idempotencia de cotizaciones y reservas, y la regla de reintentos de los avisos al motor.
 */
@SpringBootTest(properties = {
    // El ciclo automático solo corre cuando una prueba lo invoca.
    "corocora.simulacion.respuesta-automatica=false",
    "corocora.simulacion.intervalo-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class TallerBodegaApiTest {

  private static final String VIN_CON_CAMPANA = "CB0TEST0000000001";
  private static final String VIN_SIN_CAMPANA = "WDB0000000000000B";
  private static final String VIN_DESCONOCIDO = "WDB0000000000000Z";

  @Autowired
  private MockMvc mvc;
  @Autowired
  private ObjectMapper json;
  @Autowired
  private CredencialInterna credencial;
  @Autowired
  private CotizacionRepository cotizaciones;
  @Autowired
  private ReservaRepository reservas;
  @Autowired
  private SolicitudRepuestoRepository solicitudes;
  @Autowired
  private Simulacion simulacion;
  @Autowired
  private BodegaService bodega;

  @MockitoBean
  private MotorClient motor;

  @BeforeEach
  void estadoInicial() {
    cotizaciones.deleteAll();
    reservas.deleteAll();
    solicitudes.deleteAll();
    simulacion.setRespuestaAutomatica(false);
    simulacion.setRetardoSegundos(10);
    simulacion.setRepuestosDisponibles(true);
  }

  // ---------------------------------------------------------------- credencial interna

  @Test
  void sinCredencialTodaLaApiRespondeNoAutorizado() throws Exception {
    mvc.perform(get("/api/vehiculos/X").param("km", "10"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Credencial interna requerida"));
    mvc.perform(get("/api/reservas")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/estado")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/simulacion")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/cotizaciones").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/reservas/1/disponibles")).andExpect(status().isUnauthorized());
    verifyNoInteractions(motor);
  }

  @Test
  void credencialEquivocadaNoPasaYLaCorrectaTambienSeAceptaComoBearer() throws Exception {
    mvc.perform(get("/api/estado").header(CredencialInterna.HEADER, "otra-credencial"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/estado").header(HttpHeaders.AUTHORIZATION, "Bearer " + credencial.token()))
        .andExpect(status().isOk());
    mvc.perform(interna(get("/api/estado")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sistema").value("CB-Taller-Bodega"))
        .andExpect(jsonPath("$.estado").value("OK"));
  }

  // ---------------------------------------------------------------- vehículos

  @Test
  void vinConocidoDevuelveHistorialGarantiaYCampana() throws Exception {
    mvc.perform(interna(get("/api/vehiculos/" + VIN_CON_CAMPANA).param("km", "15000")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vin").value(VIN_CON_CAMPANA))
        .andExpect(jsonPath("$.registrado").value(true))
        .andExpect(jsonPath("$.modelo").value("Clase C 200"))
        .andExpect(jsonPath("$.historyRecordId").value("HIST-" + VIN_CON_CAMPANA))
        .andExpect(jsonPath("$.warrantyEligible").value(true))
        .andExpect(jsonPath("$.campaignActive").value(true))
        .andExpect(jsonPath("$.campaignId").value("RECALL-CAMARA-2026"));
  }

  @Test
  void vinConocidoPierdeLaGarantiaAlSuperarElKilometraje() throws Exception {
    mvc.perform(interna(get("/api/vehiculos/" + VIN_SIN_CAMPANA).param("km", "60001")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrado").value(true))
        .andExpect(jsonPath("$.warrantyEligible").value(false))
        .andExpect(jsonPath("$.campaignActive").value(false))
        .andExpect(jsonPath("$.campaignId").doesNotExist());
  }

  @Test
  void vinDesconocidoNoEstaRegistradoNiTieneGarantia() throws Exception {
    mvc.perform(interna(get("/api/vehiculos/" + VIN_DESCONOCIDO).param("km", "0")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vin").value(VIN_DESCONOCIDO))
        .andExpect(jsonPath("$.registrado").value(false))
        .andExpect(jsonPath("$.warrantyEligible").value(false))
        .andExpect(jsonPath("$.campaignActive").value(false));
  }

  @Test
  void vinMalFormadoOKilometrajeNegativoSonSolicitudIncorrecta() throws Exception {
    mvc.perform(interna(get("/api/vehiculos/X").param("km", "10")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VIN o kilometraje inválido"));
    mvc.perform(interna(get("/api/vehiculos/" + VIN_SIN_CAMPANA).param("km", "-1")))
        .andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------- cotizaciones

  @Test
  void cotizacionIncompletaSeRechazaConMensajeEnEspanol() throws Exception {
    mvc.perform(interna(post("/api/cotizaciones")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("businessKey es obligatoria"));
    String vinCorto = cotizacion("PV-COT-0", "ABC", "Ruido");
    mvc.perform(interna(post("/api/cotizaciones")).contentType(MediaType.APPLICATION_JSON).content(vinCorto))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("vin debe tener 17 letras mayúsculas o dígitos, sin I, O ni Q"));
    assertThat(cotizaciones.count()).isZero();
  }

  @Test
  void laMismaCotizacionNoSeDuplicaYUnaDistintaConLaMismaClaveEsConflicto() throws Exception {
    String cuerpo = cotizacion("PV-COT-1", VIN_SIN_CAMPANA, "Ruido");
    JsonNode primera = leer(mvc.perform(interna(post("/api/cotizaciones"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.quoteAmount").value(850000)));
    assertThat(primera.path("quoteId").asText()).startsWith("COT-");

    mvc.perform(interna(post("/api/cotizaciones")).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quoteId").value(primera.path("quoteId").asText()))
        .andExpect(jsonPath("$.quoteAmount").value(850000));

    String otroSintoma = cotizacion("PV-COT-1", VIN_SIN_CAMPANA, "Otro");
    mvc.perform(interna(post("/api/cotizaciones")).contentType(MediaType.APPLICATION_JSON).content(otroSintoma))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("La solicitud ya tiene otra cotización"));
    assertThat(cotizaciones.count()).isEqualTo(1);
  }

  @Test
  void listarCotizacionesReflejaLaRespuestaDelClienteConsultandoElMotorConSuCliente() throws Exception {
    crearCotizacion("PV-COT-2");
    when(motor.get(eq("/history/process-instance"), anyMap())).thenReturn(json.readTree("[{\"id\":\"pi-1\"}]"));
    when(motor.get(eq("/history/activity-instance"), anyMap()))
        .thenReturn(json.readTree("[{\"activityId\":\"sendQuote\"},{\"activityId\":\"quoteAccepted\"}]"));

    mvc.perform(interna(get("/api/cotizaciones").param("businessKey", "PV-COT-2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].businessKey").value("PV-COT-2"))
        .andExpect(jsonPath("$[0].estado").value("ACEPTADA"));

    verify(motor).get(eq("/history/process-instance"), argThat(filtro ->
        "PV-COT-2".equals(filtro.get("processInstanceBusinessKey"))
            && "CB_PostventaGarantias".equals(filtro.get("processDefinitionKey"))));
    verify(motor).get(eq("/history/activity-instance"), argThat(filtro ->
        "pi-1".equals(filtro.get("processInstanceId")) && Boolean.TRUE.equals(filtro.get("finished"))));

    // El estado quedó guardado: una cotización ya respondida no vuelve a consultar el motor.
    mvc.perform(interna(get("/api/cotizaciones")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].estado").value("ACEPTADA"));
    verify(motor, times(1)).get(eq("/history/process-instance"), anyMap());
  }

  @Test
  void cotizacionSigueEnviadaMientrasElClienteNoResponde() throws Exception {
    crearCotizacion("PV-COT-3");
    when(motor.get(eq("/history/process-instance"), anyMap())).thenReturn(json.readTree("[{\"id\":\"pi-3\"}]"));
    when(motor.get(eq("/history/activity-instance"), anyMap()))
        .thenReturn(json.readTree("[{\"activityId\":\"sendQuote\"}]"));

    mvc.perform(interna(get("/api/cotizaciones").param("businessKey", "PV-COT-3")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].estado").value("ENVIADA"));
  }

  @Test
  void siElMotorNoRespondeOFallaLasCotizacionesSeListanConSuUltimoEstado() throws Exception {
    crearCotizacion("PV-COT-4");
    crearCotizacion("PV-COT-5");
    when(motor.get(eq("/history/process-instance"), anyMap()))
        .thenThrow(new MotorNoDisponibleException("sin conexión", null));

    mvc.perform(interna(get("/api/cotizaciones")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].estado").value("ENVIADA"))
        .andExpect(jsonPath("$[1].estado").value("ENVIADA"));
    // Tras el primer fallo de conexión no se insiste con las demás cotizaciones.
    verify(motor, times(1)).get(eq("/history/process-instance"), anyMap());

    when(motor.get(eq("/history/process-instance"), anyMap())).thenThrow(new MotorException(401, "{}"));
    mvc.perform(interna(get("/api/cotizaciones")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].estado").value("ENVIADA"));
  }

  // ---------------------------------------------------------------- reservas

  @Test
  void conRepuestosDisponiblesLaReservaQuedaReservada() throws Exception {
    mvc.perform(interna(post("/api/reservas"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(reserva("PV-RES-1", VIN_CON_CAMPANA)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.reservationId").isNumber())
        .andExpect(jsonPath("$.partsReservationStatus").value("RESERVED"));
    mvc.perform(interna(post("/api/reservas")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("businessKey es obligatoria"));
  }

  @Test
  void sinRepuestosLaReservaQuedaPendienteYElReintentoDevuelveLaMisma() throws Exception {
    mvc.perform(interna(put("/api/simulacion"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"repuestosDisponibles\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repuestosDisponibles").value(false));

    String cuerpo = reserva("PV-RES-2", VIN_CON_CAMPANA);
    JsonNode primera = leer(mvc.perform(interna(post("/api/reservas"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.partsReservationStatus").value("PENDIENTE")));

    mvc.perform(interna(post("/api/reservas")).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reservationId").value(primera.path("reservationId").asLong()))
        .andExpect(jsonPath("$.partsReservationStatus").value("PENDIENTE"));

    mvc.perform(interna(post("/api/reservas"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(reserva("PV-RES-2", VIN_SIN_CAMPANA)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("La solicitud ya tiene una reserva de otro vehículo"));

    assertThat(reservas.count()).isEqualTo(1);
    mvc.perform(interna(get("/api/reservas")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].businessKey").value("PV-RES-2"))
        .andExpect(jsonPath("$[0].estado").value("PENDIENTE"));
    verifyNoInteractions(motor);
  }

  @Test
  void avisoDeDisponiblesEsConflictoMientrasElProcesoNoLoEsperaYLuegoSeEntrega() throws Exception {
    long id = crearReservaPendiente("PV-RES-3");
    when(motor.enviarMensaje(eq("RepuestosDisponibles"), eq("PV-RES-3"), anyMap()))
        .thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva("PV-RES-3")).thenReturn(true);

    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("El proceso todavía no espera el aviso de Bodega"));
    assertThat(estadoDeLaReserva(id)).isEqualTo(Reserva.PENDIENTE);

    when(motor.enviarMensaje(eq("RepuestosDisponibles"), eq("PV-RES-3"), anyMap()))
        .thenReturn(ResultadoMensaje.ENTREGADO);
    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.businessKey").value("PV-RES-3"))
        .andExpect(jsonPath("$.estado").value("RESERVED"));
    verify(motor, times(2)).enviarMensaje(
        "RepuestosDisponibles", "PV-RES-3", Map.of("partsReservationStatus", "RESERVED"));

    // El aviso repetido es idempotente: responde igual y no vuelve a enviar el mensaje.
    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("RESERVED"));
    verify(motor, times(2)).enviarMensaje(anyString(), anyString(), anyMap());
  }

  @Test
  void avisoDeDisponiblesSinInstanciaActivaDejaLaReservaSinProceso() throws Exception {
    long id = crearReservaPendiente("PV-RES-4");
    when(motor.enviarMensaje(anyString(), eq("PV-RES-4"), anyMap())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva("PV-RES-4")).thenReturn(false);

    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("El proceso PV-RES-4 ya no existe; la reserva quedó sin proceso"));
    assertThat(estadoDeLaReserva(id)).isEqualTo(Reserva.SIN_PROCESO);

    // Ya no se reintenta: el siguiente aviso ni siquiera llega al motor.
    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("La reserva está SIN_PROCESO y ya no admite el aviso"));
    verify(motor, times(1)).enviarMensaje(anyString(), anyString(), anyMap());

    // Un proceso nuevo con la misma clave recibe una reserva nueva, no la que quedó sin proceso.
    simulacion.setRepuestosDisponibles(true);
    mvc.perform(interna(post("/api/reservas"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(reserva("PV-RES-4", VIN_CON_CAMPANA)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.partsReservationStatus").value("RESERVED"));
  }

  @Test
  void avisoDeDisponiblesConElMotorCaidoRespondeErrorDePasarelaYSigueReintentable() throws Exception {
    long id = crearReservaPendiente("PV-RES-5");
    when(motor.enviarMensaje(anyString(), eq("PV-RES-5"), anyMap()))
        .thenReturn(ResultadoMensaje.MOTOR_NO_DISPONIBLE);
    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor no responde"));
    assertThat(estadoDeLaReserva(id)).isEqualTo(Reserva.PENDIENTE);

    // El motor se cae justo entre el mensaje y la consulta de la instancia.
    when(motor.enviarMensaje(anyString(), eq("PV-RES-5"), anyMap())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva("PV-RES-5")).thenThrow(new MotorNoDisponibleException("sin conexión", null));
    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isBadGateway());
    assertThat(estadoDeLaReserva(id)).isEqualTo(Reserva.PENDIENTE);

    // Un rechazo que no es de negocio (credencial mal configurada) tampoco cambia la reserva.
    when(motor.enviarMensaje(anyString(), eq("PV-RES-5"), anyMap())).thenThrow(new MotorException(401, "{}"));
    mvc.perform(interna(post("/api/reservas/" + id + "/disponibles")))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor rechazó la operación"));
    assertThat(estadoDeLaReserva(id)).isEqualTo(Reserva.PENDIENTE);
  }

  @Test
  void avisoDeUnaReservaQueNoExisteNoSeEncuentra() throws Exception {
    mvc.perform(interna(post("/api/reservas/999999/disponibles")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("La reserva 999999 no existe"));
    verifyNoInteractions(motor);
  }

  // ---------------------------------------------------------------- solicitudes de repuesto

  @Test
  void solicitudDeRepuestoSeRegistraYSeConsultaPorClave() throws Exception {
    mvc.perform(interna(post("/api/solicitudes-repuesto"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessKey\":\"PV-SOL-1\",\"vin\":\"" + VIN_CON_CAMPANA + "\",\"partCode\":\"CAM-123\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.partRequestId").value(startsWith("REQ-")))
        .andExpect(jsonPath("$.estado").value("SOLICITADA"));
    mvc.perform(interna(post("/api/solicitudes-repuesto"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessKey\":\"PV-SOL-OTRA\",\"vin\":\"" + VIN_CON_CAMPANA + "\"}"))
        .andExpect(status().isCreated());

    mvc.perform(interna(get("/api/solicitudes-repuesto").param("businessKey", "PV-SOL-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].partCode").value("CAM-123"))
        .andExpect(jsonPath("$[0].estado").value("SOLICITADA"));
    mvc.perform(interna(get("/api/solicitudes-repuesto")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].businessKey").value("PV-SOL-OTRA"))
        .andExpect(jsonPath("$[0].partCode").value("SIN-CODIGO"));
  }

  @Test
  void solicitudDeRepuestoSinClaveDelProcesoSeRechaza() throws Exception {
    mvc.perform(interna(post("/api/solicitudes-repuesto"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"vin\":\"" + VIN_CON_CAMPANA + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("businessKey es obligatoria"));
    assertThat(solicitudes.count()).isZero();
  }

  @Test
  void entregaManualEsConflictoMientrasElProcesoNoEsperaElRepuestoYLuegoSeEntrega() throws Exception {
    long id = crearSolicitud("PV-SOL-2");
    when(motor.enviarMensaje(eq("RepuestoRecibido"), eq("PV-SOL-2"), anyMap()))
        .thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva("PV-SOL-2")).thenReturn(true);

    mvc.perform(interna(post("/api/solicitudes-repuesto/" + id + "/entregar")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("El proceso PV-SOL-2 todavía no espera el repuesto"));
    SolicitudRepuesto pendiente = solicitudes.findById(id).orElseThrow();
    assertThat(pendiente.getEstado()).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
    assertThat(pendiente.getIntentosRespuesta()).isEqualTo(1);

    when(motor.enviarMensaje(eq("RepuestoRecibido"), eq("PV-SOL-2"), anyMap()))
        .thenReturn(ResultadoMensaje.ENTREGADO);
    mvc.perform(interna(post("/api/solicitudes-repuesto/" + id + "/entregar")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.partRequestId").value("REQ-" + id))
        .andExpect(jsonPath("$.partCode").value("CAM-123"))
        .andExpect(jsonPath("$.estado").value("ENTREGADA"))
        .andExpect(jsonPath("$.remision").value("REM-" + id));
    verify(motor, times(2)).enviarMensaje("RepuestoRecibido", "PV-SOL-2", Map.of("partReceivedRef", "REM-" + id));

    mvc.perform(interna(post("/api/solicitudes-repuesto/" + id + "/entregar")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("La solicitud ya está ENTREGADA"));
    verify(motor, times(2)).enviarMensaje(anyString(), anyString(), anyMap());

    mvc.perform(interna(post("/api/solicitudes-repuesto/999999/entregar")))
        .andExpect(status().isNotFound());
  }

  @Test
  void entregaManualSinInstanciaActivaDejaLaSolicitudSinProceso() throws Exception {
    long id = crearSolicitud("PV-SOL-3");
    when(motor.enviarMensaje(anyString(), eq("PV-SOL-3"), anyMap())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva("PV-SOL-3")).thenReturn(false);

    mvc.perform(interna(post("/api/solicitudes-repuesto/" + id + "/entregar")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("El proceso PV-SOL-3 ya no existe; la solicitud quedó sin proceso"));
    assertThat(estadoDeLaSolicitud(id)).isEqualTo(SolicitudRepuesto.Estado.SIN_PROCESO);
  }

  @Test
  void entregaManualConElMotorCaidoRespondeErrorDePasarela() throws Exception {
    long id = crearSolicitud("PV-SOL-4");
    when(motor.enviarMensaje(anyString(), eq("PV-SOL-4"), anyMap()))
        .thenReturn(ResultadoMensaje.MOTOR_NO_DISPONIBLE);

    mvc.perform(interna(post("/api/solicitudes-repuesto/" + id + "/entregar")))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor no responde"));
    assertThat(estadoDeLaSolicitud(id)).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
  }

  // ---------------------------------------------------------------- ciclo automático

  @Test
  void elCicloAutomaticoNoHaceNadaSiLaRespuestaAutomaticaEstaApagadaOAunNoPasaElRetardo() throws Exception {
    long id = crearSolicitud("PV-AUT-0");

    bodega.entregarAutomaticamente();

    simulacion.setRespuestaAutomatica(true);
    simulacion.setRetardoSegundos(3600);
    bodega.entregarAutomaticamente();

    verifyNoInteractions(motor);
    assertThat(estadoDeLaSolicitud(id)).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
  }

  @Test
  void elCicloAutomaticoReintentaMientrasHayaInstanciaYEntregaCuandoElProcesoEspera() throws Exception {
    long id = crearSolicitud("PV-AUT-1");
    activarEntregaInmediata();
    when(motor.enviarMensaje(anyString(), eq("PV-AUT-1"), anyMap())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva("PV-AUT-1")).thenReturn(true);

    bodega.entregarAutomaticamente();
    bodega.entregarAutomaticamente();
    SolicitudRepuesto pendiente = solicitudes.findById(id).orElseThrow();
    assertThat(pendiente.getEstado()).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
    assertThat(pendiente.getIntentosRespuesta()).isEqualTo(2);

    when(motor.enviarMensaje(anyString(), eq("PV-AUT-1"), anyMap())).thenReturn(ResultadoMensaje.ENTREGADO);
    bodega.entregarAutomaticamente();
    SolicitudRepuesto entregada = solicitudes.findById(id).orElseThrow();
    assertThat(entregada.getEstado()).isEqualTo(SolicitudRepuesto.Estado.ENTREGADA);
    assertThat(entregada.getRemision()).isEqualTo("REM-" + id);
    assertThat(entregada.getEntregadaEn()).isNotNull();

    // Entregada ya no entra en el ciclo.
    bodega.entregarAutomaticamente();
    verify(motor, times(3)).enviarMensaje("RepuestoRecibido", "PV-AUT-1", Map.of("partReceivedRef", "REM-" + id));
  }

  @Test
  void elCicloAutomaticoDejaSinProcesoLaSolicitudCuyaInstanciaYaNoExisteYNoLaReintenta() throws Exception {
    long id = crearSolicitud("PV-AUT-2");
    activarEntregaInmediata();
    when(motor.enviarMensaje(anyString(), eq("PV-AUT-2"), anyMap())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva("PV-AUT-2")).thenReturn(false);

    bodega.entregarAutomaticamente();
    bodega.entregarAutomaticamente();

    assertThat(estadoDeLaSolicitud(id)).isEqualTo(SolicitudRepuesto.Estado.SIN_PROCESO);
    verify(motor, times(1)).enviarMensaje(anyString(), anyString(), anyMap());
  }

  @Test
  void elCicloAutomaticoReintentaSiElMotorNoRespondeYNoInsisteConLasDemasEnEseCiclo() throws Exception {
    long primera = crearSolicitud("PV-AUT-3");
    long segunda = crearSolicitud("PV-AUT-4");
    activarEntregaInmediata();
    when(motor.enviarMensaje(anyString(), anyString(), anyMap())).thenReturn(ResultadoMensaje.MOTOR_NO_DISPONIBLE);

    bodega.entregarAutomaticamente();

    assertThat(estadoDeLaSolicitud(primera)).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
    assertThat(estadoDeLaSolicitud(segunda)).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
    verify(motor, times(1)).enviarMensaje(anyString(), anyString(), anyMap());
    verify(motor, never()).existeInstanciaActiva(any());

    // También se reintenta si el motor cae entre el mensaje y la consulta de la instancia.
    when(motor.enviarMensaje(anyString(), anyString(), anyMap())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva(anyString())).thenThrow(new MotorNoDisponibleException("sin conexión", null));
    bodega.entregarAutomaticamente();
    assertThat(estadoDeLaSolicitud(primera)).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
    assertThat(estadoDeLaSolicitud(segunda)).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
  }

  @Test
  void unRechazoDelMotorParaUnaSolicitudNoImpideEntregarLasDemas() throws Exception {
    long rechazada = crearSolicitud("PV-AUT-5");
    long aceptada = crearSolicitud("PV-AUT-6");
    activarEntregaInmediata();
    when(motor.enviarMensaje(anyString(), eq("PV-AUT-5"), anyMap())).thenThrow(new MotorException(500, "{}"));
    when(motor.enviarMensaje(anyString(), eq("PV-AUT-6"), anyMap())).thenReturn(ResultadoMensaje.ENTREGADO);

    bodega.entregarAutomaticamente();

    assertThat(estadoDeLaSolicitud(rechazada)).isEqualTo(SolicitudRepuesto.Estado.SOLICITADA);
    assertThat(estadoDeLaSolicitud(aceptada)).isEqualTo(SolicitudRepuesto.Estado.ENTREGADA);
  }

  // ---------------------------------------------------------------- simulación

  @Test
  void laSimulacionSeConsultaYSeCambiaPorCampos() throws Exception {
    mvc.perform(interna(get("/api/simulacion")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.respuestaAutomatica").value(false))
        .andExpect(jsonPath("$.retardoSegundos").value(10))
        .andExpect(jsonPath("$.repuestosDisponibles").value(true));

    mvc.perform(interna(put("/api/simulacion"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"respuestaAutomatica\":true,\"retardoSegundos\":-5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.respuestaAutomatica").value(true))
        .andExpect(jsonPath("$.retardoSegundos").value(0))
        .andExpect(jsonPath("$.repuestosDisponibles").value(true));
  }

  // ---------------------------------------------------------------- ayudas

  private MockHttpServletRequestBuilder interna(MockHttpServletRequestBuilder peticion) {
    return peticion.header(CredencialInterna.HEADER, credencial.token());
  }

  private static String cotizacion(String businessKey, String vin, String sintoma) {
    return "{\"businessKey\":\"" + businessKey + "\",\"vin\":\"" + vin
        + "\",\"customerName\":\"Prueba\",\"symptom\":\"" + sintoma + "\"}";
  }

  private static String reserva(String businessKey, String vin) {
    return "{\"businessKey\":\"" + businessKey + "\",\"vin\":\"" + vin + "\"}";
  }

  private void crearCotizacion(String businessKey) throws Exception {
    mvc.perform(interna(post("/api/cotizaciones"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cotizacion(businessKey, VIN_SIN_CAMPANA, "Ruido")))
        .andExpect(status().isCreated());
  }

  private long crearReservaPendiente(String businessKey) throws Exception {
    simulacion.setRepuestosDisponibles(false);
    JsonNode creada = leer(mvc.perform(interna(post("/api/reservas"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(reserva(businessKey, VIN_CON_CAMPANA)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.partsReservationStatus").value("PENDIENTE")));
    return creada.path("reservationId").asLong();
  }

  private long crearSolicitud(String businessKey) throws Exception {
    JsonNode creada = leer(mvc.perform(interna(post("/api/solicitudes-repuesto"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessKey\":\"" + businessKey + "\",\"vin\":\"" + VIN_CON_CAMPANA
                + "\",\"partCode\":\"CAM-123\"}"))
        .andExpect(status().isCreated()));
    String numero = creada.path("partRequestId").asText().substring("REQ-".length());
    return Long.parseLong(numero);
  }

  private void activarEntregaInmediata() {
    simulacion.setRespuestaAutomatica(true);
    simulacion.setRetardoSegundos(0);
  }

  private String estadoDeLaReserva(long id) {
    return reservas.findById(id).orElseThrow().getEstado();
  }

  private SolicitudRepuesto.Estado estadoDeLaSolicitud(long id) {
    return solicitudes.findById(id).orElseThrow().getEstado();
  }

  private JsonNode leer(ResultActions resultado) throws Exception {
    return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
