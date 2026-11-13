import { Client, Variables, logger } from 'camunda-external-task-client-js';

// Worker de Gestión de Nómina. Simula el HRIS, el software de nómina y la plataforma bancaria.
// El cálculo es ficticio y no implementa reglas laborales reales.
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

client.subscribe('nomina.obtenerNovedades', async ({ task, taskService }) => {
  // Variables opcionales de demostración (se envían al iniciar el proceso por REST):
  //   simulatePeriodClosed, simulateMissingSupports, simulateLateChanges.
  // Cuando Talento Humano ya registró su corrección (correctionAction), el HRIS
  // devuelve el periodo abierto y los soportes completos para no repetir el ciclo.
  const corrected = task.variables.get('correctionAction') != null;
  const periodOpen = corrected || task.variables.get('simulatePeriodClosed') !== true;
  const documentsComplete = corrected || task.variables.get('simulateMissingSupports') !== true;
  const hasLateChanges = !corrected && task.variables.get('simulateLateChanges') === true;

  const changes = [
    { employeeId: 'EMP-001', changeType: 'Horas extra', changeValue: 2, supportAttached: true },
    { employeeId: 'EMP-002', changeType: 'Incapacidad', changeValue: 1, supportAttached: documentsComplete }
  ];
  if (hasLateChanges) {
    changes.push({ employeeId: 'EMP-003', changeType: 'Horas extra', changeValue: 4, supportAttached: true });
  }

  const variables = result({
    periodOpen,
    documentsComplete,
    hasLateChanges,
    lateChangesSummary: hasLateChanges ? 'EMP-003 · Horas extra · 4 h registradas después de la fecha de corte' : null
  });
  // La multiinstancia necesita una colección Java, no un JSON genérico.
  variables.setTyped('employeeChanges', {
    value: JSON.stringify(changes),
    type: 'Object',
    valueInfo: { serializationDataFormat: 'application/json', objectTypeName: 'java.util.ArrayList' }
  });
  await taskService.complete(task, variables);
});

client.subscribe('nomina.calcularLiquidacion', async ({ task, taskService }) => {
  // La consistencia no se fija aquí: la decide el analista en su formulario.
  const period = task.variables.get('payPeriodId') || 'sin periodo';
  const variables = result({ payrollSummary: `Liquidación simulada del periodo ${period}, sin excepciones automáticas` });
  variables.setTyped('payrollTotal', { value: 1000000, type: 'Long' });
  await taskService.complete(task, variables);
});

client.subscribe('nomina.generarComprobantes', async ({ task, taskService }) => {
  await taskService.complete(task, result({ receiptsGenerated: true }));
});

client.subscribe('nomina.enviarDispersion', async ({ task, taskService }) => {
  // El proceso queda esperando DispersionAceptada, DispersionRechazada o el plazo de 2 h.
  await taskService.complete(task, result({ paymentBatchSent: true }));
});

client.subscribe('nomina.registrarPago', async ({ task, taskService }) => {
  await taskService.complete(task, result({ paymentRecorded: true, payrollPeriodClosed: true }));
});
