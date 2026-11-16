package com.corocorabenz.banco.dispersion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Lote de dispersión de nómina que Corocora Benz envía al banco. */
@Entity
@Table(name = "banco_dispersion")
public class Dispersion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** businessKey del proceso de nómina, para responderle al motor. */
  @Column(nullable = false)
  private String businessKey;

  private String paymentBatchId;
  private String bankAccount;
  private Long payrollTotal;
  private String payPeriodId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EstadoDispersion estado = EstadoDispersion.RECIBIDA;

  private String codigoRespuesta;
  private String referenciaBancaria;
  private int intentosRespuesta;
  private Instant recibidaEn = Instant.now();
  private Instant respondidaEn;

  public Long getId() { return id; }
  public String getBusinessKey() { return businessKey; }
  public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
  public String getPaymentBatchId() { return paymentBatchId; }
  public void setPaymentBatchId(String paymentBatchId) { this.paymentBatchId = paymentBatchId; }
  public String getBankAccount() { return bankAccount; }
  public void setBankAccount(String bankAccount) { this.bankAccount = bankAccount; }
  public Long getPayrollTotal() { return payrollTotal; }
  public void setPayrollTotal(Long payrollTotal) { this.payrollTotal = payrollTotal; }
  public String getPayPeriodId() { return payPeriodId; }
  public void setPayPeriodId(String payPeriodId) { this.payPeriodId = payPeriodId; }
  public EstadoDispersion getEstado() { return estado; }
  public void setEstado(EstadoDispersion estado) { this.estado = estado; }
  public String getCodigoRespuesta() { return codigoRespuesta; }
  public void setCodigoRespuesta(String codigoRespuesta) { this.codigoRespuesta = codigoRespuesta; }
  public String getReferenciaBancaria() { return referenciaBancaria; }
  public void setReferenciaBancaria(String referenciaBancaria) { this.referenciaBancaria = referenciaBancaria; }
  public int getIntentosRespuesta() { return intentosRespuesta; }
  public void setIntentosRespuesta(int intentosRespuesta) { this.intentosRespuesta = intentosRespuesta; }
  public Instant getRecibidaEn() { return recibidaEn; }
  public Instant getRespondidaEn() { return respondidaEn; }
  public void setRespondidaEn(Instant respondidaEn) { this.respondidaEn = respondidaEn; }
}
