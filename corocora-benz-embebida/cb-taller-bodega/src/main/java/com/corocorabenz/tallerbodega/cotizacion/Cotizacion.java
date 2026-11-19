package com.corocorabenz.tallerbodega.cotizacion;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Cotización de reparación enviada al cliente cuando el servicio no lo cubre la garantía. */
@Entity
@Table(name = "tb_cotizacion")
public class Cotizacion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String businessKey;
  private String vin;
  private String customerName;
  private String symptom;
  private Long monto;
  /** ENVIADA, ACEPTADA, RECHAZADA o VENCIDA; lo actualiza EstadoCotizacionService con el historial del motor. */
  private String estado = "ENVIADA";
  private Instant creadaEn = Instant.now();

  public String getQuoteId() {
    return id == null ? null : "COT-" + id;
  }

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

  public String getCustomerName() {
    return customerName;
  }

  public void setCustomerName(String customerName) {
    this.customerName = customerName;
  }

  public String getSymptom() {
    return symptom;
  }

  public void setSymptom(String symptom) {
    this.symptom = symptom;
  }

  public Long getMonto() {
    return monto;
  }

  public void setMonto(Long monto) {
    this.monto = monto;
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
