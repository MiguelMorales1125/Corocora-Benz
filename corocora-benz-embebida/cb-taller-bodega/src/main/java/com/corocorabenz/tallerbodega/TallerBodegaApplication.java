package com.corocorabenz.tallerbodega;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TallerBodegaApplication {

  public static void main(String... args) {
    SpringApplication.run(TallerBodegaApplication.class, args);
  }
}
