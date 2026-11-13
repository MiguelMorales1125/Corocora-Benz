import { Client, Variables, logger } from 'camunda-external-task-client-js';

// Worker de Postventa y del proceso hijo de taller. Datos ficticios de demostración.
const client = new Client({
  baseUrl: process.env.CAMUNDA_REST_URL || 'http://localhost:8080/engine-rest',
  asyncResponseTimeout: 10000,
  use: logger
});

function result(values) {
  const variables = new Variables();
  variables.setAll(values);
  return variables;
}

// VIN de prueba con llamado a revisión (recall) activo.
const RECALL_VIN = 'CB0TEST0000000001';
const WARRANTY_MAX_KM = 60000;

client.subscribe('postventa.consultarVehiculo', async ({ task, taskService }) => {
  const vin = task.variables.get('vin');
  const mileageKm = Number(task.variables.get('mileageKm') || 0);
  const campaignActive = vin === RECALL_VIN;
  await taskService.complete(task, result({
    historyRecordId: `HIST-${vin}`,
    warrantyEligible: mileageKm <= WARRANTY_MAX_KM,
    campaignActive,
    campaignId: campaignActive ? 'RECALL-CAMARA-2026' : null
  }));
});

client.subscribe('postventa.enviarCotizacion', async ({ task, taskService }) => {
  // La respuesta del cliente llega después como mensaje CotizacionAceptada o CotizacionRechazada.
  await taskService.complete(task, result({ quoteId: `COT-${Date.now()}`, quoteAmount: 850000 }));
});

client.subscribe('postventa.reservarRepuestos', async ({ task, taskService }) => {
  await taskService.complete(task, result({ partsReservationStatus: 'RESERVED' }));
});

client.subscribe('postventa.solicitarRepuesto', async ({ task, taskService }) => {
  // La bodega confirma la llegada con el mensaje RepuestoRecibido.
  const partCode = task.variables.get('partCode') || 'SIN-CODIGO';
  await taskService.complete(task, result({ partRequestId: `REQ-${partCode}-${Date.now()}` }));
});
