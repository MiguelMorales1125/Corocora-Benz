package com.corocorabenz.banco;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.corocorabenz.comun.motor.MotorClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La aplicación arranca con su application.yaml tal cual y con el MotorClient real de cb-comun
 * (sin simular). No se llama al motor: la respuesta automática queda apagada y la base de datos
 * es distinta de la de las demás pruebas.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:cb-banco-arranque;DB_CLOSE_DELAY=-1",
    "corocora.simulacion.respuesta-automatica=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class ArranqueTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MotorClient motor;

  @Autowired
  private CredencialInterna credencial;

  @Test
  void arrancaConElClienteDelMotorDeCbComun() {
    assertThat(motor).isNotNull();
  }

  @Test
  void laApiExigeLaCredencialConfigurada() throws Exception {
    mvc.perform(get("/api/estado"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/estado").header(CredencialInterna.HEADER, credencial.token()))
        .andExpect(status().isOk());
  }
}
