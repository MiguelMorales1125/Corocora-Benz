package com.corocorabenz.centralsys.actividad;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Una acción hecha por un empleado en la intranet (sección 5.3 del contrato). */
@Entity
@Table(name = "cs_actividad", indexes = @Index(name = "cs_actividad_usuario", columnList = "usuario, fecha"))
public class Actividad {

  static final int LARGO_TEXTO = 255;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = LARGO_TEXTO)
  private String usuario;

  @Column(nullable = false, length = 40)
  private String accion;

  /** Nombre de la tarea, cuando la acción es sobre una tarea. */
  @Column(length = LARGO_TEXTO)
  private String tarea;

  @Column(length = 64)
  private String tareaId;

  @Column(length = LARGO_TEXTO)
  private String businessKey;

  /** Dato adicional legible: la reserva avisada, el nuevo responsable, etc. */
  @Column(length = LARGO_TEXTO)
  private String detalle;

  @Column(nullable = false)
  private Instant fecha;

  protected Actividad() {
    // Lo exige JPA.
  }

  Actividad(String usuario, String accion, String tarea, String tareaId, String businessKey, String detalle) {
    this.usuario = usuario;
    this.accion = accion;
    this.tarea = tarea;
    this.tareaId = tareaId;
    this.businessKey = businessKey;
    this.detalle = detalle;
    this.fecha = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getUsuario() {
    return usuario;
  }

  public String getAccion() {
    return accion;
  }

  public String getTarea() {
    return tarea;
  }

  public String getTareaId() {
    return tareaId;
  }

  public String getBusinessKey() {
    return businessKey;
  }

  public String getDetalle() {
    return detalle;
  }

  public Instant getFecha() {
    return fecha;
  }
}
