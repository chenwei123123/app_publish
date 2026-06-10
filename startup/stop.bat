@echo off
setlocal

set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=dev"

if /I not "%PROFILE%"=="dev" if /I not "%PROFILE%"=="sit" if /I not "%PROFILE%"=="prod" (
  echo Usage: stop.bat [dev^|sit^|prod]
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
set "PID_FILE=%SCRIPT_DIR%run\app-publish-service-%PROFILE%.pid"

if not exist "%PID_FILE%" (
  echo No PID file found for profile=%PROFILE%.
  exit /b 0
)

set /p APP_PID=<"%PID_FILE%"
if "%APP_PID%"=="" (
  del /q "%PID_FILE%" >nul 2>nul
  echo PID file is empty and has been removed.
  exit /b 0
)

tasklist /FI "PID eq %APP_PID%" | find "%APP_PID%" >nul 2>nul
if errorlevel 1 (
  del /q "%PID_FILE%" >nul 2>nul
  echo Process %APP_PID% is not running. Stale PID file removed.
  exit /b 0
)

taskkill /PID %APP_PID% /T /F >nul
if errorlevel 1 (
  echo Failed to stop process %APP_PID%.
  exit /b 1
)

del /q "%PID_FILE%" >nul 2>nul
echo Stopped app-publish-service %PROFILE%. PID=%APP_PID%

endlocal
exit /b 0
