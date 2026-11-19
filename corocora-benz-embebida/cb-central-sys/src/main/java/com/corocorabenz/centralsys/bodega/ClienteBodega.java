package com.corocorabenz.centralsys.bodega;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Llamadas de CentralSys a Taller-Bodega, con la credencial interna. Traduce sus respuestas
 * a los códigos del contrato: 409 y 404 se conservan; cualquier otro fallo es 502. Si el 502 es
 * de la propia bodega (su motor no responde), se conserva también su mensaje.
 */
@Component
public class ClienteBodega {

  static final String MENSAJE_SIN_RESPUESTA = "Taller-Bodega no responde";
  static final String MENSAJE_RECHAZO = "Taller-Bodega rechazó la operación";
  static final String MENSAJE_CONFLICTO = "La bodega no puede hacer esa operación en este momento";

  private static final Logger LOG = LoggerFactory.getLogger(ClienteBodega.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final RestClient rest;

  @Autowired
  public ClienteBodega(CredencialInterna credencial, @Value("${corocora.taller-bodega}") String url) {
    this(credencial.clienteRest(url));
  }

  /** Para pruebas: permite inyectar un RestClient simulado. */
  ClienteBodega(RestClient rest) {
    this.rest = rest;
  }

  public JsonNode reservas() {
    return ejecutar(HttpMethod.GET, "/api/reservas", null, null, null);
  }

  public JsonNode avisarDisponibilidad(String reservaId) {
    return ejecutar(
        HttpMethod.POST, "/api/reservas/{id}/disponibles", reservaId, null, "La reserva no existe");
  }

  public JsonNode solicitudes() {
    return ejecutar(HttpMethod.GET, "/api/solicitudes-repuesto", null, null, null);
  }

  public JsonNode entregar(String solicitudId) {
    return ejecutar(
        HttpMethod.POST,
        "/api/solicitudes-repuesto/{id}/entregar",
        solicitudId,
        null,
        "La solicitud de repuesto no existe");
  }

  public JsonNode simulacion() {
    return ejecutar(HttpMethod.GET, "/api/simulacion", null, null, null);
  }

  public JsonNode cambiarSimulacion(Object cambio) {
    return ejecutar(HttpMethod.PUT, "/api/simulacion", null, cambio, null);
  }

  private JsonNode ejecutar(HttpMethod metodo, String ruta, String id, Object cuerpo, String mensajeNoExiste) {
    RestClient.RequestBodySpec peticion = id == null
        ? rest.method(metodo).uri(ruta)
        : rest.method(metodo).uri(ruta, id);
    peticion.accept(MediaType.APPLICATION_JSON);
    if (cuerpo != null) {
      peticion.contentType(MediaType.APPLICATION_JSON);
      peticion.body(cuerpo);
    }
    Respuesta respuesta;
    try {
      // exchange y no retrieve: aquí interesa leer el código y el cuerpo también cuando es un error.
      respuesta = peticion.exchange((request, response) ->
          new Respuesta(response.getStatusCode().value(), response.getBody().readAllBytes()));
    } catch (ResourceAccessException e) {
      LOG.warn("Taller-Bodega no respondió a {} {}: {}", metodo, ruta, e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, MENSAJE_SIN_RESPUESTA);
    }
    return interpretar(respuesta, metodo, ruta, mensajeNoExiste);
  }

  private static JsonNode interpretar(Respuesta respuesta, HttpMethod metodo, String ruta, String mensajeNoExiste) {
    int estado = respuesta.estado();
    if (estado >= 200 && estado < 300) {
      JsonNode cuerpo = leerJson(respuesta.cuerpo());
      if (cuerpo == null) {
        LOG.warn("Taller-Bodega respondió algo que no es JSON a {} {}", metodo, ruta);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, MENSAJE_RECHAZO);
      }
      return cuerpo;
    }
    if (estado == HttpStatus.CONFLICT.value()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, mensajeDe(respuesta.cuerpo(), MENSAJE_CONFLICTO));
    }
    if (estado == HttpStatus.NOT_FOUND.value() && mensajeNoExiste != null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, mensajeNoExiste);
    }
    if (estado == HttpStatus.BAD_GATEWAY.value()) {
      // La bodega sí respondió: quien falla es el sistema del que ella depende, y su mensaje lo dice.
      LOG.warn("Taller-Bodega respondió 502 a {} {}", metodo, ruta);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, mensajeDe(respuesta.cuerpo(), MENSAJE_RECHAZO));
    }
    // Un 401 aquí significa que la credencial interna está mal configurada: no es culpa de la persona.
    LOG.warn("Taller-Bodega respondió {} a {} {}", estado, metodo, ruta);
    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, MENSAJE_RECHAZO);
  }

  /** @return el JSON, un objeto vacío si no hay cuerpo, o {@code null} si no es JSON */
  private static JsonNode leerJson(byte[] cuerpo) {
    if (cuerpo == null || cuerpo.length == 0) {
      return JsonNodeFactory.instance.objectNode();
    }
    try {
      return JSON.readTree(cuerpo);
    } catch (IOException e) {
      return null;
    }
  }

  /** @return el campo {@code error} del cuerpo, o {@code porDefecto} si no lo trae */
  private static String mensajeDe(byte[] cuerpo, String porDefecto) {
    JsonNode json = leerJson(cuerpo);
    if (json != null && json.path("error").isTextual() && !json.path("error").asText().isBlank()) {
      return json.path("error").asText();
    }
    return porDefecto;
  }

  private record Respuesta(int estado, byte[] cuerpo) {
  }
}
