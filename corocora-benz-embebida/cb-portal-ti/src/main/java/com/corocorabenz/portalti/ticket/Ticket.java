package com.corocorabenz.portalti.ticket;

import jakarta.persistence.*;

@Entity
@Table(name = "ti_ticket", uniqueConstraints = @UniqueConstraint(columnNames = "businessKey"))
public class Ticket {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, unique = true) private String businessKey;
  private String requesterId;
  private String systemName;
  @Column(length = 4000) private String description;
  private String impact;
  private String urgency;
  private Boolean safetyImpact;
  private Integer affectedTeams;
  private String status = "OPEN";
  @Column(length = 4000) private String rootCause;
  @Column(length = 4000) private String resolutionSummary;
  public Long getId() { return id; }
  public String getTicketId() { return "TI-" + id; }
  public String getBusinessKey() { return businessKey; }
  public void setBusinessKey(String v) { businessKey = v; }
  public String getRequesterId() { return requesterId; }
  public void setRequesterId(String v) { requesterId = v; }
  public String getSystemName() { return systemName; }
  public void setSystemName(String v) { systemName = v; }
  public String getDescription() { return description; }
  public void setDescription(String v) { description = v; }
  public String getImpact() { return impact; }
  public void setImpact(String v) { impact = v; }
  public String getUrgency() { return urgency; }
  public void setUrgency(String v) { urgency = v; }
  public Boolean getSafetyImpact() { return safetyImpact; }
  public void setSafetyImpact(Boolean v) { safetyImpact = v; }
  public Integer getAffectedTeams() { return affectedTeams; }
  public void setAffectedTeams(Integer v) { affectedTeams = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { status = v; }
  public String getRootCause() { return rootCause; }
  public void setRootCause(String v) { rootCause = v; }
  public String getResolutionSummary() { return resolutionSummary; }
  public void setResolutionSummary(String v) { resolutionSummary = v; }
}
