@echo off
setlocal

set "DEFAULT_PROFILE=__DEFAULT_PROFILE__"
set "PROFILE_PLACEHOLDER=__DEFAULT"
set "PROFILE_PLACEHOLDER=%PROFILE_PLACEHOLDER%_PROFILE__"
if "%DEFAULT_PROFILE%"=="%PROFILE_PLACEHOLDER%" set "DEFAULT_PROFILE=dev"
set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=%DEFAULT_PROFILE%"

if /I not "%PROFILE%"=="dev" if /I not "%PROFILE%"=="sit" if /I not "%PROFILE%"=="prod" (
  echo Usage: stop.bat [dev^|sit^|prod]
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
set "APP_NAME=app-publish-service"
for %%I in ("%SCRIPT_DIR%..") do set "DIRECT_PARENT=%%~fI"
if exist "%DIRECT_PARENT%\script" (
  set "PROJECT_DIR=%DIRECT_PARENT%"
) else (
  for %%I in ("%SCRIPT_DIR%..\..") do set "PROJECT_DIR=%%~fI"
)
set "PID_FILE=%PROJECT_DIR%\script\run\%APP_NAME%-%PROFILE%.pid"

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
echo Stopped %APP_NAME% %PROFILE%. PID=%APP_PID%

endlocal
exit /b 0
