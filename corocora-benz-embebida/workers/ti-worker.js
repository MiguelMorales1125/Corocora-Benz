import { Client, Variables, logger } from 'camunda-external-task-client-js';

// Worker de Gestión de Incidentes TI. Simula la herramienta de mesa de ayuda.
const client = new Client({
  baseUrl: process.env.CAMUNDA_REST_URL || 'http://localhost:9000/engine-rest',
  asyncResponseTimeout: 10000,
  use: logger
});

function result(values) {
  const variables = new Variables();
  variables.setAll(values);
  return variables;
}

client.subscribe('ti.crearTicket', async ({ task, taskService }) => {
  await taskService.complete(task, result({ ticketId: `TI-${Date.now()}`, ticketStatus: 'OPEN' }));
});

client.subscribe('ti.registrarSolucion', async ({ task, taskService }) => {
  await taskService.complete(task, result({ knowledgeArticleId: `KB-${task.variables.get('ticketId')}` }));
});

client.subscribe('ti.cerrarTicket', async ({ task, taskService }) => {
  await taskService.complete(task, result({ ticketStatus: 'CLOSED' }));
});
