import { Client, Variables, logger } from 'camunda-external-task-client-js';

const client = new Client({
  baseUrl: process.env.CAMUNDA_REST_URL || 'http://localhost:8080/engine-rest',
  asyncResponseTimeout: 10000,
  use: logger
});

client.subscribe('comun.enviarNotificacion', async ({ task, taskService }) => {
  const variables = new Variables();
  variables.setAll({ notificationSent: true, notificationMode: 'SIMULATED' });
  await taskService.complete(task, variables);
});
