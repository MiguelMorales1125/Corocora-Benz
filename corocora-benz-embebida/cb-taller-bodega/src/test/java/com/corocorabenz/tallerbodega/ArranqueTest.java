package com.corocorabenz.tallerbodega;

import static org.assertj.core.api.Assertions.assertThat;

import com.corocorabenz.comun.credencial.FiltroCredencialInterna;
import com.corocorabenz.comun.motor.MotorClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * La aplicación arranca con su configuración real (sin simular el motor): el cliente del motor
 * es el de cb-comun y toda la API queda detrás de la credencial interna. No abre ningún puerto
 * ni llama al motor, porque no hay solicitudes pendientes y la entrega automática está apagada.
 */
@SpringBootTest(properties = "corocora.simulacion.respuesta-automatica=false")
@ActiveProfiles("h2")
class ArranqueTest {

  @Autowired
  private ApplicationContext aplicacion;

  @Test
  void usaElClienteDelMotorDeCbComun() {
    assertThat(aplicacion.getBeansOfType(MotorClient.class)).hasSize(1);
  }

  @Test
  void todaLaApiExigeLaCredencialInterna() {
    FilterRegistrationBean<?> registro = aplicacion.getBean("filtroCredencialInterna", FilterRegistrationBean.class);

    assertThat(registro.getFilter()).isInstanceOf(FiltroCredencialInterna.class);
    assertThat(registro.getUrlPatterns()).containsExactly("/api/*");
    assertThat(registro.isEnabled()).isTrue();
  }
}
