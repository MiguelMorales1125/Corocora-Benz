-- Bases de PostgreSQL de Corocora Benz. Correr una sola vez:
--   psql -U postgres -p 5432 -f crear-bases.sql
-- corocora_camunda: tablas del motor (las crea Camunda al arrancar).
-- corocora: tablas de negocio de los portales, CentralSys, Banco y Taller-Bodega (las crea JPA).
CREATE DATABASE corocora_camunda;
CREATE DATABASE corocora;
