package com.corocorabenz.centralsys.bodega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Cómo se traducen las respuestas de Taller-Bodega a los códigos del contrato. */
class ClienteBodegaTest {

  private static final String BODEGA = "http://bodega.test";

  private MockRestServiceServer servidor;
  private ClienteBodega cliente;

  @BeforeEach
  void preparar() {
    RestClient.Builder constructor = RestClient.builder()
        .baseUrl(BODEGA)
        .defaultHeader("X-CB-Internal", "token-de-prueba");
    servidor = MockRestServiceServer.bindTo(constructor).build();
    cliente = new ClienteBodega(constructor.build());
  }

  @Test
  void listaLasReservasConLaCredencialInterna() {
    servidor.expect(requestTo(BODEGA + "/api/reservas"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-CB-Internal", "token-de-prueba"))
        .andRespond(withSuccess("[{\"id\":7,\"estado\":\"PENDIENTE\"}]", MediaType.APPLICATION_JSON));

    JsonNode reservas = cliente.reservas();

    assertThat(reservas.get(0).get("estado").asText()).isEqualTo("PENDIENTE");
    servidor.verify();
  }

  @Test
  void usaLasRutasDeTallerBodega() {
    servidor.expect(requestTo(BODEGA + "/api/reservas/7/disponibles"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":7,\"estado\":\"RESERVED\"}", MediaType.APPLICATION_JSON));
    servidor.expect(requestTo(BODEGA + "/api/solicitudes-repuesto"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor.expect(requestTo(BODEGA + "/api/solicitudes-repuesto/3/entregar"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":3,\"estado\":\"ENTREGADA\"}", MediaType.APPLICATION_JSON));
    servidor.expect(requestTo(BODEGA + "/api/simulacion"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"respuestaAutomatica\":true}", MediaType.APPLICATION_JSON));
    servidor.expect(requestTo(BODEGA + "/api/simulacion"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().json("{\"respuestaAutomatica\":false}"))
        .andRespond(withSuccess("{\"respuestaAutomatica\":false}", MediaType.APPLICATION_JSON));

    assertThat(cliente.avisarDisponibilidad("7").get("estado").asText()).isEqualTo("RESERVED");
    assertThat(cliente.solicitudes().isArray()).isTrue();
    assertThat(cliente.entregar("3").get("estado").asText()).isEqualTo("ENTREGADA");
    assertThat(cliente.simulacion().get("respuestaAutomatica").asBoolean()).isTrue();
    assertThat(cliente.cambiarSimulacion(Map.of("respuestaAutomatica", false)).get("respuestaAutomatica").asBoolean())
        .isFalse();
    servidor.verify();
  }

  @Test
  void el409DeLaBodegaSeConservaConSuMensaje() {
    servidor.expect(requestTo(BODEGA + "/api/reservas/7/disponibles"))
        .andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"El proceso todavía no espera el aviso de Bodega\"}"));

    ResponseStatusException fallo = catchThrowableOfType(
        ResponseStatusException.class, () -> cliente.avisarDisponibilidad("7"));

    assertThat(fallo.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(fallo.getReason()).isEqualTo("El proceso todavía no espera el aviso de Bodega");
  }

  @Test
  void el404DeLaBodegaSeConserva() {
    servidor.expect(requestTo(BODEGA + "/api/solicitudes-repuesto/99/entregar"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    ResponseStatusException fallo = catchThrowableOfType(
        ResponseStatusException.class, () -> cliente.entregar("99"));

    assertThat(fallo.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(fallo.getReason()).isEqualTo("La solicitud de repuesto no existe");
  }

  @Test
  void cualquierOtroErrorDeLaBodegaEs502() {
    servidor.expect(requestTo(BODEGA + "/api/reservas"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"Credencial interna requerida\"}"));
    servidor.expect(requestTo(BODEGA + "/api/simulacion"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    ResponseStatusException sinCredencial = catchThrowableOfType(
        ResponseStatusException.class, () -> cliente.reservas());
    ResponseStatusException errorInterno = catchThrowableOfType(
        ResponseStatusException.class, () -> cliente.simulacion());

    assertThat(sinCredencial.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(sinCredencial.getReason()).isEqualTo(ClienteBodega.MENSAJE_RECHAZO);
    assertThat(errorInterno.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
  }

  @Test
  void el502DeLaBodegaConservaSuMensaje() {
    servidor.expect(requestTo(BODEGA + "/api/reservas/7/disponibles"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"El motor de procesos no responde\"}"));
    servidor.expect(requestTo(BODEGA + "/api/reservas"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

    ResponseStatusException motorCaido = catchThrowableOfType(
        ResponseStatusException.class, () -> cliente.avisarDisponibilidad("7"));
    ResponseStatusException sinMensaje = catchThrowableOfType(
        ResponseStatusException.class, () -> cliente.reservas());

    assertThat(motorCaido.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(motorCaido.getReason()).isEqualTo("El motor de procesos no responde");
    assertThat(sinMensaje.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(sinMensaje.getReason()).isEqualTo(ClienteBodega.MENSAJE_RECHAZO);
  }

  @Test
  void siLaBodegaNoRespondeEs502() {
    servidor.expect(requestTo(BODEGA + "/api/reservas"))
        .andRespond(withException(new IOException("Conexión rechazada")));

    ResponseStatusException fallo = catchThrowableOfType(
        ResponseStatusException.class, () -> cliente.reservas());

    assertThat(fallo.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(fallo.getReason()).isEqualTo(ClienteBodega.MENSAJE_SIN_RESPUESTA);
  }
}
