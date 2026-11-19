package com.corocorabenz.tallerbodega.repuesto;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Reserva de los repuestos de la reparación, antes de la cita en el taller. */
@Entity
@Table(name = "tb_reserva")
public class Reserva {

  /** Los repuestos están apartados. El BPMN compara este valor tal cual. */
  public static final String RESERVADA = "RESERVED";
  /** Faltan repuestos y el proceso espera el aviso RepuestosDisponibles. El BPMN compara este valor tal cual. */
  public static final String PENDIENTE = "PENDIENTE";
  /** Los repuestos llegaron cuando el proceso que los esperaba ya no existía. */
  public static final String SIN_PROCESO = "SIN_PROCESO";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String businessKey;
  private String vin;
  private String estado = RESERVADA;
  private Instant creadaEn = Instant.now();

  public Long getId() {
    return id;
  }

  public String getBusinessKey() {
    return businessKey;
  }

  public void setBusinessKey(String businessKey) {
    this.businessKey = businessKey;
  }

  public String getVin() {
    return vin;
  }

  public void setVin(String vin) {
    this.vin = vin;
  }

  public String getEstado() {
    return estado;
  }

  public void setEstado(String estado) {
    this.estado = estado;
  }

  public Instant getCreadaEn() {
    return creadaEn;
  }
}
