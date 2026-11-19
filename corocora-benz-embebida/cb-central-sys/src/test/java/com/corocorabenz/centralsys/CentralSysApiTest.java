package com.corocorabenz.centralsys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corocorabenz.centralsys.bodega.ClienteBodega;
import com.corocorabenz.comun.motor.MotorClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

/**
 * La API de CentralSys vista desde el navegador (sección 5.3 del contrato), con el motor
 * y Taller-Bodega simulados y la base H2 en memoria.
 */
@SpringBootTest(properties = {
    "corocora.internal-token=token-de-prueba",
    "corocora.engine-rest=http://motor.test/engine-rest",
    "corocora.central.espera-reasignacion-ms=300",
    "corocora.central.intervalo-sondeo-ms=20"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class CentralSysApiTest {

  private static final String TAREAS = "/api/app/central/tasks";

  private static final String FORMULARIO_ENTREGA = """
      {
        "components": [
          {"type": "textfield", "key": "serviceOrderId", "label": "Orden", "disabled": true},
          {"type": "number", "key": "customerSatisfaction", "label": "Satisfacción",
           "validate": {"required": true, "min": 1, "max": 5}},
          {"type": "number", "key": "discount", "label": "Descuento"},
          {"type": "group", "label": "Entrega", "components": [
            {"type": "checkbox", "key": "delivered", "label": "Entregado"},
            {"type": "textfield", "key": "deliveryNote", "label": "Observación"},
            {"type": "number", "key": "serviceCost", "label": "Costo", "disabled": true}
          ]}
        ]
      }
      """;

  private static final String FORMULARIO_REVISION = """
      {
        "components": [
          {"type": "textfield", "key": "ticketId", "label": "Ticket", "disabled": true},
          {"type": "textfield", "key": "recoveryPlan", "label": "Plan de atención", "validate": {"required": true}},
          {"type": "textfield", "key": "newOwner", "label": "Nuevo responsable"}
        ]
      }
      """;

  private final ObjectMapper json = new ObjectMapper();

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private MotorClient motor;

  @MockitoBean
  private ClienteBodega bodega;

  private MotorSimulado simulado;

  @BeforeEach
  void prepararMotor() throws Exception {
    simulado = new MotorSimulado();
    simulado.usuario("mesa1", "mesa-servicio");
    simulado.usuario("mesa2", "mesa-servicio");
    simulado.usuario("responsable1", "responsables-ti");
    simulado.usuario("soporte1", "soporte-ti-n1");
    simulado.usuario("nomina1", "analistas-nomina");
    simulado.usuario("asesor1", "asesores-servicio");
    simulado.usuario("bodega1", "bodega");
    simulado.usuario("cliente1", "clientes");
    simulado.usuario("demo", "camunda-admin");
    simulado.usuario("USR-01");

    simulado.tarea("t-triaje", "triageIncident", null, "TI-0001", "mesa-servicio");
    simulado.tarea("t-entrega", "deliverVehicle", "mesa1", "PV-0001", "mesa-servicio");
    simulado.tarea("t-revision", "reviewSla", "responsable1", "TI-0002", "responsables-ti");
    simulado.tarea("t-confirmar", "confirmSolution", "USR-01", "TI-0003");
    simulado.formulario("t-triaje", FORMULARIO_REVISION);
    simulado.formulario("t-entrega", FORMULARIO_ENTREGA);
    simulado.formulario("t-revision", FORMULARIO_REVISION);
    simulado.conectar(motor);
  }

  // ------------------------------------------------------------ sesión y perfil

  @Test
  void sinSesionTodaLaApiResponde401() throws Exception {
    mvc.perform(get(TAREAS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Inicia sesión"));
    mvc.perform(get("/api/app/central/actividad"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/app/bodega/reservas"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unClienteNoUsaLaIntranet() throws Exception {
    MockHttpSession cliente = sesionDe("cliente1");
    mvc.perform(get(TAREAS).session(cliente))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").isNotEmpty());
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/claim"), cliente))
        .andExpect(status().isForbidden());
    mvc.perform(get(TAREAS + "/t-triaje/deployed-form").session(cliente))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/app/central/actividad").session(cliente))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/app/bodega/reservas").session(cliente))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(post("/api/app/central/nomina"), cliente).content(nominaValida()))
        .andExpect(status().isForbidden());
    assertThat(simulado.asignadaA("t-triaje")).isNull();
  }

  @Test
  void laPaginaNoExponeLaUrlDelMotor() throws Exception {
    String pagina = mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
    assertThat(pagina).contains("window.CB_PORTAL");
    assertThat(pagina).contains("/auth.js");
    assertThat(pagina).doesNotContain("engine-rest");
    assertThat(pagina).doesNotContain("motor.test");
  }

  // ------------------------------------------------------------------- bandeja

  @Test
  void laBandejaTraeLasLibresDeMisGruposYLasMias() throws Exception {
    mvc.perform(get(TAREAS).session(sesionDe("mesa1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.id=='t-triaje')].businessKey").value("TI-0001"))
        .andExpect(jsonPath("$[?(@.id=='t-triaje')].taskDefinitionKey").value("triageIncident"))
        .andExpect(jsonPath("$[?(@.id=='t-triaje')].processDefinitionKey").value("CB_GestionIncidenteTI"))
        .andExpect(jsonPath("$[?(@.id=='t-triaje')].processName").value("Gestión de incidentes de TI"))
        .andExpect(jsonPath("$[?(@.id=='t-triaje')].candidateGroups[0]").value("mesa-servicio"))
        .andExpect(jsonPath("$[?(@.id=='t-entrega')].assignee").value("mesa1"));
  }

  @Test
  void laBandejaNoMuestraLasQueTieneOtraPersonaDelGrupo() throws Exception {
    mvc.perform(get(TAREAS).session(sesionDe("mesa2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("t-triaje"));
  }

  @Test
  void elAdminVeTodasYUnEmpleadoSinGrupoSoloLasSuyas() throws Exception {
    mvc.perform(get(TAREAS).session(sesionDe("demo")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4));
    mvc.perform(get(TAREAS).session(sesionDe("USR-01")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("t-confirmar"))
        .andExpect(jsonPath("$[0].candidateGroups").isEmpty());
  }

  @Test
  void laBandejaPideLasInstanciasEnUnaSolaConsulta() throws Exception {
    mvc.perform(get(TAREAS).session(sesionDe("demo")))
        .andExpect(status().isOk());
    long consultasDeInstancias = simulado.llamadas.stream()
        .filter("GET /process-instance"::equals)
        .count();
    assertThat(consultasDeInstancias).isEqualTo(1);
  }

  // ------------------------------------------------------------------ permisos

  @Test
  void otroGrupoNoPuedeReclamarNiLeerLaTarea() throws Exception {
    MockHttpSession responsable = sesionDe("responsable1");
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/claim"), responsable))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").isNotEmpty());
    mvc.perform(get(TAREAS + "/t-triaje/deployed-form").session(responsable))
        .andExpect(status().isForbidden());
    mvc.perform(get(TAREAS + "/t-triaje/form-variables").session(responsable))
        .andExpect(status().isForbidden());
    mvc.perform(get(TAREAS + "/t-triaje/variables").session(responsable))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/unclaim"), responsable))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/complete"), responsable).content("{\"variables\":{}}"))
        .andExpect(status().isForbidden());
    assertThat(simulado.asignadaA("t-triaje")).isNull();
    assertThat(simulado.existe("t-triaje")).isTrue();
  }

  @Test
  void unaTareaInexistenteEs404EnTodosLosEndpoints() throws Exception {
    MockHttpSession mesa = sesionDe("mesa1");
    mvc.perform(conCsrf(post(TAREAS + "/no-existe/claim"), mesa))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").isNotEmpty());
    mvc.perform(conCsrf(post(TAREAS + "/no-existe/unclaim"), mesa))
        .andExpect(status().isNotFound());
    mvc.perform(get(TAREAS + "/no-existe/deployed-form").session(mesa))
        .andExpect(status().isNotFound());
    mvc.perform(get(TAREAS + "/no-existe/form-variables").session(mesa))
        .andExpect(status().isNotFound());
    mvc.perform(get(TAREAS + "/no-existe/variables").session(mesa))
        .andExpect(status().isNotFound());
    mvc.perform(conCsrf(post(TAREAS + "/no-existe/complete"), mesa).content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void quienNoEsDelGrupoTampocoLeeUnaTareaAsignadaAOtro() throws Exception {
    mvc.perform(get(TAREAS + "/t-entrega/variables").session(sesionDe("mesa2")))
        .andExpect(status().isForbidden());
    mvc.perform(get(TAREAS + "/t-confirmar/deployed-form").session(sesionDe("mesa1")))
        .andExpect(status().isForbidden());
  }

  // ---------------------------------------------------------- reclamar y soltar

  @Test
  void elGrupoCorrectoReclamaLeeYSuelta() throws Exception {
    MockHttpSession mesa = sesionDe("mesa1");
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/claim"), mesa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("t-triaje"))
        .andExpect(jsonPath("$.assignee").value("mesa1"));
    assertThat(simulado.asignadaA("t-triaje")).isEqualTo("mesa1");

    mvc.perform(get(TAREAS + "/t-triaje/deployed-form").session(mesa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components[0].key").value("ticketId"));
    mvc.perform(get(TAREAS + "/t-triaje/form-variables").session(mesa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticketId.value").value("TI-0001"));
    mvc.perform(get(TAREAS + "/t-triaje/variables").session(mesa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticketId.type").value("String"));

    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/unclaim"), mesa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignee").isEmpty());
    assertThat(simulado.asignadaA("t-triaje")).isNull();
  }

  @Test
  void reclamarLaQueYaTieneOtraPersonaDelGrupoEs409() throws Exception {
    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/claim"), sesionDe("mesa2")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Otra persona ya tiene esta tarea"));
    assertThat(simulado.asignadaA("t-entrega")).isEqualTo("mesa1");
  }

  @Test
  void soloQuienLaTieneLaSuelta() throws Exception {
    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/unclaim"), sesionDe("mesa2")))
        .andExpect(status().isForbidden());
    assertThat(simulado.asignadaA("t-entrega")).isEqualTo("mesa1");
  }

  @Test
  void unaTareaSinGrupoNoSePuedeSoltar() throws Exception {
    mvc.perform(conCsrf(post(TAREAS + "/t-confirmar/unclaim"), sesionDe("USR-01")))
        .andExpect(status().isConflict());
    assertThat(simulado.asignadaA("t-confirmar")).isEqualTo("USR-01");
  }

  @Test
  void sinTokenCsrfNoSeEscribe() throws Exception {
    mvc.perform(post(TAREAS + "/t-triaje/claim").session(sesionDe("mesa1")))
        .andExpect(status().isForbidden());
    assertThat(simulado.asignadaA("t-triaje")).isNull();
  }

  // ----------------------------------------------------------------- completar

  @Test
  void completarSoloAceptaLosCamposEditablesYFijaElTipo() throws Exception {
    String cuerpo = """
        {"variables": {
          "serviceOrderId": {"value": "OTRA-ORDEN", "type": "String"},
          "customerSatisfaction": {"value": "4", "type": "String"},
          "discount": {"value": 10.5, "type": "String"},
          "delivered": {"value": true, "type": "String"},
          "deliveryNote": {"value": 123, "type": "Long"},
          "serviceCost": {"value": 1, "type": "Long"},
          "coverageApproved": {"value": true, "type": "Boolean"}
        }}
        """;
    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/complete"), sesionDe("mesa1")).content(cuerpo))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completed").value(true))
        .andExpect(jsonPath("$.assigned").value((Object) null));

    Map<String, Map<String, Object>> enviadas = simulado.completadas.get("t-entrega");
    assertThat(enviadas).containsOnlyKeys("customerSatisfaction", "discount", "delivered", "deliveryNote");
    assertThat(enviadas.get("customerSatisfaction")).containsEntry("value", 4L).containsEntry("type", "Long");
    assertThat(enviadas.get("discount")).containsEntry("value", 10.5).containsEntry("type", "Double");
    assertThat(enviadas.get("delivered")).containsEntry("value", true).containsEntry("type", "Boolean");
    assertThat(enviadas.get("deliveryNote")).containsEntry("value", "123").containsEntry("type", "String");
  }

  @Test
  void completarSinUnCampoObligatorioEs400YNoCompleta() throws Exception {
    String cuerpo = "{\"variables\":{\"deliveryNote\":{\"value\":\"Todo bien\",\"type\":\"String\"}}}";
    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/complete"), sesionDe("mesa1")).content(cuerpo))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Falta el campo obligatorio «Satisfacción»"));
    assertThat(simulado.existe("t-entrega")).isTrue();
  }

  @Test
  void completarRespetaElMaximoDelFormulario() throws Exception {
    String cuerpo = "{\"variables\":{\"customerSatisfaction\":{\"value\":6,\"type\":\"Long\"}}}";
    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/complete"), sesionDe("mesa1")).content(cuerpo))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").isNotEmpty());
    assertThat(simulado.existe("t-entrega")).isTrue();
  }

  @Test
  void soloQuienLaTieneAsignadaLaCompleta() throws Exception {
    String cuerpo = "{\"variables\":{\"customerSatisfaction\":{\"value\":5,\"type\":\"Long\"}}}";
    // mesa2 es del grupo candidato, pero la tarea la tiene mesa1.
    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/complete"), sesionDe("mesa2")).content(cuerpo))
        .andExpect(status().isForbidden());
    // Una tarea libre de mi grupo tampoco: primero hay que reclamarla.
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/complete"), sesionDe("mesa1"))
            .content("{\"variables\":{\"recoveryPlan\":{\"value\":\"x\",\"type\":\"String\"}}}"))
        .andExpect(status().isForbidden());
    assertThat(simulado.existe("t-entrega")).isTrue();
    assertThat(simulado.existe("t-triaje")).isTrue();

    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/complete"), sesionDe("demo")).content(cuerpo))
        .andExpect(status().isOk());
    assertThat(simulado.existe("t-entrega")).isFalse();
  }

  @Test
  void completarUnaTareaQueOtroAcabaDeCompletarEs404() throws Exception {
    simulado.seEsfumaAntesDeActuar("t-entrega");
    String cuerpo = "{\"variables\":{\"customerSatisfaction\":{\"value\":5,\"type\":\"Long\"}}}";

    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/complete"), sesionDe("mesa1")).content(cuerpo))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("La tarea no existe o ya fue completada"));
  }

  @Test
  void soltarUnaTareaQueOtroAcabaDeCompletarEs404() throws Exception {
    simulado.seEsfumaAntesDeActuar("t-entrega");

    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/unclaim"), sesionDe("mesa1")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("La tarea no existe o ya fue completada"));
  }

  @Test
  void siElMotorRechazaCompletarYLaTareaSigueAhiEs502() throws Exception {
    simulado.fallaAlCompletar("t-entrega");
    String cuerpo = "{\"variables\":{\"customerSatisfaction\":{\"value\":5,\"type\":\"Long\"}}}";

    mvc.perform(conCsrf(post(TAREAS + "/t-entrega/complete"), sesionDe("mesa1")).content(cuerpo))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").isNotEmpty());
    assertThat(simulado.existe("t-entrega")).isTrue();
  }

  // -------------------------------------------------------------- reasignación

  @Test
  void trasRevisarElPlazoLaSiguienteTareaQuedaParaElNuevoResponsable() throws Exception {
    mvc.perform(conCsrf(post(TAREAS + "/t-revision/complete"), sesionDe("responsable1"))
            .content(revision("soporte1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completed").value(true))
        .andExpect(jsonPath("$.assigned").value(true));
    assertThat(simulado.asignadaA("soporte-t-revision")).isEqualTo("soporte1");
  }

  @Test
  void noSeReasignaAUnClienteNiAQuienNoExiste() throws Exception {
    mvc.perform(conCsrf(post(TAREAS + "/t-revision/complete"), sesionDe("responsable1"))
            .content(revision("cliente1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigned").value(false));
    assertThat(simulado.asignadaA("soporte-t-revision")).isNull();

    simulado.tarea("t-revision-2", "reviewSla", "responsable1", "TI-0009", "responsables-ti");
    simulado.formulario("t-revision-2", FORMULARIO_REVISION);
    mvc.perform(conCsrf(post(TAREAS + "/t-revision-2/complete"), sesionDe("responsable1"))
            .content(revision("nadie")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigned").value(false));
  }

  @Test
  void siLaSiguienteTareaNoApareceAssignedEsFalse() throws Exception {
    simulado.creaTareaDeSoporte = false;
    mvc.perform(conCsrf(post(TAREAS + "/t-revision/complete"), sesionDe("responsable1"))
            .content(revision("soporte1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completed").value(true))
        .andExpect(jsonPath("$.assigned").value(false));
  }

  @Test
  void sinNuevoResponsableAssignedEsNull() throws Exception {
    mvc.perform(conCsrf(post(TAREAS + "/t-revision/complete"), sesionDe("responsable1"))
            .content("{\"variables\":{\"recoveryPlan\":{\"value\":\"Escalar\",\"type\":\"String\"}}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigned").value((Object) null));
    assertThat(simulado.asignadaA("soporte-t-revision")).isNull();
  }

  // -------------------------------------------------------------------- nómina

  @Test
  void soloAnalistasDeNominaOAdminInicianLaNomina() throws Exception {
    // Sin permiso gana el 403 aunque los datos sean incorrectos.
    mvc.perform(conCsrf(post("/api/app/central/nomina"), sesionDe("USR-01")).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(post("/api/app/central/nomina"), sesionDe("mesa1")).content(nominaValida()))
        .andExpect(status().isForbidden());
    assertThat(simulado.ultimaNomina).isNull();

    mvc.perform(conCsrf(post("/api/app/central/nomina"), sesionDe("demo")).content(nominaValida()))
        .andExpect(status().isOk());
  }

  @Test
  void laNominaSeIniciaConSuClaveYSusVariables() throws Exception {
    MvcResult resultado = mvc.perform(conCsrf(post("/api/app/central/nomina"), sesionDe("nomina1"))
            .content(nominaValida()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("instancia-nomina"))
        .andReturn();
    JsonNode respuesta = json.readTree(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(respuesta.get("businessKey").asText()).matches("NOM-2026-11-Q2-[A-Z0-9]{6}");

    assertThat(simulado.llamadas).contains("POST /process-definition/key/CB_GestionNomina/start");
    assertThat(simulado.ultimaNomina.get("businessKey")).isEqualTo(respuesta.get("businessKey").asText());
    JsonNode variables = json.valueToTree(simulado.ultimaNomina.get("variables"));
    assertThat(variables.size()).isEqualTo(3);
    assertThat(variables.at("/payPeriodId/value").asText()).isEqualTo("2026-11-Q2");
    assertThat(variables.at("/payCycle/value").asText()).isEqualTo("QUINCENAL");
    assertThat(variables.at("/cutoffDate/value").asText()).isEqualTo("2026-11-30");
    assertThat(variables.at("/cutoffDate/type").asText()).isEqualTo("String");
  }

  @Test
  void laNominaEnviaLasSimulacionesMarcadasComoBooleanos() throws Exception {
    String conSimulaciones = nominaCon(
        "\"simulatePeriodClosed\":true,\"simulateMissingSupports\":true,\"simulateLateChanges\":true");

    mvc.perform(conCsrf(post("/api/app/central/nomina"), sesionDe("nomina1")).content(conSimulaciones))
        .andExpect(status().isOk());

    JsonNode variables = json.valueToTree(simulado.ultimaNomina.get("variables"));
    assertThat(variables.size()).isEqualTo(6);
    for (String bandera : new String[] {"simulatePeriodClosed", "simulateMissingSupports", "simulateLateChanges"}) {
      assertThat(variables.at("/" + bandera + "/value").isBoolean()).as(bandera).isTrue();
      assertThat(variables.at("/" + bandera + "/value").asBoolean()).as(bandera).isTrue();
      assertThat(variables.at("/" + bandera + "/type").asText()).as(bandera).isEqualTo("Boolean");
    }
  }

  @Test
  void laNominaNoEnviaLasSimulacionesSinMarcar() throws Exception {
    String soloFueraDeCorte = nominaCon("\"simulatePeriodClosed\":false,\"simulateLateChanges\":true");

    mvc.perform(conCsrf(post("/api/app/central/nomina"), sesionDe("nomina1")).content(soloFueraDeCorte))
        .andExpect(status().isOk());

    JsonNode variables = json.valueToTree(simulado.ultimaNomina.get("variables"));
    assertThat(variables.size()).isEqualTo(4);
    assertThat(variables.at("/simulateLateChanges/value").asBoolean()).isTrue();
    assertThat(variables.has("simulatePeriodClosed")).isFalse();
    assertThat(variables.has("simulateMissingSupports")).isFalse();
  }

  @Test
  void laNominaValidaSusDatos() throws Exception {
    MockHttpSession analista = sesionDe("nomina1");
    String periodoInvalido = "{\"payPeriodId\":\"2026 11\",\"payCycle\":\"MENSUAL\",\"cutoffDate\":\"2026-11-30\"}";
    String cicloInvalido = "{\"payPeriodId\":\"2026-11\",\"payCycle\":\"SEMANAL\",\"cutoffDate\":\"2026-11-30\"}";
    String fechaInvalida = "{\"payPeriodId\":\"2026-11\",\"payCycle\":\"MENSUAL\",\"cutoffDate\":\"2026-02-30\"}";
    for (String cuerpo : new String[] {periodoInvalido, cicloInvalido, fechaInvalida, "{}"}) {
      mvc.perform(conCsrf(post("/api/app/central/nomina"), analista).content(cuerpo))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").isNotEmpty());
    }
    assertThat(simulado.ultimaNomina).isNull();
  }

  // -------------------------------------------------------------------- bodega

  @Test
  void elPanelDeBodegaEsSoloParaBodegaOAdmin() throws Exception {
    when(bodega.reservas()).thenReturn(json.readTree("[{\"id\":7,\"businessKey\":\"PV-1\",\"estado\":\"PENDIENTE\"}]"));
    mvc.perform(get("/api/app/bodega/reservas").session(sesionDe("bodega1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].estado").value("PENDIENTE"));
    mvc.perform(get("/api/app/bodega/reservas").session(sesionDe("demo")))
        .andExpect(status().isOk());
  }

  @Test
  void otroGrupoNoEntraAlPanelDeBodega() throws Exception {
    MockHttpSession asesor = sesionDe("asesor1");
    mvc.perform(get("/api/app/bodega/reservas").session(asesor))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/app/bodega/solicitudes").session(asesor))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/app/bodega/simulacion").session(asesor))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(post("/api/app/bodega/reservas/7/disponibles"), asesor))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(post("/api/app/bodega/solicitudes/3/entregar"), asesor))
        .andExpect(status().isForbidden());
    mvc.perform(conCsrf(put("/api/app/bodega/simulacion"), asesor).content("{\"respuestaAutomatica\":false}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(bodega);
  }

  @Test
  void elConflictoDeLaBodegaLlegaComo409() throws Exception {
    when(bodega.avisarDisponibilidad("7")).thenThrow(
        new ResponseStatusException(HttpStatus.CONFLICT, "El proceso todavía no espera el aviso de Bodega"));
    mvc.perform(conCsrf(post("/api/app/bodega/reservas/7/disponibles"), sesionDe("bodega1")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("El proceso todavía no espera el aviso de Bodega"));
    // Un id que no es un número no puede existir en la bodega: ni se reenvía.
    mvc.perform(conCsrf(post("/api/app/bodega/solicitudes/abc/entregar"), sesionDe("bodega1")))
        .andExpect(status().isNotFound());
  }

  @Test
  void laSimulacionSoloReenviaLosCamposConocidos() throws Exception {
    when(bodega.cambiarSimulacion(any())).thenReturn(json.readTree("{\"respuestaAutomatica\":false}"));
    mvc.perform(conCsrf(put("/api/app/bodega/simulacion"), sesionDe("bodega1"))
            .content("{\"respuestaAutomatica\":false,\"otraCosa\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.respuestaAutomatica").value(false));
    verify(bodega).cambiarSimulacion(Map.of("respuestaAutomatica", false));

    mvc.perform(conCsrf(put("/api/app/bodega/simulacion"), sesionDe("bodega1")).content("{\"retardoSegundos\":-1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void elCambioDeSimulacionQuedaEnLaActividadConPalabrasYNoConNombresInternos() throws Exception {
    when(bodega.cambiarSimulacion(any())).thenReturn(json.readTree("{\"respuestaAutomatica\":false}"));
    MockHttpSession deBodega = sesionDe("bodega1");

    mvc.perform(conCsrf(put("/api/app/bodega/simulacion"), deBodega)
            .content("{\"respuestaAutomatica\":false,\"retardoSegundos\":5,\"repuestosDisponibles\":true}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/app/central/actividad").session(deBodega))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].accion").value("CAMBIAR_SIMULACION"))
        .andExpect(jsonPath("$[0].detalle")
            .value("Respuesta automática: no, Retardo: 5 s, Repuestos disponibles al reservar: sí"));
  }

  // ----------------------------------------------------------------- actividad

  @Test
  void cadaAccionQuedaEnLaActividadDeQuienLaHizo() throws Exception {
    MockHttpSession mesa = sesionDe("mesa2");
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/claim"), mesa))
        .andExpect(status().isOk());
    mvc.perform(conCsrf(post(TAREAS + "/t-triaje/complete"), mesa)
            .content("{\"variables\":{\"recoveryPlan\":{\"value\":\"Plan\",\"type\":\"String\"}}}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/app/central/actividad").session(mesa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].accion").value("COMPLETAR"))
        .andExpect(jsonPath("$[0].businessKey").value("TI-0001"))
        .andExpect(jsonPath("$[0].tareaId").value("t-triaje"))
        .andExpect(jsonPath("$[0].usuario").value("mesa2"))
        .andExpect(jsonPath("$[0].fecha").isNotEmpty())
        .andExpect(jsonPath("$[1].accion").value("RECLAMAR"));

    // La actividad es de cada persona: otra cuenta no la ve.
    mvc.perform(get("/api/app/central/actividad").session(sesionDe("soporte1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // ------------------------------------------------------------------ ayudantes

  private static String nominaValida() {
    return "{\"payPeriodId\":\"2026-11-Q2\",\"payCycle\":\"QUINCENAL\",\"cutoffDate\":\"2026-11-30\"}";
  }

  /** La nómina válida con más campos, escritos como JSON: por ejemplo {@code "simulateLateChanges":true}. */
  private static String nominaCon(String camposAdicionales) {
    String valida = nominaValida();
    return valida.substring(0, valida.length() - 1) + "," + camposAdicionales + "}";
  }

  private static String revision(String nuevoResponsable) {
    return "{\"variables\":{"
        + "\"recoveryPlan\":{\"value\":\"Escalar al fabricante\",\"type\":\"String\"},"
        + "\"newOwner\":{\"value\":\"" + nuevoResponsable + "\",\"type\":\"String\"}}}";
  }

  private JsonNode pedirCsrf(MockHttpSession sesion) throws Exception {
    MvcResult resultado = mvc.perform(get("/api/app/csrf").session(sesion))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /** Escritura JSON con la sesión y un token CSRF recién pedido, como hace auth.js. */
  private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder peticion, MockHttpSession sesion)
      throws Exception {
    JsonNode csrf = pedirCsrf(sesion);
    return peticion
        .session(sesion)
        .header(csrf.get("header").asText(), csrf.get("token").asText())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}");
  }

  private MockHttpSession sesionDe(String usuario) throws Exception {
    MockHttpSession sesion = new MockHttpSession();
    JsonNode csrf = pedirCsrf(sesion);
    mvc.perform(post("/api/app/login")
            .session(sesion)
            .header(csrf.get("header").asText(), csrf.get("token").asText())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("username", usuario)
            .param("password", MotorSimulado.CLAVE))
        .andExpect(status().isNoContent());
    return sesion;
  }
}
