package com.corocorabenz.banco;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corocorabenz.banco.dispersion.Dispersion;
import com.corocorabenz.banco.dispersion.DispersionRepository;
import com.corocorabenz.banco.dispersion.DispersionService;
import com.corocorabenz.banco.dispersion.DispersionService.NuevaDispersion;
import com.corocorabenz.banco.dispersion.EstadoDispersion;
import com.corocorabenz.banco.simulacion.Simulacion;
import com.corocorabenz.comun.credencial.CredencialInterna;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.corocorabenz.comun.motor.ResultadoMensaje;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API del banco con el motor simulado. El ciclo automático queda apagado (y con un intervalo de
 * una hora) para que no se cruce con las pruebas: las que lo necesitan lo encienden y llaman
 * directamente al método del ciclo.
 */
@SpringBootTest(properties = {
    "corocora.internal-token=credencial-de-prueba",
    "corocora.simulacion.respuesta-automatica=false",
    "corocora.simulacion.retardo-segundos=0",
    "corocora.simulacion.intervalo-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class DispersionApiTest {

  private static final String CREDENCIAL = "credencial-de-prueba";
  private static final String PROCESO = "NOM-2026-01-ABC123";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper json;

  @Autowired
  private DispersionRepository repository;

  @Autowired
  private DispersionService service;

  @Autowired
  private Simulacion simulacion;

  @MockitoBean
  private MotorClient motor;

  @BeforeEach
  void limpiar() {
    repository.deleteAll();
    simulacion.setRespuestaAutomatica(false);
    simulacion.setRetardoSegundos(0);
  }

  @AfterEach
  void apagarRespuestaAutomatica() {
    simulacion.setRespuestaAutomatica(false);
  }

  // ---------------------------------------------------------------- credencial interna

  @Test
  void sinCredencialRespondeNoAutorizado() throws Exception {
    mvc.perform(get("/api/dispersiones"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Credencial interna requerida"));
    mvc.perform(get("/api/estado"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/simulacion"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/dispersiones")
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo(lote("LOTE-1", "000-123"))))
        .andExpect(status().isUnauthorized());
    assertThat(repository.count()).isZero();
  }

  @Test
  void conCredencialIncorrectaRespondeNoAutorizado() throws Exception {
    mvc.perform(get("/api/dispersiones").header(CredencialInterna.HEADER, "otra-credencial"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Credencial interna requerida"));
  }

  @Test
  void aceptaLaCredencialComoBearer() throws Exception {
    mvc.perform(get("/api/estado").header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENCIAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sistema").value("CB-Banco"))
        .andExpect(jsonPath("$.estado").value("OK"));
  }

  // ---------------------------------------------------------------- recepción

  @Test
  void creaLaDispersionConLaFormaQueEsperaElMotor() throws Exception {
    mvc.perform(interna(post("/api/dispersiones"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo(lote("LOTE-1", "000-123"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.dispersionId").isNumber())
        .andExpect(jsonPath("$.estado").value("RECIBIDA"))
        .andExpect(jsonPath("$.recibida").value(true));

    mvc.perform(interna(get("/api/dispersiones")).param("businessKey", PROCESO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].businessKey").value(PROCESO))
        .andExpect(jsonPath("$[0].paymentBatchId").value("LOTE-1"))
        .andExpect(jsonPath("$[0].bankAccount").value("000-123"))
        .andExpect(jsonPath("$[0].payrollTotal").value(1000000))
        .andExpect(jsonPath("$[0].estado").value("RECIBIDA"));
  }

  @Test
  void sinBusinessKeyRespondeSolicitudIncorrecta() throws Exception {
    Map<String, Object> sinClave = lote("LOTE-1", "000-123");
    sinClave.remove("businessKey");
    mvc.perform(interna(post("/api/dispersiones"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo(sinClave)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Falta businessKey"));
  }

  @Test
  void reenviarElMismoLoteDevuelveLaMismaDispersion() throws Exception {
    long primera = recibir(lote("LOTE-1", "000-123"));
    long segunda = recibir(lote("LOTE-1", "000-123"));

    assertThat(segunda).isEqualTo(primera);
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void unLoteDistintoReemplazaAlAnteriorSinResponder() throws Exception {
    long primera = recibir(lote("LOTE-1", "000"));
    long segunda = recibir(lote("LOTE-1", "000-123"));

    assertThat(segunda).isNotEqualTo(primera);
    assertThat(estadoDe(primera)).isEqualTo(EstadoDispersion.REEMPLAZADA);
    assertThat(estadoDe(segunda)).isEqualTo(EstadoDispersion.RECIBIDA);
  }

  @Test
  void elMismoLoteEnviadoALaVezSeRegistraUnaSolaVez() throws Exception {
    int hilos = 8;
    NuevaDispersion nueva = new NuevaDispersion(PROCESO, "LOTE-1", "000-123", 1000000L, "2026-01");
    CountDownLatch salida = new CountDownLatch(1);
    ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
    try {
      List<Future<Long>> resultados = new ArrayList<>();
      for (int i = 0; i < hilos; i++) {
        Callable<Long> tarea = () -> {
          salida.await();
          return service.recibir(nueva).getId();
        };
        resultados.add(ejecutor.submit(tarea));
      }
      salida.countDown();
      Set<Long> ids = new HashSet<>();
      for (Future<Long> resultado : resultados) {
        ids.add(resultado.get(30, TimeUnit.SECONDS));
      }
      assertThat(ids).hasSize(1);
      assertThat(repository.count()).isEqualTo(1);
    } finally {
      ejecutor.shutdownNow();
    }
  }

  // ---------------------------------------------------------------- respuesta automática

  @Test
  void respuestaAutomaticaSinInstanciaActivaQuedaSinProcesoYNoSeReintenta() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva(PROCESO)).thenReturn(false);
    simulacion.setRespuestaAutomatica(true);

    service.responderAutomaticamente();

    assertThat(estadoDe(id)).isEqualTo(EstadoDispersion.SIN_PROCESO);

    service.responderAutomaticamente();

    verify(motor, times(1)).enviarMensaje(anyString(), anyString(), any());
    assertThat(estadoDe(id)).isEqualTo(EstadoDispersion.SIN_PROCESO);
  }

  @Test
  void respuestaAutomaticaSeReintentaMientrasElProcesoExista() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva(PROCESO)).thenReturn(true);
    simulacion.setRespuestaAutomatica(true);

    service.responderAutomaticamente();
    service.responderAutomaticamente();

    Dispersion pendiente = repository.findById(id).orElseThrow();
    assertThat(pendiente.getEstado()).isEqualTo(EstadoDispersion.RECIBIDA);
    assertThat(pendiente.getIntentosRespuesta()).isEqualTo(2);

    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.ENTREGADO);
    service.responderAutomaticamente();

    Dispersion aceptada = repository.findById(id).orElseThrow();
    assertThat(aceptada.getEstado()).isEqualTo(EstadoDispersion.ACEPTADA);
    assertThat(aceptada.getCodigoRespuesta()).isEqualTo("00-APROBADA");
    assertThat(aceptada.getReferenciaBancaria()).isEqualTo("BANCO-" + id);
    assertThat(aceptada.getRespondidaEn()).isNotNull();
    verify(motor, times(3)).enviarMensaje(
        "DispersionAceptada",
        PROCESO,
        Map.of("bankResponseCode", "00-APROBADA", "bankReference", "BANCO-" + id));
  }

  @Test
  void respuestaAutomaticaSeReintentaSiElMotorNoResponde() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.MOTOR_NO_DISPONIBLE);
    simulacion.setRespuestaAutomatica(true);

    service.responderAutomaticamente();

    assertThat(estadoDe(id)).isEqualTo(EstadoDispersion.RECIBIDA);
    verify(motor, never()).existeInstanciaActiva(anyString());
  }

  @Test
  void noSeDescartaSiElMotorSeCaeAlConsultarLaInstancia() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva(PROCESO)).thenThrow(new MotorNoDisponibleException("sin conexión", null));
    simulacion.setRespuestaAutomatica(true);

    service.responderAutomaticamente();

    assertThat(estadoDe(id)).isEqualTo(EstadoDispersion.RECIBIDA);
  }

  @Test
  void unErrorDelMotorConUnaDispersionNoDetieneLasDemas() throws Exception {
    long conError = recibir(lote("OTRO-PROCESO", "LOTE-9", "000-123"));
    long normal = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), eq("OTRO-PROCESO"), any())).thenThrow(new MotorException(500, "fallo"));
    when(motor.enviarMensaje(anyString(), eq(PROCESO), any())).thenReturn(ResultadoMensaje.ENTREGADO);
    simulacion.setRespuestaAutomatica(true);

    service.responderAutomaticamente();

    assertThat(estadoDe(conError)).isEqualTo(EstadoDispersion.RECIBIDA);
    assertThat(estadoDe(normal)).isEqualTo(EstadoDispersion.ACEPTADA);
  }

  @Test
  void respuestaAutomaticaRechazaLaCuentaConFormatoInvalido() throws Exception {
    long id = recibir(lote("LOTE-1", "cuenta-mala"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.ENTREGADO);
    simulacion.setRespuestaAutomatica(true);

    service.responderAutomaticamente();

    Dispersion rechazada = repository.findById(id).orElseThrow();
    assertThat(rechazada.getEstado()).isEqualTo(EstadoDispersion.RECHAZADA);
    assertThat(rechazada.getCodigoRespuesta()).isEqualTo("R03-CUENTA-INVALIDA");
    verify(motor).enviarMensaje(
        "DispersionRechazada",
        PROCESO,
        Map.of("bankResponseCode", "R03-CUENTA-INVALIDA"));
  }

  @Test
  void conLaRespuestaAutomaticaApagadaNoSeLlamaAlMotor() throws Exception {
    recibir(lote("LOTE-1", "000-123"));

    service.responderAutomaticamente();

    verify(motor, never()).enviarMensaje(anyString(), anyString(), any());
  }

  @Test
  void unIntentoFallidoNoPisaElReemplazoQueLlegoMientrasSeHablabaConElMotor() throws Exception {
    long primera = recibir(lote("LOTE-1", "000"));
    NuevaDispersion corregida = new NuevaDispersion(PROCESO, "LOTE-1", "000-123", 1000000L, "2026-01");
    // El lote corregido llega justo mientras el banco intenta responder el anterior.
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenAnswer(llamada -> {
      service.recibir(corregida);
      return ResultadoMensaje.NO_ESPERADO;
    });
    when(motor.existeInstanciaActiva(PROCESO)).thenReturn(true);
    simulacion.setRespuestaAutomatica(true);

    service.responderAutomaticamente();

    assertThat(estadoDe(primera)).isEqualTo(EstadoDispersion.REEMPLAZADA);
  }

  // ---------------------------------------------------------------- respuesta manual

  @Test
  void aceptarAManoRespondeConflictoMientrasElProcesoNoEspera() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva(PROCESO)).thenReturn(true);

    mvc.perform(interna(post("/api/dispersiones/" + id + "/aceptar")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error")
            .value("El proceso " + PROCESO + " todavía no espera la respuesta del banco"));
    assertThat(estadoDe(id)).isEqualTo(EstadoDispersion.RECIBIDA);

    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.ENTREGADO);

    mvc.perform(interna(post("/api/dispersiones/" + id + "/aceptar")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.estado").value("ACEPTADA"))
        .andExpect(jsonPath("$.paymentBatchId").value("LOTE-1"))
        .andExpect(jsonPath("$.bankAccount").value("000-123"))
        .andExpect(jsonPath("$.payrollTotal").value(1000000))
        .andExpect(jsonPath("$.referenciaBancaria").value("BANCO-" + id));

    mvc.perform(interna(post("/api/dispersiones/" + id + "/aceptar")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("La dispersión ya está ACEPTADA"));
  }

  @Test
  void rechazarAManoEnviaElCodigoIndicado() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.ENTREGADO);

    mvc.perform(interna(post("/api/dispersiones/" + id + "/rechazar"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo(Map.of("codigo", "R01-FONDOS"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("RECHAZADA"))
        .andExpect(jsonPath("$.codigoRespuesta").value("R01-FONDOS"));

    verify(motor).enviarMensaje("DispersionRechazada", PROCESO, Map.of("bankResponseCode", "R01-FONDOS"));
  }

  @Test
  void aceptarAManoSinProcesoActivoDejaLaDispersionSinProceso() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.NO_ESPERADO);
    when(motor.existeInstanciaActiva(PROCESO)).thenReturn(false);

    mvc.perform(interna(post("/api/dispersiones/" + id + "/aceptar")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").exists());

    assertThat(estadoDe(id)).isEqualTo(EstadoDispersion.SIN_PROCESO);
  }

  @Test
  void aceptarAManoConElMotorCaidoRespondePuertaDeEnlaceIncorrecta() throws Exception {
    long id = recibir(lote("LOTE-1", "000-123"));
    when(motor.enviarMensaje(anyString(), anyString(), any())).thenReturn(ResultadoMensaje.MOTOR_NO_DISPONIBLE);

    mvc.perform(interna(post("/api/dispersiones/" + id + "/aceptar")))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor no responde"));
    assertThat(estadoDe(id)).isEqualTo(EstadoDispersion.RECIBIDA);
  }

  @Test
  void responderUnaDispersionQueNoExisteRespondeNoEncontrado() throws Exception {
    mvc.perform(interna(post("/api/dispersiones/999999/aceptar")))
        .andExpect(status().isNotFound());
  }

  // ---------------------------------------------------------------- simulación

  @Test
  void laSimulacionSeConsultaYSeCambia() throws Exception {
    mvc.perform(interna(get("/api/simulacion")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.respuestaAutomatica").value(false))
        .andExpect(jsonPath("$.retardoSegundos").value(0));

    mvc.perform(interna(put("/api/simulacion"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo(Map.of("retardoSegundos", 5))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.respuestaAutomatica").value(false))
        .andExpect(jsonPath("$.retardoSegundos").value(5));
  }

  // ---------------------------------------------------------------- apoyo

  private static MockHttpServletRequestBuilder interna(MockHttpServletRequestBuilder peticion) {
    return peticion.header(CredencialInterna.HEADER, CREDENCIAL);
  }

  private static Map<String, Object> lote(String paymentBatchId, String bankAccount) {
    return lote(PROCESO, paymentBatchId, bankAccount);
  }

  private static Map<String, Object> lote(String businessKey, String paymentBatchId, String bankAccount) {
    Map<String, Object> lote = new LinkedHashMap<>();
    lote.put("businessKey", businessKey);
    lote.put("paymentBatchId", paymentBatchId);
    lote.put("bankAccount", bankAccount);
    lote.put("payrollTotal", 1000000);
    lote.put("payPeriodId", "2026-01");
    return lote;
  }

  private String cuerpo(Object valor) throws Exception {
    return json.writeValueAsString(valor);
  }

  /** Envía el lote como lo hace el motor y devuelve el id de la dispersión. */
  private long recibir(Map<String, Object> lote) throws Exception {
    MvcResult resultado = mvc.perform(interna(post("/api/dispersiones"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo(lote)))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode respuesta = json.readTree(resultado.getResponse().getContentAsString());
    return respuesta.get("dispersionId").asLong();
  }

  private EstadoDispersion estadoDe(long id) {
    return repository.findById(id).orElseThrow().getEstado();
  }
}
