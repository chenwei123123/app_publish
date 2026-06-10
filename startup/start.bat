@echo off
setlocal

set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=dev"

if /I not "%PROFILE%"=="dev" if /I not "%PROFILE%"=="sit" if /I not "%PROFILE%"=="prod" (
  echo Usage: start.bat [dev^|sit^|prod]
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "RUN_DIR=%SCRIPT_DIR%run"
set "LOG_DIR=%SCRIPT_DIR%logs"
set "PID_FILE=%RUN_DIR%\app-publish-service-%PROFILE%.pid"
set "OUT_LOG=%LOG_DIR%\app-publish-service-%PROFILE%.out.log"
set "ERR_LOG=%LOG_DIR%\app-publish-service-%PROFILE%.err.log"

if not exist "%RUN_DIR%" mkdir "%RUN_DIR%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

if exist "%PID_FILE%" (
  set /p EXISTING_PID=<"%PID_FILE%"
  if not "%EXISTING_PID%"=="" (
    tasklist /FI "PID eq %EXISTING_PID%" | find "%EXISTING_PID%" >nul 2>nul
    if not errorlevel 1 (
      echo app-publish-service %PROFILE% is already running. PID=%EXISTING_PID%
      exit /b 0
    )
  )
  del /q "%PID_FILE%" >nul 2>nul
)

echo Starting app-publish-service with profile=%PROFILE%

for /f %%I in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$projectDir = [IO.Path]::GetFullPath(''%PROJECT_DIR%''); $outLog = [IO.Path]::GetFullPath(''%OUT_LOG%''); $errLog = [IO.Path]::GetFullPath(''%ERR_LOG%''); $settings = [IO.Path]::GetFullPath((Join-Path $projectDir ''settings.xml'')); $proc = Start-Process -FilePath ''mvn.cmd'' -ArgumentList @(''-s'', $settings, ''spring-boot:run'', ('-Dspring-boot.run.profiles=%PROFILE%'')) -WorkingDirectory $projectDir -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru; $proc.Id"') do set "APP_PID=%%I"

if "%APP_PID%"=="" (
  echo Failed to start app-publish-service.
  exit /b 1
)

>"%PID_FILE%" echo %APP_PID%
echo Started. PID=%APP_PID%
echo stdout: %OUT_LOG%
echo stderr: %ERR_LOG%

endlocal
exit /b 0
