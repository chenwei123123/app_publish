@echo off
setlocal

set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=dev"

if /I not "%PROFILE%"=="dev" if /I not "%PROFILE%"=="sit" if /I not "%PROFILE%"=="prod" (
  echo Usage: start.bat [dev^|sit^|prod] [jar-path]
  exit /b 1
)

set "APP_NAME=app-publish-service"
set "INPUT_JAR=%~2"
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "DIRECT_PARENT=%%~fI"
if exist "%DIRECT_PARENT%\script" (
  set "PROJECT_DIR=%DIRECT_PARENT%"
) else (
  for %%I in ("%SCRIPT_DIR%..\..") do set "PROJECT_DIR=%%~fI"
)
set "RUN_DIR=%PROJECT_DIR%\script\run"
set "LOG_DIR=%PROJECT_DIR%\log"
set "PID_FILE=%RUN_DIR%\%APP_NAME%-%PROFILE%.pid"
set "OUT_LOG=%LOG_DIR%\%APP_NAME%-%PROFILE%.out.log"
set "ERR_LOG=%LOG_DIR%\%APP_NAME%-%PROFILE%.err.log"

if not exist "%RUN_DIR%" mkdir "%RUN_DIR%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

if exist "%PID_FILE%" (
  set /p EXISTING_PID=<"%PID_FILE%"
  if not "%EXISTING_PID%"=="" (
    tasklist /FI "PID eq %EXISTING_PID%" | find "%EXISTING_PID%" >nul 2>nul
    if not errorlevel 1 (
      echo %APP_NAME% %PROFILE% is already running. PID=%EXISTING_PID%
      exit /b 0
    )
  )
  del /q "%PID_FILE%" >nul 2>nul
)

set "JAR_PATH=%INPUT_JAR%"
if "%JAR_PATH%"=="" (
  for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$projectDir = [IO.Path]::GetFullPath('%PROJECT_DIR%'); $jar = Get-ChildItem -Path $projectDir -Filter 'app-publish-service-*-%PROFILE%.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if (-not $jar) { $target = Join-Path $projectDir 'target'; $jar = Get-ChildItem -Path $target -Filter 'app-publish-service-*-%PROFILE%.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1 }; if ($jar) { $jar.FullName }"`) do set "JAR_PATH=%%I"
)

if "%JAR_PATH%"=="" (
  echo No runnable jar found for profile=%PROFILE% under %PROJECT_DIR% or target\.
  echo Please package the project first, for example:
  echo mvn -s settings.xml clean package -P%PROFILE% -DskipTests
  exit /b 1
)

if not exist "%JAR_PATH%" (
  echo Jar file not found: %JAR_PATH%
  exit /b 1
)

echo Starting %APP_NAME% with profile=%PROFILE%

for /f %%I in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$jar = [IO.Path]::GetFullPath('%JAR_PATH%'); $outLog = [IO.Path]::GetFullPath('%OUT_LOG%'); $errLog = [IO.Path]::GetFullPath('%ERR_LOG%'); $pidFile = [IO.Path]::GetFullPath('%PID_FILE%'); $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\\java.exe' } else { 'java.exe' }; $argList = @(); if ($env:JAVA_OPTS) { $argList += ($env:JAVA_OPTS -split ' ' | Where-Object { $_ }) }; $argList += '-jar', $jar, '--spring.profiles.active=%PROFILE%'; if ($env:APP_ARGS) { $argList += ($env:APP_ARGS -split ' ' | Where-Object { $_ }) }; $proc = Start-Process -FilePath $javaBin -ArgumentList $argList -WorkingDirectory '%PROJECT_DIR%' -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru; Set-Content -Path $pidFile -Value $proc.Id; $proc.Id"') do set "APP_PID=%%I"

if "%APP_PID%"=="" (
  echo Failed to start %APP_NAME%.
  exit /b 1
)

echo Started. PID=%APP_PID%
echo Jar: %JAR_PATH%
echo stdout: %OUT_LOG%
echo stderr: %ERR_LOG%

endlocal
exit /b 0
