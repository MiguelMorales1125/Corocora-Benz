@echo off
setlocal
cd /d "%~dp0"

where npm >nul 2>nul
if errorlevel 1 (
  echo No se encontro npm. Instala Node.js 18 o superior y vuelve a abrir este archivo.
  pause
  exit /b 1
)

if not exist "node_modules\camunda-external-task-client-js" (
  echo Instalando dependencias de los workers...
  call npm install
  if errorlevel 1 (
    echo No se pudieron instalar las dependencias.
    pause
    exit /b 1
  )
)

start "Worker TI" cmd /k "npm run ti"

echo Se abrio el worker de TI (temporal hasta la Fase 4). Puedes dejar esta ventana abierta o cerrarla.
endlocal
