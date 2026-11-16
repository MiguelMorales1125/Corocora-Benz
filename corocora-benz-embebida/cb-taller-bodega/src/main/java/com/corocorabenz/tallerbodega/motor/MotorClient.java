package com.corocorabenz.tallerbodega.motor;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Cliente de la REST API de Camunda en CB-BPM-Engine. */
@Component
public class MotorClient {

  private static final Logger LOG = LoggerFactory.getLogger(MotorClient.class);

  private final RestClient rest;

  public MotorClient(RestClient.Builder builder, @Value("${corocora.engine-rest}") String engineRest) {
    this.rest = builder.baseUrl(engineRest).build();
  }

  /**
   * Envía un mensaje al proceso con esa businessKey ({@code POST /message}).
   *
   * @return false si el proceso todavía no está esperando el mensaje o el motor no responde.
   */
  public boolean enviarMensaje(String mensaje, String businessKey, Map<String, Object> variables) {
    Map<String, Object> tipadas = new LinkedHashMap<>();
    variables.forEach((nombre, valor) -> tipadas.put(nombre, Map.of("value", valor, "type", tipo(valor))));
    try {
      rest.post().uri("/message")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("messageName", mensaje, "businessKey", businessKey, "processVariables", tipadas))
          .retrieve()
          .toBodilessEntity();
      LOG.info("Mensaje {} enviado al motor para {}", mensaje, businessKey);
      return true;
    } catch (RestClientException e) {
      LOG.debug("El motor no aceptó {} para {}: {}", mensaje, businessKey, e.getMessage());
      return false;
    }
  }

  private static String tipo(Object valor) {
    if (valor instanceof Boolean) return "Boolean";
    if (valor instanceof Integer || valor instanceof Long) return "Long";
    if (valor instanceof Number) return "Double";
    return "String";
  }
}
