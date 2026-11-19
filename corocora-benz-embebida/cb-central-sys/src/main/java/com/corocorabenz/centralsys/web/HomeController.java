package com.corocorabenz.centralsys.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sirve la página de la intranet. Los datos los pide el navegador a /api/app/**. */
@Controller
public class HomeController {

  @GetMapping("/")
  public String home() {
    return "index";
  }
}
