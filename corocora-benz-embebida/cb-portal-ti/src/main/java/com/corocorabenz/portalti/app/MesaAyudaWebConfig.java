package com.corocorabenz.portalti.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Aplica el permiso de empleado a toda la API de la mesa de ayuda. */
@Configuration
public class MesaAyudaWebConfig implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new SoloEmpleadosInterceptor())
        .addPathPatterns("/api/app/ti/**");
  }
}
