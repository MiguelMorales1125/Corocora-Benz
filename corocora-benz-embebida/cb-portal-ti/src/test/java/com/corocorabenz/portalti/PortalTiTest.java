package com.corocorabenz.portalti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La mesa de ayuda vista desde fuera, con el motor simulado y la base en memoria:
 * sesión y permisos de /api/app/ti (sección 5.2 del contrato) y la API interna de
 * tickets con su credencial (sección 2).
 */
@SpringBootTest(properties = "corocora.internal-token=token-de-prueba")
@ActiveProfiles("h2")
@AutoConfigureMockMvc
class PortalTiTest {

  private static final String TOKEN = "token-de-prueba";
  private static final String CLAVE = "demo";
  private static final String INICIO_DEL_PROCESO = "/process-definition/key/CB_GestionIncidenteTI/start";
  private static final Map<String, List<String>> GRUPOS = Map.of(
      "USR-01", List.of(),
      "mesa1", List.of("mesa-servicio"),
      "cliente1", List.of("clientes"),
      "demo", List.of("camunda-admin"));

  /** Una tarea de usuario tal como la devolvería el motor. */
  private record Tarea(String id, String definicion, String asignada, String instancia, String businessKey) {
  }

  private final ObjectMapper json = new ObjectMapper();
  private final List<Tarea> tareasDelMotor = new ArrayList<>();

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private MotorClient motor;

  @BeforeEach
  void simularMotor() {
    tareasDelMotor.clear();
    when(motor.post(eq("/identity/verify"), any())).thenAnswer(llamada -> {
      Map<?, ?> credenciales = llamada.getArgument(1);
      boolean correcta = GRUPOS.containsKey(credenciales.get("username"))
          && CLAVE.equals(credenciales.get("password"));
      return json.createObjectNode().put("authenticated", correcta);
    });
    when(motor.get(eq("/group"), any())).thenAnswer(llamada -> {
      Map<?, ?> parametros = llamada.getArgument(1);
      ArrayNode grupos = json.createArrayNode();
      for (String grupo : GRUPOS.getOrDefault(parametros.get("member"), List.of())) {
        grupos.addObject().put("id", grupo);
      }
      return grupos;
    });
    when(motor.get(eq("/task"), any())).thenAnswer(llamada -> tareasQueCumplen(llamada.getArgument(1)));
    when(motor.get(eq("/process-instance"), any())).thenAnswer(llamada -> instancias(llamada.getArgument(1)));
  }

  // ------------------------------------------------------- sesión y permisos

  @Test
  void sinSesionLaMesaDeAyudaResponde401() throws Exception {
    mvc.perform(get("/api/app/ti/tickets"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Inicia sesión"));
    mvc.perform(conCsrf(post("/api/app/ti/reports"), new MockHttpSession())
            .contentType(MediaType.APPLICATION_JSON)
            .content(reporte().toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(conCsrf(post("/api/app/ti/tickets/TI-1/confirm"), new MockHttpSession())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\":true}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unClienteRecibe403EnTodaLaMesaDeAyuda() throws Exception {
    MockHttpSession cliente = sesionDe("cliente1");

    mvc.perform(get("/api/app/ti/tickets").session(cliente))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Esta operación es solo para empleados"));
    mvc.perform(conCsrf(post("/api/app/ti/reports"), cliente)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reporte().toString()))
        .andExpect(status().isForbidden());
    // El permiso va antes que la validación: un cuerpo incompleto sigue siendo 403, no 400.
    mvc.perform(conCsrf(post("/api/app/ti/reports"), cliente)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(post("/api/app/ti/tickets/TI-1/confirm"), cliente)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\":true}"))
        .andExpect(status().isForbidden());

    verify(motor, never()).post(eq(INICIO_DEL_PROCESO), any());
  }

  @Test
  void escribirSinCsrfResponde403() throws Exception {
    mvc.perform(post("/api/app/ti/reports")
            .session(sesionDe("USR-01"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(reporte().toString()))
        .andExpect(status().isForbidden());

    verify(motor, never()).post(eq(INICIO_DEL_PROCESO), any());
  }

  // ---------------------------------------------------------------- reportar

  @Test
  void unEmpleadoReportaYElProcesoArrancaASuNombre() throws Exception {
    ObjectNode cuerpo = reporte();
    cuerpo.put("affectedTeams", 3);
    cuerpo.put("safetyImpact", true);
    cuerpo.put("notificationEmail", "usr01@corocorabenz.test");
    // Quien reporta sale de la sesión: lo que venga en el cuerpo se ignora.
    cuerpo.put("requesterId", "otra-persona");

    MvcResult resultado = mvc.perform(conCsrf(post("/api/app/ti/reports"), sesionDe("USR-01"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo.toString()))
        .andExpect(status().isOk())
        .andReturn();
    String businessKey = leer(resultado).path("businessKey").asText();
    assertThat(businessKey).matches("TI-[A-Z0-9]{8}");

    JsonNode enviado = inicioEnviadoAlMotor();
    assertThat(enviado.path("businessKey").asText()).isEqualTo(businessKey);
    JsonNode variables = enviado.path("variables");
    assertThat(variables.path("requesterId").path("value").asText()).isEqualTo("USR-01");
    assertThat(variables.path("systemName").path("value").asText()).isEqualTo("ERP");
    assertThat(variables.path("description").path("value").asText()).isEqualTo("No inicia sesión");
    assertThat(variables.path("impact").path("value").asText()).isEqualTo("Medio");
    assertThat(variables.path("urgency").path("value").asText()).isEqualTo("Alta");
    assertThat(variables.path("affectedTeams").path("value").asLong()).isEqualTo(3);
    assertThat(variables.path("affectedTeams").path("type").asText()).isEqualTo("Long");
    assertThat(variables.path("safetyImpact").path("value").asBoolean()).isTrue();
    assertThat(variables.path("safetyImpact").path("type").asText()).isEqualTo("Boolean");
    assertThat(variables.path("notificationEmail").path("value").asText()).isEqualTo("usr01@corocorabenz.test");
  }

  @Test
  void elCorreoEsOpcionalYVacioNoSeEnviaAlMotor() throws Exception {
    ObjectNode cuerpo = reporte();
    cuerpo.put("notificationEmail", "");

    mvc.perform(conCsrf(post("/api/app/ti/reports"), sesionDe("mesa1"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo.toString()))
        .andExpect(status().isOk());

    JsonNode variables = inicioEnviadoAlMotor().path("variables");
    assertThat(variables.has("notificationEmail")).isFalse();
    assertThat(variables.path("requesterId").path("value").asText()).isEqualTo("mesa1");
  }

  @Test
  void elAdministradorTambienPuedeReportar() throws Exception {
    mvc.perform(conCsrf(post("/api/app/ti/reports"), sesionDe("demo"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(reporte().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessKey").isNotEmpty());
  }

  @Test
  void unReporteQueNoCumpleElContratoResponde400() throws Exception {
    MockHttpSession sesion = sesionDe("USR-01");

    exigir400(sesion, reporte().put("systemName", "SAP"),
        "El sistema afectado debe ser Software del vehículo, ERP, Portal de clientes o Correo");
    exigir400(sesion, reporte().without("systemName"), "El sistema afectado es obligatorio");
    exigir400(sesion, reporte().put("description", "   "), "La descripción es obligatoria");
    exigir400(sesion, reporte().put("description", "x".repeat(4001)), "La descripción admite hasta 4000 caracteres");
    exigir400(sesion, reporte().put("impact", "Crítico"), "El impacto debe ser Bajo, Medio o Alto");
    exigir400(sesion, reporte().put("urgency", "Urgente"), "La urgencia debe ser Baja, Media o Alta");
    exigir400(sesion, reporte().put("affectedTeams", 0),
        "Los equipos afectados deben ser un número entero entre 1 y 999999999");
    exigir400(sesion, reporte().put("affectedTeams", 1.5),
        "Los equipos afectados deben ser un número entero entre 1 y 999999999");
    exigir400(sesion, reporte().without("affectedTeams"), "Los equipos afectados son obligatorios");
    exigir400(sesion, reporte().without("safetyImpact"), "Indica si el incidente afecta la seguridad");
    exigir400(sesion, reporte().put("notificationEmail", "no-es-un-correo"),
        "El correo para notificaciones no tiene un formato válido");

    mvc.perform(conCsrf(post("/api/app/ti/reports"), sesion)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{esto no es json"))
        .andExpect(status().isBadRequest());

    verify(motor, never()).post(eq(INICIO_DEL_PROCESO), any());
  }

  @Test
  void siElMotorFallaElReporteResponde502() throws Exception {
    MockHttpSession sesion = sesionDe("USR-01");

    when(motor.post(eq(INICIO_DEL_PROCESO), any()))
        .thenThrow(new MotorNoDisponibleException("sin conexión", null));
    mvc.perform(conCsrf(post("/api/app/ti/reports"), sesion)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reporte().toString()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor no responde"));

    when(motor.post(eq(INICIO_DEL_PROCESO), any()))
        .thenThrow(new MotorException(500, "{\"message\":\"detalle interno\"}"));
    mvc.perform(conCsrf(post("/api/app/ti/reports"), sesion)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reporte().toString()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor rechazó la operación"));
  }

  // ------------------------------------------------------------- mis tickets

  @Test
  void cadaPersonaVeSoloSusTicketsYSabeCualPuedeConfirmar() throws Exception {
    String porConfirmar = claveNueva();
    String enAtencion = claveNueva();
    String ajeno = claveNueva();
    String idPorConfirmar = crearTicket(porConfirmar, "USR-01");
    crearTicket(enAtencion, "USR-01");
    crearTicket(ajeno, "mesa1");
    registrarSolucion(idPorConfirmar);
    tareasDelMotor.add(new Tarea("t-confirmar", "confirmSolution", "USR-01", "pi-1", porConfirmar));
    // Una confirmación de otra persona no habilita nada para USR-01.
    tareasDelMotor.add(new Tarea("t-ajena", "confirmSolution", "mesa1", "pi-2", ajeno));

    MvcResult resultado = mvc.perform(get("/api/app/ti/tickets").session(sesionDe("USR-01")))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode tickets = leer(resultado);

    assertThat(claves(tickets)).contains(porConfirmar, enAtencion).doesNotContain(ajeno);
    JsonNode resuelto = ticketCon(tickets, porConfirmar);
    assertThat(resuelto.path("ticketId").asText()).isEqualTo(idPorConfirmar);
    assertThat(resuelto.path("systemName").asText()).isEqualTo("ERP");
    assertThat(resuelto.path("description").asText()).isEqualTo("No inicia sesión");
    assertThat(resuelto.path("status").asText()).isEqualTo("RESOLVED");
    assertThat(resuelto.path("rootCause").asText()).isEqualTo("Causa");
    assertThat(resuelto.path("resolutionSummary").asText()).isEqualTo("Solución");
    assertThat(resuelto.path("canConfirm").asBoolean()).isTrue();
    // El contrato fija estos campos: no se filtra quién reportó ni datos internos del ticket.
    assertThat(resuelto.fieldNames()).toIterable().containsExactlyInAnyOrder(
        "ticketId", "businessKey", "systemName", "description", "status",
        "rootCause", "resolutionSummary", "canConfirm");
    JsonNode abierto = ticketCon(tickets, enAtencion);
    assertThat(abierto.path("status").asText()).isEqualTo("OPEN");
    assertThat(abierto.path("canConfirm").asBoolean()).isFalse();
  }

  @Test
  void sinMotorLosTicketsSeMuestranSinOpcionDeConfirmar() throws Exception {
    String businessKey = claveNueva();
    registrarSolucion(crearTicket(businessKey, "USR-01"));
    MockHttpSession sesion = sesionDe("USR-01");
    when(motor.get(eq("/task"), any())).thenThrow(new MotorNoDisponibleException("sin conexión", null));

    MvcResult resultado = mvc.perform(get("/api/app/ti/tickets").session(sesion))
        .andExpect(status().isOk())
        .andReturn();

    JsonNode ticket = ticketCon(leer(resultado), businessKey);
    assertThat(ticket.path("status").asText()).isEqualTo("RESOLVED");
    assertThat(ticket.path("canConfirm").asBoolean()).isFalse();
  }

  // --------------------------------------------------------------- confirmar

  @Test
  void confirmarUnTicketAjenoOInexistenteResponde404() throws Exception {
    String businessKey = claveNueva();
    String ticketAjeno = crearTicket(businessKey, "mesa1");
    registrarSolucion(ticketAjeno);
    tareasDelMotor.add(new Tarea("t-ajena", "confirmSolution", "mesa1", "pi-1", businessKey));
    MockHttpSession sesion = sesionDe("USR-01");

    for (String ticketId : List.of(ticketAjeno, "TI-999999", "TI-incorrecto", "15")) {
      mvc.perform(conCsrf(post("/api/app/ti/tickets/" + ticketId + "/confirm"), sesion)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"confirmed\":true}"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value("Ticket no encontrado"));
    }

    verify(motor, never()).post(startsWith("/task/"), any());
  }

  @Test
  void confirmarSinTareaPendienteResponde409() throws Exception {
    String ticketId = crearTicket(claveNueva(), "USR-01");

    mvc.perform(conCsrf(post("/api/app/ti/tickets/" + ticketId + "/confirm"), sesionDe("USR-01"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Este ticket no tiene una confirmación pendiente"));

    verify(motor, never()).post(startsWith("/task/"), any());
  }

  @Test
  void confirmarCompletaLaTareaConLaRespuestaDeLaPersona() throws Exception {
    String businessKey = claveNueva();
    String ticketId = crearTicket(businessKey, "USR-01");
    registrarSolucion(ticketId);
    tareasDelMotor.add(new Tarea("t-77", "confirmSolution", "USR-01", "pi-1", businessKey));

    mvc.perform(conCsrf(post("/api/app/ti/tickets/" + ticketId + "/confirm"), sesionDe("USR-01"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticketId").value(ticketId))
        .andExpect(jsonPath("$.confirmed").value(false));

    ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
    verify(motor).post(eq("/task/t-77/complete"), cuerpo.capture());
    JsonNode respuesta = json.valueToTree(cuerpo.getValue()).path("variables").path("userConfirmed");
    assertThat(respuesta.path("value").isBoolean()).isTrue();
    assertThat(respuesta.path("value").asBoolean()).isFalse();
    assertThat(respuesta.path("type").asText()).isEqualTo("Boolean");
  }

  @Test
  void confirmarSinDecirSiONoResponde400() throws Exception {
    String ticketId = crearTicket(claveNueva(), "USR-01");

    mvc.perform(conCsrf(post("/api/app/ti/tickets/" + ticketId + "/confirm"), sesionDe("USR-01"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Indica si la solución resolvió el incidente"));
  }

  @Test
  void siLaTareaSeCerroMientrasTantoResponde409() throws Exception {
    String businessKey = claveNueva();
    String ticketId = crearTicket(businessKey, "USR-01");
    tareasDelMotor.add(new Tarea("t-88", "confirmSolution", "USR-01", "pi-1", businessKey));
    when(motor.post(eq("/task/t-88/complete"), any()))
        .thenThrow(new MotorException(500, "{\"message\":\"task is null\"}"));
    when(motor.get("/task/t-88")).thenThrow(new MotorException(404, "{\"message\":\"No matching task\"}"));

    mvc.perform(conCsrf(post("/api/app/ti/tickets/" + ticketId + "/confirm"), sesionDe("USR-01"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\":true}"))
        .andExpect(status().isConflict());
  }

  @Test
  void siElMotorRechazaCompletarUnaTareaQueSigueAhiResponde502() throws Exception {
    String businessKey = claveNueva();
    String ticketId = crearTicket(businessKey, "USR-01");
    tareasDelMotor.add(new Tarea("t-99", "confirmSolution", "USR-01", "pi-1", businessKey));
    when(motor.post(eq("/task/t-99/complete"), any()))
        .thenThrow(new MotorException(500, "{\"message\":\"fallo interno\"}"));

    mvc.perform(conCsrf(post("/api/app/ti/tickets/" + ticketId + "/confirm"), sesionDe("USR-01"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\":true}"))
        .andExpect(status().isBadGateway());
  }

  // ------------------------------------------------------------- API interna

  @Test
  void laApiInternaExigeLaCredencial() throws Exception {
    mvc.perform(get("/api/tickets").param("requesterId", "USR-01"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Credencial interna requerida"));
    mvc.perform(get("/api/tickets"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(ticketNuevo(claveNueva(), "USR-01").toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/tickets/TI-1/cerrar")
            .header(CredencialInterna.HEADER, "otro-token"))
        .andExpect(status().isUnauthorized());
    // La sesión de una persona, aunque sea administradora, no abre la API interna.
    mvc.perform(get("/api/tickets").param("requesterId", "USR-01").session(sesionDe("demo")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void crearTicketEsIdempotentePorBusinessKey() throws Exception {
    String businessKey = claveNueva();
    ObjectNode ticket = ticketNuevo(businessKey, businessKey);

    mvc.perform(interna(post("/api/tickets"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessKey\":\"" + businessKey + "\"}"))
        .andExpect(status().isBadRequest());

    MvcResult creado = mvc.perform(interna(post("/api/tickets"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(ticket.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticketStatus").value("OPEN"))
        .andReturn();
    String ticketId = leer(creado).path("ticketId").asText();
    assertThat(ticketId).matches("TI-\\d+");

    mvc.perform(interna(post("/api/tickets"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(ticket.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticketId").value(ticketId));

    mvc.perform(interna(post("/api/tickets"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(ticket.deepCopy().put("description", "Otro").toString()))
        .andExpect(status().isConflict());

    mvc.perform(interna(get("/api/tickets")).param("requesterId", businessKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ticketId").value(ticketId))
        .andExpect(jsonPath("$[0].businessKey").value(businessKey))
        .andExpect(jsonPath("$[0].status").value("OPEN"));
  }

  @Test
  void unTicketCerradoNoAdmiteSolucionNiReapertura() throws Exception {
    String ticketId = crearTicket(claveNueva(), "USR-01");

    mvc.perform(interna(post("/api/tickets/" + ticketId + "/solucion"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rootCause\":\"Causa\",\"resolutionSummary\":\"Solución\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.knowledgeArticleId").value("KB-" + ticketId));
    mvc.perform(interna(post("/api/tickets/" + ticketId + "/reabrir")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticketStatus").value("OPEN"));
    mvc.perform(interna(post("/api/tickets/" + ticketId + "/cerrar"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticketStatus").value("CLOSED"));

    mvc.perform(interna(post("/api/tickets/" + ticketId + "/solucion"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rootCause\":\"Causa\",\"resolutionSummary\":\"Solución\"}"))
        .andExpect(status().isConflict());
    mvc.perform(interna(post("/api/tickets/" + ticketId + "/reabrir")))
        .andExpect(status().isConflict());
    mvc.perform(interna(post("/api/tickets/TI-incorrecto/cerrar"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(interna(post("/api/tickets/TI-999999/cerrar")))
        .andExpect(status().isNotFound());
  }

  @Test
  void unTicketResueltoVuelveAAbiertoSiSoporteLoRetomo() throws Exception {
    String businessKey = claveNueva();
    registrarSolucion(crearTicket(businessKey, "USR-01"));
    tareasDelMotor.add(new Tarea("t-soporte", "resolveN1", null, "pi-sub", businessKey));

    mvc.perform(interna(get("/api/tickets")).param("requesterId", "USR-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.businessKey == '" + businessKey + "')].status").value("OPEN"));
  }

  // ------------------------------------------------------------------ página

  @Test
  void laPaginaCargaLosRecursosComunesYNoExponeElMotor() throws Exception {
    MvcResult pagina = mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(pagina.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("href=\"/portal.css\"")
        .contains("src=\"/auth.js\"")
        .contains("src=\"/app.js\"")
        .contains("perfil: \"empleado\"")
        .doesNotContain("engine-rest")
        .doesNotContain("9000")
        .doesNotContain(TOKEN);

    MvcResult script = mvc.perform(get("/app.js"))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(script.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("/ti/reports")
        .doesNotContain("engine-rest")
        .doesNotContain(CredencialInterna.HEADER);
    mvc.perform(get("/auth.js"))
        .andExpect(status().isOk());
    mvc.perform(get("/portal.css"))
        .andExpect(status().isOk());
  }

  // ------------------------------------------------------- motor de mentiras

  private ArrayNode tareasQueCumplen(Map<String, ?> filtros) {
    ArrayNode tareas = json.createArrayNode();
    for (Tarea tarea : tareasDelMotor) {
      if (cumple(tarea, filtros)) {
        tareas.addObject()
            .put("id", tarea.id())
            .put("taskDefinitionKey", tarea.definicion())
            .put("assignee", tarea.asignada())
            .put("processInstanceId", tarea.instancia());
      }
    }
    return tareas;
  }

  private static boolean cumple(Tarea tarea, Map<String, ?> filtros) {
    Object asignada = filtros.get("assignee");
    Object definicion = filtros.get("taskDefinitionKey");
    Object definiciones = filtros.get("taskDefinitionKeyIn");
    Object businessKey = filtros.get("processInstanceBusinessKey");
    return (asignada == null || asignada.equals(tarea.asignada()))
        && (definicion == null || definicion.equals(tarea.definicion()))
        && (definiciones == null || ((Collection<?>) definiciones).contains(tarea.definicion()))
        && (businessKey == null || businessKey.equals(tarea.businessKey()));
  }

  private ArrayNode instancias(Map<String, ?> filtros) {
    Collection<?> pedidas = (Collection<?>) filtros.get("processInstanceIds");
    ArrayNode instancias = json.createArrayNode();
    for (Tarea tarea : tareasDelMotor) {
      if (pedidas.contains(tarea.instancia())) {
        instancias.addObject()
            .put("id", tarea.instancia())
            .put("businessKey", tarea.businessKey());
      }
    }
    return instancias;
  }

  private JsonNode inicioEnviadoAlMotor() {
    ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
    verify(motor).post(eq(INICIO_DEL_PROCESO), cuerpo.capture());
    return json.valueToTree(cuerpo.getValue());
  }

  // ------------------------------------------------------------------ ayudas

  private ObjectNode reporte() {
    return json.createObjectNode()
        .put("systemName", "ERP")
        .put("description", "No inicia sesión")
        .put("impact", "Medio")
        .put("urgency", "Alta")
        .put("affectedTeams", 1)
        .put("safetyImpact", false);
  }

  private ObjectNode ticketNuevo(String businessKey, String requesterId) {
    return json.createObjectNode()
        .put("businessKey", businessKey)
        .put("requesterId", requesterId)
        .put("systemName", "ERP")
        .put("description", "No inicia sesión")
        .put("impact", "Medio")
        .put("urgency", "Media")
        .put("safetyImpact", false)
        .put("affectedTeams", 1);
  }

  /** Crea el ticket como lo hace el motor y devuelve su ticketId. */
  private String crearTicket(String businessKey, String requesterId) throws Exception {
    MvcResult creado = mvc.perform(interna(post("/api/tickets"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(ticketNuevo(businessKey, requesterId).toString()))
        .andExpect(status().isOk())
        .andReturn();
    return leer(creado).path("ticketId").asText();
  }

  private void registrarSolucion(String ticketId) throws Exception {
    mvc.perform(interna(post("/api/tickets/" + ticketId + "/solucion"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rootCause\":\"Causa\",\"resolutionSummary\":\"Solución\"}"))
        .andExpect(status().isOk());
  }

  private void exigir400(MockHttpSession sesion, JsonNode cuerpo, String mensaje) throws Exception {
    mvc.perform(conCsrf(post("/api/app/ti/reports"), sesion)
            .contentType(MediaType.APPLICATION_JSON)
            .content(cuerpo.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(mensaje));
  }

  private static String claveNueva() {
    return "PRUEBA-" + UUID.randomUUID();
  }

  private static List<String> claves(JsonNode tickets) {
    List<String> claves = new ArrayList<>();
    for (JsonNode ticket : tickets) {
      claves.add(ticket.path("businessKey").asText());
    }
    return claves;
  }

  private static JsonNode ticketCon(JsonNode tickets, String businessKey) {
    for (JsonNode ticket : tickets) {
      if (businessKey.equals(ticket.path("businessKey").asText())) {
        return ticket;
      }
    }
    throw new AssertionError("No está el ticket de " + businessKey);
  }

  private JsonNode leer(MvcResult resultado) throws Exception {
    return json.readTree(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static MockHttpServletRequestBuilder interna(MockHttpServletRequestBuilder peticion) {
    return peticion.header(CredencialInterna.HEADER, TOKEN);
  }

  /** Igual que auth.js: pide un token nuevo justo antes de cada escritura. */
  private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder peticion, MockHttpSession sesion)
      throws Exception {
    MvcResult resultado = mvc.perform(get("/api/app/csrf").session(sesion))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode csrf = json.readTree(resultado.getResponse().getContentAsString());
    return peticion
        .session(sesion)
        .header(csrf.get("header").asText(), csrf.get("token").asText());
  }

  private MockHttpSession sesionDe(String usuario) throws Exception {
    MockHttpSession sesion = new MockHttpSession();
    mvc.perform(conCsrf(post("/api/app/login"), sesion)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("username", usuario)
            .param("password", CLAVE))
        .andExpect(status().isNoContent());
    return sesion;
  }
}
