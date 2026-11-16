package com.corocorabenz.tallerbodega.repuesto;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Repuesto adicional que el técnico pide a bodega durante el diagnóstico. */
@Entity
@Table(name = "tb_solicitud_repuesto")
public class SolicitudRepuesto {

  public enum Estado { SOLICITADA, ENTREGADA, SIN_PROCESO }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String businessKey;
  private String vin;
  private String partCode;
  @Enumerated(EnumType.STRING)
  private Estado estado = Estado.SOLICITADA;
  private String remision;
  private int intentosRespuesta;
  private Instant solicitadaEn = Instant.now();
  private Instant entregadaEn;

  public String getPartRequestId() { return id == null ? null : "REQ-" + id; }
  public Long getId() { return id; }
  public String getBusinessKey() { return businessKey; }
  public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
  public String getVin() { return vin; }
  public void setVin(String vin) { this.vin = vin; }
  public String getPartCode() { return partCode; }
  public void setPartCode(String partCode) { this.partCode = partCode; }
  public Estado getEstado() { return estado; }
  public void setEstado(Estado estado) { this.estado = estado; }
  public String getRemision() { return remision; }
  public void setRemision(String remision) { this.remision = remision; }
  public int getIntentosRespuesta() { return intentosRespuesta; }
  public void setIntentosRespuesta(int intentosRespuesta) { this.intentosRespuesta = intentosRespuesta; }
  public Instant getSolicitadaEn() { return solicitadaEn; }
  public Instant getEntregadaEn() { return entregadaEn; }
  public void setEntregadaEn(Instant entregadaEn) { this.entregadaEn = entregadaEn; }
}
