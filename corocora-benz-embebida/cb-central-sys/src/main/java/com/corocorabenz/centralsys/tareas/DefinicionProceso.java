package com.corocorabenz.centralsys.tareas;

/** Clave y nombre de una definición de proceso desplegada. */
record DefinicionProceso(String key, String name) {

  static final DefinicionProceso DESCONOCIDA = new DefinicionProceso(null, null);
}
