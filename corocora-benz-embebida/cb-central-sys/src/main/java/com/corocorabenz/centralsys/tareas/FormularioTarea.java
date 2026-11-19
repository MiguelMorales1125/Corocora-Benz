package com.corocorabenz.centralsys.tareas;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Campos editables del formulario desplegado de una tarea (formato de Camunda Forms / form-js).
 * Decide qué variables se aceptan al completar la tarea y con qué tipo: el navegador
 * no puede escribir variables que el formulario no deja editar.
 */
final class FormularioTarea {

  private static final String TIPO_NUMERO = "number";
  private static final String TIPO_CASILLA = "checkbox";
  private static final String TIPO_LISTA = "select";
  private static final String TIPO_RADIO = "radio";

  // Componentes que no guardan un valor escrito por la persona aunque tengan "key".
  private static final Set<String> TIPOS_SIN_VALOR = Set.of(
      "text", "button", "spacer", "separator", "image", "html", "iframe", "table",
      "expression", "group", "dynamiclist");

  private final List<Campo> editables;

  private FormularioTarea(List<Campo> editables) {
    this.editables = editables;
  }

  /** @param esquema JSON del formulario, o {@code null} si la tarea no tiene formulario */
  static FormularioTarea de(JsonNode esquema) {
    Map<String, Campo> campos = new LinkedHashMap<>();
    if (esquema != null) {
      recorrer(esquema.path("components"), campos);
    }
    return new FormularioTarea(new ArrayList<>(campos.values()));
  }

  /**
   * Filtra, tipa y valida lo que envió el navegador.
   *
   * @param enviadas variables en formato Camunda {@code {nombre: {value, type}}}; el tipo que
   *     diga el navegador se ignora
   * @return valores listos para {@code MotorClient.tipar}; solo de campos editables
   * @throws ResponseStatusException 400 si falta un campo obligatorio o un valor no es válido
   */
  Map<String, Object> variablesAceptadas(JsonNode enviadas) {
    Map<String, Object> aceptadas = new LinkedHashMap<>();
    for (Campo campo : editables) {
      JsonNode entrada = enviadas == null ? null : enviadas.get(campo.key());
      Object valor = entrada == null ? null : campo.convertir(valorDe(entrada));
      if (campo.obligatorio() && estaVacio(valor)) {
        throw invalido("Falta el campo obligatorio «" + campo.etiqueta() + "»");
      }
      if (entrada == null) {
        // Lo que no se envió no se toca: la variable conserva el valor que tenga en el proceso.
        continue;
      }
      campo.validar(valor);
      aceptadas.put(campo.key(), valor);
    }
    return aceptadas;
  }

  /** Recorre también los componentes anidados (grupos y listas). */
  private static void recorrer(JsonNode componentes, Map<String, Campo> destino) {
    if (!componentes.isArray()) {
      return;
    }
    for (JsonNode componente : componentes) {
      if (esEditable(componente)) {
        Campo campo = Campo.de(componente);
        destino.putIfAbsent(campo.key(), campo);
      }
      recorrer(componente.path("components"), destino);
    }
  }

  private static boolean esEditable(JsonNode componente) {
    String clave = Tarea.texto(componente, "key");
    String tipo = Tarea.texto(componente, "type");
    if (clave == null || tipo == null || TIPOS_SIN_VALOR.contains(tipo)) {
      return false;
    }
    if (componente.path("disabled").asBoolean(false)) {
      return false;
    }
    // "readonly" también puede ser una expresión FEEL que aquí no se evalúa: solo cuenta el literal.
    return !componente.path("readonly").isBoolean() || !componente.path("readonly").booleanValue();
  }

  private static JsonNode valorDe(JsonNode entrada) {
    if (entrada.isObject()) {
      return entrada.path("value");
    }
    return entrada;
  }

  private static boolean estaVacio(Object valor) {
    return valor == null || (valor instanceof String texto && texto.isBlank());
  }

  private static ResponseStatusException invalido(String mensaje) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensaje);
  }

  /** Un campo editable del formulario. */
  record Campo(
      String key,
      String etiqueta,
      String tipo,
      boolean obligatorio,
      BigDecimal minimo,
      BigDecimal maximo,
      Set<String> opciones) {

    static Campo de(JsonNode componente) {
      String clave = Tarea.texto(componente, "key");
      String etiqueta = Tarea.texto(componente, "label");
      JsonNode reglas = componente.path("validate");
      return new Campo(
          clave,
          etiqueta != null ? etiqueta : clave,
          Tarea.texto(componente, "type"),
          reglas.path("required").asBoolean(false),
          limite(reglas.path("min")),
          limite(reglas.path("max")),
          opcionesDe(componente.path("values")));
    }

    /** number -> Long (o Double si tiene decimales), checkbox -> Boolean, lo demás -> String. */
    Object convertir(JsonNode valor) {
      if (valor.isMissingNode() || valor.isNull()) {
        return null;
      }
      if (TIPO_NUMERO.equals(tipo)) {
        return numero(valor);
      }
      if (TIPO_CASILLA.equals(tipo)) {
        return casilla(valor);
      }
      return valor.isValueNode() ? valor.asText() : valor.toString();
    }

    void validar(Object valor) {
      if (valor == null) {
        return;
      }
      if (valor instanceof Number numero) {
        BigDecimal decimal = new BigDecimal(numero.toString());
        if (minimo != null && decimal.compareTo(minimo) < 0) {
          throw invalido("El campo «" + etiqueta + "» no puede ser menor que " + minimo.toPlainString());
        }
        if (maximo != null && decimal.compareTo(maximo) > 0) {
          throw invalido("El campo «" + etiqueta + "» no puede ser mayor que " + maximo.toPlainString());
        }
      }
      if (valor instanceof String texto && tieneOpcionesFijas() && !texto.isEmpty() && !opciones.contains(texto)) {
        throw invalido("El valor del campo «" + etiqueta + "» no es una de sus opciones");
      }
    }

    private Object numero(JsonNode valor) {
      String texto = valor.asText().trim();
      if (texto.isEmpty()) {
        return null;
      }
      BigDecimal decimal;
      try {
        decimal = new BigDecimal(texto);
      } catch (NumberFormatException e) {
        throw invalido("El campo «" + etiqueta + "» debe ser un número");
      }
      if (decimal.stripTrailingZeros().scale() > 0) {
        return comoDouble(decimal);
      }
      try {
        return decimal.longValueExact();
      } catch (ArithmeticException e) {
        // Entero que no cabe en un Long.
        return comoDouble(decimal);
      }
    }

    private Double comoDouble(BigDecimal decimal) {
      double valor = decimal.doubleValue();
      if (Double.isInfinite(valor)) {
        throw invalido("El campo «" + etiqueta + "» tiene un número demasiado grande");
      }
      return valor;
    }

    /** Solo las listas de una sola respuesta: en las de varias el valor es un arreglo. */
    private boolean tieneOpcionesFijas() {
      return !opciones.isEmpty() && (TIPO_LISTA.equals(tipo) || TIPO_RADIO.equals(tipo));
    }

    private Object casilla(JsonNode valor) {
      if (valor.isBoolean()) {
        return valor.booleanValue();
      }
      String texto = valor.asText().trim();
      if (texto.equalsIgnoreCase("true")) {
        return Boolean.TRUE;
      }
      if (texto.equalsIgnoreCase("false")) {
        return Boolean.FALSE;
      }
      throw invalido("El campo «" + etiqueta + "» debe ser verdadero o falso");
    }

    private static BigDecimal limite(JsonNode nodo) {
      if (!nodo.isNumber()) {
        return null;
      }
      return nodo.decimalValue();
    }

    /** Valores fijos de una lista desplegable o de opciones; vacío si no los declara. */
    private static Set<String> opcionesDe(JsonNode valores) {
      Set<String> opciones = new LinkedHashSet<>();
      if (!valores.isArray()) {
        return opciones;
      }
      for (JsonNode opcion : valores) {
        JsonNode valor = opcion.path("value");
        if (valor.isValueNode() && !valor.isNull()) {
          opciones.add(valor.asText());
        }
      }
      return opciones;
    }
  }
}
