package com.corocorabenz.centralsys.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Qué variables acepta el servidor al completar una tarea y con qué tipo. */
class FormularioTareaTest {

  private static final String ESQUEMA = """
      {
        "components": [
          {"type": "text", "text": "Instrucciones"},
          {"type": "textfield", "key": "ticketId", "label": "Ticket", "disabled": true},
          {"type": "textfield", "key": "soloLectura", "label": "Solo lectura", "readonly": true},
          {"type": "textarea", "key": "rootCause", "label": "Causa raíz", "validate": {"required": true}},
          {"type": "select", "key": "technicalDomain", "label": "Dominio",
           "values": [{"label": "Nivel 1", "value": "catalogoNivel1"}, {"label": "Especializado", "value": "especializado"}]},
          {"type": "group", "label": "Costos", "components": [
            {"type": "number", "key": "serviceCost", "label": "Costo", "validate": {"min": 0}},
            {"type": "group", "label": "Interno", "components": [
              {"type": "checkbox", "key": "approved", "label": "Aprobado"},
              {"type": "number", "key": "internalCost", "label": "Costo interno", "disabled": true}
            ]}
          ]}
        ]
      }
      """;

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void soloAceptaCamposEditablesTambienLosAnidados() throws Exception {
    Map<String, Object> aceptadas = aceptar("""
        {
          "ticketId": {"value": "TI-9999"},
          "soloLectura": {"value": "cambiado"},
          "rootCause": {"value": "Cable suelto"},
          "serviceCost": {"value": 1500},
          "approved": {"value": false},
          "internalCost": {"value": 1},
          "inventada": {"value": "x"}
        }
        """);

    assertThat(aceptadas).containsOnlyKeys("rootCause", "serviceCost", "approved");
    assertThat(aceptadas.get("approved")).isEqualTo(false);
  }

  @Test
  void elTipoLoFijaElCampoYNoElNavegador() throws Exception {
    Map<String, Object> aceptadas = aceptar("""
        {
          "rootCause": {"value": 42, "type": "Long"},
          "serviceCost": {"value": "1500.0", "type": "String"},
          "approved": {"value": "true", "type": "String"}
        }
        """);

    assertThat(aceptadas.get("rootCause")).isEqualTo("42");
    assertThat(aceptadas.get("serviceCost")).isEqualTo(1500L);
    assertThat(aceptadas.get("approved")).isEqualTo(true);
  }

  @Test
  void unNumeroConDecimalesEsDouble() throws Exception {
    Map<String, Object> aceptadas = aceptar("{\"rootCause\":{\"value\":\"x\"},\"serviceCost\":{\"value\":10.25}}");

    assertThat(aceptadas.get("serviceCost")).isEqualTo(10.25);
  }

  @Test
  void loQueNoSeEnviaNoSeToca() throws Exception {
    Map<String, Object> aceptadas = aceptar("{\"rootCause\":{\"value\":\"x\"}}");

    assertThat(aceptadas).containsOnlyKeys("rootCause");
  }

  @Test
  void unObligatorioAusenteNuloOVacioEs400() {
    for (String enviadas : new String[] {
        "{}",
        "{\"rootCause\":{\"value\":null}}",
        "{\"rootCause\":{\"value\":\"   \"}}"}) {
      assertThatThrownBy(() -> aceptar(enviadas))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("400")
          .hasMessageContaining("Causa raíz");
    }
  }

  @Test
  void validaNumerosLimitesYOpciones() {
    assertThatThrownBy(() -> aceptar("{\"rootCause\":{\"value\":\"x\"},\"serviceCost\":{\"value\":\"mucho\"}}"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("debe ser un número");
    assertThatThrownBy(() -> aceptar("{\"rootCause\":{\"value\":\"x\"},\"serviceCost\":{\"value\":-1}}"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no puede ser menor que 0");
    assertThatThrownBy(() -> aceptar("{\"rootCause\":{\"value\":\"x\"},\"technicalDomain\":{\"value\":\"otro\"}}"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no es una de sus opciones");
  }

  @Test
  void sinFormularioNoSeAceptaNingunaVariable() throws Exception {
    JsonNode enviadas = json.readTree("{\"cualquiera\":{\"value\":1}}");

    assertThat(FormularioTarea.de(null).variablesAceptadas(enviadas)).isEmpty();
  }

  private Map<String, Object> aceptar(String enviadas) throws Exception {
    return FormularioTarea.de(json.readTree(ESQUEMA)).variablesAceptadas(json.readTree(enviadas));
  }
}
