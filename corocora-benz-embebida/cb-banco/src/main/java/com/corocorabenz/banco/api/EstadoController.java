package com.corocorabenz.banco.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EstadoController {

  @GetMapping("/api/estado")
  public Map<String, String> estado() {
    return Map.of("sistema", "CB-Banco", "estado", "OK");
  }
}
