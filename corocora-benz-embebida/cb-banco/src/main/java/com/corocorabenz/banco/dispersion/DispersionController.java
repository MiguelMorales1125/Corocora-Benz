package com.corocorabenz.banco.dispersion;

import com.corocorabenz.banco.dispersion.DispersionService.NuevaDispersion;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API del banco.
 * <ul>
 *   <li>{@code POST /api/dispersiones}: lo llama el motor (HTTP Connector de "Enviar dispersión al Banco").</li>
 *   <li>{@code POST /api/dispersiones/{id}/aceptar|rechazar}: respuesta manual del banco.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dispersiones")
public class DispersionController {

  private final DispersionRepository repository;
  private final DispersionService service;

  public DispersionController(DispersionRepository repository, DispersionService service) {
    this.repository = repository;
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> recibir(@RequestBody NuevaDispersion nueva) {
    if (nueva.businessKey() == null || nueva.businessKey().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Falta businessKey"));
    }
    Dispersion d = service.recibir(nueva);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("dispersionId", d.getId(), "estado", d.getEstado().name(), "recibida", true));
  }

  @GetMapping
  public List<Dispersion> listar(@RequestParam(required = false) String businessKey) {
    return businessKey == null ? repository.findAllByOrderByIdDesc()
        : repository.findByBusinessKeyOrderByIdDesc(businessKey);
  }

  @GetMapping("/{id}")
  public ResponseEntity<Dispersion> ver(@PathVariable Long id) {
    return ResponseEntity.of(repository.findById(id));
  }

  public record Respuesta(String referencia, String codigo) {}

  @PostMapping("/{id}/aceptar")
  public ResponseEntity<?> aceptar(@PathVariable Long id, @RequestBody(required = false) Respuesta r) {
    return responder(id, d -> service.aceptar(d, r != null ? r.referencia() : null));
  }

  @PostMapping("/{id}/rechazar")
  public ResponseEntity<?> rechazar(@PathVariable Long id, @RequestBody(required = false) Respuesta r) {
    return responder(id, d -> service.rechazar(d, r != null ? r.codigo() : null));
  }

  private ResponseEntity<?> responder(Long id, java.util.function.Predicate<Dispersion> accion) {
    Dispersion d = repository.findById(id).orElse(null);
    if (d == null) {
      return ResponseEntity.notFound().build();
    }
    if (d.getEstado() != EstadoDispersion.RECIBIDA) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", "La dispersión ya está " + d.getEstado()));
    }
    if (!accion.test(d)) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", "El proceso " + d.getBusinessKey() + " todavía no espera la respuesta del banco"));
    }
    return ResponseEntity.ok(d);
  }
}
