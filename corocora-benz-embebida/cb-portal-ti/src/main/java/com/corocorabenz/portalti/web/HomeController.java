package com.corocorabenz.portalti.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sirve la página de la mesa de ayuda. Los datos los pide después el navegador a /api/app/ti. */
@Controller
public class HomeController {

  @GetMapping("/")
  public String home() {
    return "index";
  }
}
