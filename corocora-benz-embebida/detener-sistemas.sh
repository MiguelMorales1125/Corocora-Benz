#!/usr/bin/env bash
# Detiene los sistemas que arrancó iniciar-sistemas.sh (usa los procesos anotados en logs/pids).
cd "$(dirname "$0")"
if [ ! -s logs/pids ]; then
  echo "No hay sistemas anotados en logs/pids."
  exit 0
fi
while read -r pid; do
  if kill "$pid" 2>/dev/null; then
    echo "Detenido el proceso $pid"
  fi
done < logs/pids
: > logs/pids
