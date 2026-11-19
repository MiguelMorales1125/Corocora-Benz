package com.corocorabenz.centralsys.bodega;

import com.corocorabenz.centralsys.actividad.Accion;
import com.corocorabenz.centralsys.actividad.RegistroActividad;
import com.corocorabenz.comun.portal.Permisos;
import com.corocorabenz.comun.portal.UsuarioPortal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Panel de la bodega: solo el grupo {@code bodega} o un admin. El navegador nunca llama a
 * Taller-Bodega; estas rutas reenvían la petición con la credencial interna.
 */
@RestController
@RequestMapping("/api/app/bodega")
public class BodegaController {

  private static final Pattern ID_NUMERICO = Pattern.compile("\\d{1,18}");
  private static final int RETARDO_MAXIMO_SEGUNDOS = 3600;

  private final ClienteBodega bodega;
  private final RegistroActividad registro;

  public BodegaController(ClienteBodega bodega, RegistroActividad registro) {
    this.bodega = bodega;
    this.registro = registro;
  }

  public record CambioSimulacion(Boolean respuestaAutomatica, Integer retardoSegundos, Boolean repuestosDisponibles) {
  }

  @GetMapping("/reservas")
  public JsonNode reservas(@AuthenticationPrincipal UsuarioPortal usuario) {
    exigirBodega(usuario);
    return bodega.reservas();
  }

  @PostMapping("/reservas/{id}/disponibles")
  public JsonNode avisarDisponibilidad(@PathVariable String id, @AuthenticationPrincipal UsuarioPortal usuario) {
    exigirBodega(usuario);
    exigirIdNumerico(id, "La reserva no existe");
    JsonNode reserva = bodega.avisarDisponibilidad(id);
    registro.general(usuario, Accion.AVISAR_DISPONIBILIDAD, businessKeyDe(reserva), "Reserva " + id);
    return reserva;
  }

  @GetMapping("/solicitudes")
  public JsonNode solicitudes(@AuthenticationPrincipal UsuarioPortal usuario) {
    exigirBodega(usuario);
    return bodega.solicitudes();
  }

  @PostMapping("/solicitudes/{id}/entregar")
  public JsonNode entregar(@PathVariable String id, @AuthenticationPrincipal UsuarioPortal usuario) {
    exigirBodega(usuario);
    exigirIdNumerico(id, "La solicitud de repuesto no existe");
    JsonNode solicitud = bodega.entregar(id);
    registro.general(usuario, Accion.ENTREGAR_REPUESTO, businessKeyDe(solicitud), "Solicitud " + id);
    return solicitud;
  }

  @GetMapping("/simulacion")
  public JsonNode simulacion(@AuthenticationPrincipal UsuarioPortal usuario) {
    exigirBodega(usuario);
    return bodega.simulacion();
  }

  @PutMapping("/simulacion")
  public JsonNode cambiarSimulacion(
      @RequestBody(required = false) CambioSimulacion cambio, @AuthenticationPrincipal UsuarioPortal usuario) {
    exigirBodega(usuario);
    Map<String, Object> cambios = cambiosDe(cambio);
    JsonNode simulacion = bodega.cambiarSimulacion(cambios);
    registro.general(usuario, Accion.CAMBIAR_SIMULACION, null, describir(cambios));
    return simulacion;
  }

  private static void exigirBodega(UsuarioPortal usuario) {
    Permisos.exigirEmpleado(usuario);
    Permisos.exigirGrupo(usuario, UsuarioPortal.GRUPO_BODEGA);
  }

  /** Los ids de la bodega son números: cualquier otra cosa no puede existir y no se reenvía. */
  private static void exigirIdNumerico(String id, String mensaje) {
    if (!ID_NUMERICO.matcher(id).matches()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, mensaje);
    }
  }

  /** Solo se reenvían los campos conocidos y que vinieron con valor. */
  private static Map<String, Object> cambiosDe(CambioSimulacion cambio) {
    Map<String, Object> cambios = new LinkedHashMap<>();
    if (cambio == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica qué cambiar en la simulación");
    }
    if (cambio.respuestaAutomatica() != null) {
      cambios.put("respuestaAutomatica", cambio.respuestaAutomatica());
    }
    if (cambio.retardoSegundos() != null) {
      if (cambio.retardoSegundos() < 0 || cambio.retardoSegundos() > RETARDO_MAXIMO_SEGUNDOS) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "El retardo debe estar entre 0 y " + RETARDO_MAXIMO_SEGUNDOS + " segundos");
      }
      cambios.put("retardoSegundos", cambio.retardoSegundos());
    }
    if (cambio.repuestosDisponibles() != null) {
      cambios.put("repuestosDisponibles", cambio.repuestosDisponibles());
    }
    if (cambios.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica qué cambiar en la simulación");
    }
    return cambios;
  }

  private static String describir(Map<String, Object> cambios) {
    List<String> partes = new ArrayList<>();
    for (Map.Entry<String, Object> cambio : cambios.entrySet()) {
      partes.add(describir(cambio.getKey(), cambio.getValue()));
    }
    return String.join(", ", partes);
  }

  /** Para «Mi actividad reciente»: la persona lee una frase, no el nombre interno del campo. */
  private static String describir(String campo, Object valor) {
    return switch (campo) {
      case "respuestaAutomatica" -> "Respuesta automática: " + siONo(valor);
      case "repuestosDisponibles" -> "Repuestos disponibles al reservar: " + siONo(valor);
      case "retardoSegundos" -> "Retardo: " + valor + " s";
      default -> campo + ": " + valor;
    };
  }

  private static String siONo(Object valor) {
    return Boolean.TRUE.equals(valor) ? "sí" : "no";
  }

  private static String businessKeyDe(JsonNode respuesta) {
    return respuesta == null ? null : respuesta.path("businessKey").asText(null);
  }
}
