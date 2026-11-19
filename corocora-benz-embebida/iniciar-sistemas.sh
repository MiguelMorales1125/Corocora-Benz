#!/usr/bin/env bash
# Arranca los 6 sistemas de Corocora Benz en segundo plano (Linux, macOS o Git Bash).
#
# Uso:
#   ./iniciar-sistemas.sh        usa PostgreSQL (perfil por defecto)
#   ./iniciar-sistemas.sh h2     usa bases H2 en memoria
#
# Primero arranca el motor y espera a que responda su puerto: los demás sistemas validan
# los usuarios y consultan las tareas contra él. La salida de cada sistema queda en logs/.
# Si existe configuracion.env (ver configuracion.ejemplo.env), sus variables se pasan a los sistemas.
# Para detener todo: ./detener-sistemas.sh
set -u
cd "$(dirname "$0")"

VERSION="1.0.0-SNAPSHOT"
MOTOR="cb-bpm-engine"
OTROS="cb-portal-cliente cb-portal-ti cb-central-sys cb-banco cb-taller-bodega"
PUERTO_MOTOR=9000
ESPERA_MAXIMA_SEGUNDOS=240

PERFIL=""
case "${1:-}" in
  "") ;;
  h2) PERFIL="--spring.profiles.active=h2" ;;
  *) echo "Argumento no reconocido: $1"; echo "Uso: ./iniciar-sistemas.sh [h2]"; exit 1 ;;
esac

if [ -f configuracion.env ]; then
  echo "Usando la configuración de configuracion.env"
  set -a
  # shellcheck disable=SC1090
  . <(tr -d '\r' < configuracion.env)
  set +a
fi

HOST="${SERVER_ADDRESS:-127.0.0.1}"
[ "$HOST" = "0.0.0.0" ] && HOST="127.0.0.1"

if ! command -v java >/dev/null 2>&1; then
  echo "No se encontró java en el PATH. Instala Java 17 o superior."
  exit 1
fi

FALTAN=""
for m in $MOTOR $OTROS; do
  if [ ! -f "$m/target/$m-$VERSION.jar" ]; then
    echo "Falta $m/target/$m-$VERSION.jar"
    FALTAN=1
  fi
done
if [ -n "$FALTAN" ]; then
  echo
  echo "Hay que compilar antes de arrancar. Desde esta carpeta ejecuta:"
  echo "    ./mvnw package -DskipTests"
  exit 1
fi

puerto_abierto() {
  (exec 3<>"/dev/tcp/$HOST/$1") 2>/dev/null
}

if puerto_abierto "$PUERTO_MOTOR"; then
  echo "Ya hay algo escuchando en el puerto $PUERTO_MOTOR."
  echo "Ejecuta ./detener-sistemas.sh antes de volver a arrancar."
  exit 1
fi

mkdir -p logs
: > logs/pids

arrancar() {
  # shellcheck disable=SC2086
  nohup java -jar "$1/target/$1-$VERSION.jar" $PERFIL > "logs/$1.log" 2>&1 &
  echo "$!" >> logs/pids
}

if [ -n "$PERFIL" ]; then
  echo "Perfil: h2, bases en memoria que se pierden al detener cada sistema."
else
  echo "Perfil: por defecto, PostgreSQL. Usa \"./iniciar-sistemas.sh h2\" para no depender de PostgreSQL."
fi

echo "Arrancando el motor en el puerto $PUERTO_MOTOR..."
arrancar "$MOTOR"

echo "Esperando a que el motor responda, puede tardar cerca de un minuto..."
esperado=0
until puerto_abierto "$PUERTO_MOTOR"; do
  if [ "$esperado" -ge "$ESPERA_MAXIMA_SEGUNDOS" ]; then
    echo "El motor no respondió en $ESPERA_MAXIMA_SEGUNDOS segundos. Revisa logs/$MOTOR.log."
    echo "No se arrancaron los demás sistemas."
    exit 1
  fi
  sleep 2
  esperado=$((esperado + 2))
done

echo "El motor responde. Arrancando los demás sistemas..."
for m in $OTROS; do
  arrancar "$m"
done

echo
echo "Listo. Cada sistema tarda unos segundos en estar disponible; su salida está en logs/:"
echo "  Motor Camunda   http://localhost:9000/camunda/app/cockpit/"
echo "  PortalCliente   http://localhost:9001"
echo "  PortalTI        http://localhost:9002"
echo "  CentralSys      http://localhost:9003"
echo "  Banco           http://localhost:9004"
echo "  Taller-Bodega   http://localhost:9005"
echo "Pruebas: entra a la carpeta tests y ejecuta \"npm run todo\"."
echo "Para detener todo: ./detener-sistemas.sh"
