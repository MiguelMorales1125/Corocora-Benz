package com.corocorabenz.portalti.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Ticket de un incidente de TI. Lo crea el motor al arrancar el proceso
 * {@code CB_GestionIncidenteTI}; hay uno por businessKey.
 */
@Entity
@Table(name = "ti_ticket", uniqueConstraints = @UniqueConstraint(columnNames = "businessKey"))
public class Ticket {

  public static final String ABIERTO = "OPEN";
  public static final String RESUELTO = "RESOLVED";
  public static final String CERRADO = "CLOSED";

  private static final String PREFIJO_ID = "TI-";
  private static final Pattern FORMATO_ID = Pattern.compile("TI-\\d{1,18}");

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String businessKey;

  private String requesterId;

  private String systemName;

  @Column(length = 4000)
  private String description;

  private String impact;

  private String urgency;

  private Boolean safetyImpact;

  private Integer affectedTeams;

  private String status = ABIERTO;

  @Column(length = 4000)
  private String rootCause;

  @Column(length = 4000)
  private String resolutionSummary;

  /**
   * Convierte el identificador público ({@code TI-15}) en el id de la tabla.
   * Devuelve vacío si el texto no tiene esa forma, para que quien llama responda 404.
   */
  public static Optional<Long> idInterno(String ticketId) {
    if (ticketId == null || !FORMATO_ID.matcher(ticketId).matches()) {
      return Optional.empty();
    }
    return Optional.of(Long.parseLong(ticketId.substring(PREFIJO_ID.length())));
  }

  public Long getId() {
    return id;
  }

  /** Identificador que ven las personas y que el proceso guarda en la variable {@code ticketId}. */
  public String getTicketId() {
    return PREFIJO_ID + id;
  }

  public String getBusinessKey() {
    return businessKey;
  }

  public void setBusinessKey(String businessKey) {
    this.businessKey = businessKey;
  }

  public String getRequesterId() {
    return requesterId;
  }

  public void setRequesterId(String requesterId) {
    this.requesterId = requesterId;
  }

  public String getSystemName() {
    return systemName;
  }

  public void setSystemName(String systemName) {
    this.systemName = systemName;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getImpact() {
    return impact;
  }

  public void setImpact(String impact) {
    this.impact = impact;
  }

  public String getUrgency() {
    return urgency;
  }

  public void setUrgency(String urgency) {
    this.urgency = urgency;
  }

  public Boolean getSafetyImpact() {
    return safetyImpact;
  }

  public void setSafetyImpact(Boolean safetyImpact) {
    this.safetyImpact = safetyImpact;
  }

  public Integer getAffectedTeams() {
    return affectedTeams;
  }

  public void setAffectedTeams(Integer affectedTeams) {
    this.affectedTeams = affectedTeams;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getRootCause() {
    return rootCause;
  }

  public void setRootCause(String rootCause) {
    this.rootCause = rootCause;
  }

  public String getResolutionSummary() {
    return resolutionSummary;
  }

  public void setResolutionSummary(String resolutionSummary) {
    this.resolutionSummary = resolutionSummary;
  }
}
