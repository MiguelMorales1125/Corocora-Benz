#!/usr/bin/env bash
# Publica cada componente del hub en su propio repositorio de GitHub.
#
# El hub es la fuente de verdad. Este script toma la carpeta de cada componente con
# "git subtree split" (conserva el historial de esa carpeta) y la sube a la rama main
# de su repositorio. Se ejecuta después de hacer commit en el hub:
#
#   ./publicar-componentes.sh
#
# Necesita permiso de escritura en los repositorios. La cuenta se puede cambiar con
# la variable CUENTA_GITHUB.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

CUENTA="${CUENTA_GITHUB:-MiguelMorales1125}"
COMPONENTES="
cb-bpm-engine:CorocoraBenz-BPM-Engine
cb-portal-cliente:CorocoraBenz-PortalCliente
cb-portal-ti:CorocoraBenz-PortalTI
cb-central-sys:CorocoraBenz-CentralSys
cb-banco:CorocoraBenz-Banco
cb-taller-bodega:CorocoraBenz-TallerBodega
cb-comun:CorocoraBenz-Comun
"

if [ -n "$(git status --porcelain -- corocora-benz-embebida)" ]; then
  echo "Hay cambios sin commit en corocora-benz-embebida. Haz commit antes de publicar."
  exit 1
fi

for par in $COMPONENTES; do
  carpeta="${par%%:*}"
  repositorio="${par##*:}"
  echo "Publicando $carpeta en $CUENTA/$repositorio..."
  commit="$(git subtree split --prefix="corocora-benz-embebida/$carpeta" HEAD)"
  git push "https://github.com/$CUENTA/$repositorio.git" "$commit:refs/heads/main"
done

echo "Listo: los 7 repositorios de componente están al día con el hub."
