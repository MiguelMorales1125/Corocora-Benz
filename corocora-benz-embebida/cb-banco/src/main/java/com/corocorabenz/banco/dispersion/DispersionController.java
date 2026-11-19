package com.corocorabenz.banco.dispersion;

import com.corocorabenz.banco.dispersion.DispersionService.NuevaDispersion;
import com.corocorabenz.banco.dispersion.DispersionService.ResultadoRespuesta;
import com.corocorabenz.comun.web.ErroresApi;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
 * API del banco. Toda ella exige la credencial interna (propiedad {@code corocora.rutas-internas}).
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
    if (businessKey == null) {
      return repository.findAllByOrderByIdDesc();
    }
    return repository.findByBusinessKeyOrderByIdDesc(businessKey);
  }

  @GetMapping("/{id}")
  public ResponseEntity<Dispersion> ver(@PathVariable Long id) {
    return ResponseEntity.of(repository.findById(id));
  }

  public record Respuesta(String referencia, String codigo) {}

  @PostMapping("/{id}/aceptar")
  public ResponseEntity<?> aceptar(@PathVariable Long id, @RequestBody(required = false) Respuesta r) {
    String referencia = r != null ? r.referencia() : null;
    return responder(id, d -> service.aceptar(d, referencia));
  }

  @PostMapping("/{id}/rechazar")
  public ResponseEntity<?> rechazar(@PathVariable Long id, @RequestBody(required = false) Respuesta r) {
    String codigo = r != null ? r.codigo() : null;
    return responder(id, d -> service.rechazar(d, codigo));
  }

  private ResponseEntity<?> responder(Long id, Function<Dispersion, ResultadoRespuesta> accion) {
    Dispersion d = repository.findById(id).orElse(null);
    if (d == null) {
      return ResponseEntity.notFound().build();
    }
    if (d.getEstado() != EstadoDispersion.RECIBIDA) {
      return error(HttpStatus.CONFLICT, "La dispersión ya está " + d.getEstado());
    }
    ResultadoRespuesta resultado = accion.apply(d);
    return switch (resultado) {
      // Se vuelve a leer: el servicio guarda la respuesta en el registro, no en esta copia.
      case ENTREGADA -> ResponseEntity.of(repository.findById(id));
      case PROCESO_NO_ESPERA -> error(HttpStatus.CONFLICT,
          "El proceso " + d.getBusinessKey() + " todavía no espera la respuesta del banco");
      case SIN_PROCESO -> error(HttpStatus.CONFLICT,
          "El proceso " + d.getBusinessKey() + " ya no existe; la dispersión quedó sin proceso");
      case MOTOR_NO_DISPONIBLE -> error(HttpStatus.BAD_GATEWAY, ErroresApi.MENSAJE_MOTOR_CAIDO);
    };
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus estado, String mensaje) {
    return ResponseEntity.status(estado).body(Map.of("error", mensaje));
  }
}
