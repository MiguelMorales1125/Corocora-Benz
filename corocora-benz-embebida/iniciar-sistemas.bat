@echo off
rem Arranca los 6 sistemas de Corocora Benz, cada uno en su propia ventana.
rem
rem Uso:
rem   iniciar-sistemas.bat        usa PostgreSQL (perfil por defecto)
rem   iniciar-sistemas.bat h2     usa bases H2 en memoria (--spring.profiles.active=h2)
rem
rem Primero arranca el motor y espera a que responda su puerto: los demas sistemas
rem validan los usuarios y consultan las tareas contra el.
rem Si existe configuracion.env (ver configuracion.ejemplo.env), sus variables se pasan a los sistemas.
rem Para detener todo, cierra las ventanas que abre este script.
setlocal EnableExtensions

cd /d "%~dp0"

rem Configuracion opcional: copia configuracion.ejemplo.env como configuracion.env.
if exist "configuracion.env" (
  echo Usando la configuracion de configuracion.env
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("configuracion.env") do set "%%A=%%B"
)

set "VERSION=1.0.0-SNAPSHOT"
set "MOTOR=cb-bpm-engine"
set "OTROS=cb-portal-cliente cb-portal-ti cb-central-sys cb-banco cb-taller-bodega"
set "PUERTO_MOTOR=9000"
set "ESPERA_MAXIMA_SEGUNDOS=240"

rem Los sistemas escuchan en 127.0.0.1 salvo que SERVER_ADDRESS diga otra cosa.
set "HOST=127.0.0.1"
if defined SERVER_ADDRESS if not "%SERVER_ADDRESS%"=="0.0.0.0" set "HOST=%SERVER_ADDRESS%"

set "PERFIL="
if "%~1"=="" goto perfil_listo
if /i "%~1"=="h2" (
  set "PERFIL=--spring.profiles.active=h2"
  goto perfil_listo
)
echo Argumento no reconocido: %~1
echo Uso: iniciar-sistemas.bat [h2]
exit /b 1

:perfil_listo
where java >nul 2>nul
if errorlevel 1 (
  echo No se encontro java en el PATH. Instala Java 17 o superior.
  exit /b 1
)

set "FALTAN="
for %%M in (%MOTOR% %OTROS%) do (
  if not exist "%%M\target\%%M-%VERSION%.jar" (
    echo Falta %%M\target\%%M-%VERSION%.jar
    set "FALTAN=1"
  )
)
if defined FALTAN (
  echo.
  echo Hay que compilar antes de arrancar. Desde esta carpeta ejecuta:
  echo     mvnw.cmd package -DskipTests
  exit /b 1
)

call :esperar_puerto %PUERTO_MOTOR% 0
if not errorlevel 1 (
  echo Ya hay algo escuchando en el puerto %PUERTO_MOTOR%.
  echo Cierra las ventanas de un arranque anterior antes de volver a ejecutar este script.
  exit /b 1
)

if defined PERFIL (
  echo Perfil: h2, bases en memoria que se pierden al cerrar cada ventana.
) else (
  echo Perfil: por defecto, PostgreSQL. Usa "iniciar-sistemas.bat h2" para no depender de PostgreSQL.
)

echo Arrancando el motor en el puerto %PUERTO_MOTOR%...
call :arrancar %MOTOR%

echo Esperando a que el motor responda, puede tardar cerca de un minuto...
call :esperar_puerto %PUERTO_MOTOR% %ESPERA_MAXIMA_SEGUNDOS%
if errorlevel 1 (
  echo El motor no respondio en %ESPERA_MAXIMA_SEGUNDOS% segundos. Revisa el error en su ventana.
  echo No se arrancaron los demas sistemas.
  exit /b 1
)

echo El motor responde. Arrancando los demas sistemas...
for %%M in (%OTROS%) do call :arrancar %%M

echo.
echo Listo. Cada sistema corre en su propia ventana y tarda unos segundos en estar disponible:
echo   Motor Camunda   http://localhost:9000/camunda/app/cockpit/
echo   PortalCliente   http://localhost:9001
echo   PortalTI        http://localhost:9002
echo   CentralSys      http://localhost:9003
echo   Banco           http://localhost:9004
echo   Taller-Bodega   http://localhost:9005
echo Pruebas: entra a la carpeta tests y ejecuta "npm run todo".
exit /b 0


rem ---------------------------------------------------------------------------
rem :arrancar modulo
rem Abre una ventana con el sistema. cmd /k la deja abierta si java termina con error.
:arrancar
start "Corocora Benz - %~1" cmd /k java -jar "%~1\target\%~1-%VERSION%.jar" %PERFIL%
exit /b 0


rem ---------------------------------------------------------------------------
rem :esperar_puerto puerto segundos
rem Termina con errorlevel 0 en cuanto el puerto acepta conexiones, o con 1 si pasan los segundos.
rem Con 0 segundos hace un unico intento.
:esperar_puerto
powershell -NoProfile -Command "$limite = (Get-Date).AddSeconds(%~2); do { $c = New-Object Net.Sockets.TcpClient; try { $c.Connect('%HOST%', %~1); exit 0 } catch { if ((Get-Date) -lt $limite) { Start-Sleep -Seconds 2 } } finally { $c.Dispose() } } while ((Get-Date) -lt $limite); exit 1"
exit /b %errorlevel%
