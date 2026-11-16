package com.corocorabenz.tallerbodega.vehiculo;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Vehículo vendido por Corocora Benz, con su campaña de servicio (recall) si la tiene. */
@Entity
@Table(name = "tb_vehiculo")
public class Vehiculo {

  @Id
  private String vin;
  private String modelo;
  private Integer anio;
  /** Campaña de servicio activa; null si no tiene. */
  private String campanaActiva;

  protected Vehiculo() {
  }

  public Vehiculo(String vin, String modelo, Integer anio, String campanaActiva) {
    this.vin = vin;
    this.modelo = modelo;
    this.anio = anio;
    this.campanaActiva = campanaActiva;
  }

  public String getVin() { return vin; }
  public String getModelo() { return modelo; }
  public Integer getAnio() { return anio; }
  public String getCampanaActiva() { return campanaActiva; }
}
