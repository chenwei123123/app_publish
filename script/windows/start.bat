@echo off
setlocal EnableDelayedExpansion

set "DEFAULT_PROFILE=__DEFAULT_PROFILE__"
set "PROFILE_PLACEHOLDER=__DEFAULT"
set "PROFILE_PLACEHOLDER=%PROFILE_PLACEHOLDER%_PROFILE__"
if "%DEFAULT_PROFILE%"=="%PROFILE_PLACEHOLDER%" set "DEFAULT_PROFILE=dev"
set "PROFILE_ARG=%~1"
set "PROFILE=%PROFILE_ARG%"
if "%PROFILE%"=="" set "PROFILE=%DEFAULT_PROFILE%"

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
echo Default profile: %DEFAULT_PROFILE%
if "%PROFILE_ARG%"=="" (
  echo Effective profile: %PROFILE% ^(using script default^)
) else (
  echo Effective profile: %PROFILE% ^(from command argument^)
)
set "PACKAGED_DIR=%PROJECT_DIR%\target\app_publish"
set "FALLBACK_TARGET_DIR=%PROJECT_DIR%\target"
set "RUN_DIR=%PROJECT_DIR%\script\run"
set "LOG_DIR=%PROJECT_DIR%\log"
set "PID_FILE=%RUN_DIR%\%APP_NAME%-%PROFILE%.pid"
set "OUT_LOG=%LOG_DIR%\%APP_NAME%-%PROFILE%.out.log"
set "ERR_LOG=%LOG_DIR%\%APP_NAME%-%PROFILE%.err.log"
if "%STARTUP_TIMEOUT%"=="" set "STARTUP_TIMEOUT=120"

if not exist "%RUN_DIR%" mkdir "%RUN_DIR%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%OUT_LOG%" type nul > "%OUT_LOG%"
if not exist "%ERR_LOG%" type nul > "%ERR_LOG%"

if exist "%PID_FILE%" (
  set /p EXISTING_PID=<"%PID_FILE%"
  if not "!EXISTING_PID!"=="" (
    tasklist /FI "PID eq !EXISTING_PID!" | find "!EXISTING_PID!" >nul 2>nul
    if not errorlevel 1 (
      echo %APP_NAME% %PROFILE% is already running. PID=!EXISTING_PID!
      if not "%NO_TAIL%"=="1" call "%SCRIPT_DIR%logs.bat" "%PROFILE%"
      exit /b 0
    )
  )
  del /q "%PID_FILE%" >nul 2>nul
)

set "JAR_PATH=%INPUT_JAR%"
if "%JAR_PATH%"=="" (
  for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$searchDirs = @(); $projectDir = [IO.Path]::GetFullPath('%PROJECT_DIR%'); $packagedDir = Join-Path $projectDir 'target\\app_publish'; $targetDir = Join-Path $projectDir 'target'; $searchDirs += $projectDir; if ((Test-Path $packagedDir) -and ($packagedDir -ne $projectDir)) { $searchDirs += $packagedDir }; if ((Test-Path $targetDir) -and ($targetDir -ne $projectDir) -and ($targetDir -ne $packagedDir)) { $searchDirs += $targetDir }; $jar = $null; foreach ($dir in $searchDirs) { $jar = Get-ChildItem -Path $dir -Filter 'app-publish-service-*-%PROFILE%.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($jar) { break } }; if ($jar) { $jar.FullName }"`) do set "JAR_PATH=%%I"
)

if "%JAR_PATH%"=="" (
  echo No runnable jar found for profile=%PROFILE% under %PROJECT_DIR% or target\.
  echo Searched directories:
  echo   %PROJECT_DIR%
  echo   %PACKAGED_DIR%
  echo   %PROJECT_DIR%\target
  echo Candidate jars found under these directories:
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$projectDir = [IO.Path]::GetFullPath('%PROJECT_DIR%'); $packagedDir = Join-Path $projectDir 'target\\app_publish'; $targetDir = Join-Path $projectDir 'target'; $searchDirs = @($projectDir, $packagedDir, $targetDir) | Select-Object -Unique; $files = @(); foreach ($dir in $searchDirs) { if (Test-Path $dir) { $files += Get-ChildItem -Path $dir -Filter 'app-publish-service-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' } } }; $files = $files | Sort-Object FullName -Unique; if ($files.Count -eq 0) { Write-Host '  (none)' } else { $files | ForEach-Object { Write-Host ('  ' + $_.FullName) } }"
  echo Please package the project first, for example:
  echo mvn -s settings.xml clean package -P%PROFILE% -DskipTests
  exit /b 1
)

if not exist "%JAR_PATH%" (
  echo Jar file not found: %JAR_PATH%
  exit /b 1
)

echo Starting %APP_NAME% with profile=%PROFILE%
set "SPRING_CONFIG_ARG="
set "ACTIVE_CONFIG_DIR="
if exist "%PROJECT_DIR%\config" (
  set "ACTIVE_CONFIG_DIR=%PROJECT_DIR%\config"
)
if "!ACTIVE_CONFIG_DIR!"=="" (
  for %%I in ("%JAR_PATH%") do if exist "%%~dpIconfig" set "ACTIVE_CONFIG_DIR=%%~dpIconfig"
)
if "!ACTIVE_CONFIG_DIR!"=="" if exist "%PACKAGED_DIR%\config" (
  set "ACTIVE_CONFIG_DIR=%PACKAGED_DIR%\config"
)
if not "!ACTIVE_CONFIG_DIR!"=="" (
  if "!ACTIVE_CONFIG_DIR:~-1!"=="\" set "ACTIVE_CONFIG_DIR=!ACTIVE_CONFIG_DIR:~0,-1!"
  set "SPRING_CONFIG_URI=!ACTIVE_CONFIG_DIR:\=/!"
  set "SPRING_CONFIG_ARG=--spring.config.additional-location=optional:file:/!SPRING_CONFIG_URI!/"
)
echo Using jar: %JAR_PATH%
if not "!ACTIVE_CONFIG_DIR!"=="" (
  echo Config directory: !ACTIVE_CONFIG_DIR!
  if exist "!ACTIVE_CONFIG_DIR!\application.yml" echo   Base config: !ACTIVE_CONFIG_DIR!\application.yml
  if exist "!ACTIVE_CONFIG_DIR!\application-%PROFILE%.yml" (
    echo   Profile config: !ACTIVE_CONFIG_DIR!\application-%PROFILE%.yml
  ) else (
    echo   Profile config: !ACTIVE_CONFIG_DIR!\application-%PROFILE%.yml ^(not found^)
  )
) else (
  echo Config directory: ^(not set, Spring will use jar-internal configuration^)
)

for /f %%I in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$jar = [IO.Path]::GetFullPath('%JAR_PATH%'); $outLog = [IO.Path]::GetFullPath('%OUT_LOG%'); $errLog = [IO.Path]::GetFullPath('%ERR_LOG%'); $pidFile = [IO.Path]::GetFullPath('%PID_FILE%'); $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\\java.exe' } else { 'java.exe' }; $argList = @(); if ($env:JAVA_OPTS) { $argList += ($env:JAVA_OPTS -split ' ' | Where-Object { $_ }) }; $argList += '-jar', $jar, '--spring.profiles.active=%PROFILE%'; if ('%SPRING_CONFIG_ARG%') { $argList += '%SPRING_CONFIG_ARG%' }; if ($env:APP_ARGS) { $argList += ($env:APP_ARGS -split ' ' | Where-Object { $_ }) }; $proc = Start-Process -FilePath $javaBin -ArgumentList $argList -WorkingDirectory '%PROJECT_DIR%' -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru; Set-Content -Path $pidFile -Value $proc.Id; $proc.Id"') do set "APP_PID=%%I"

if "%APP_PID%"=="" (
  echo Failed to start %APP_NAME%.
  exit /b 1
)

echo Started. PID=%APP_PID%
echo stdout: %OUT_LOG%
echo stderr: %ERR_LOG%
if "%NO_TAIL%"=="1" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%monitor-startup.ps1" -ProcessId %APP_PID% -OutLog "%OUT_LOG%" -ErrLog "%ERR_LOG%" -StartupTimeout %STARTUP_TIMEOUT% -NoTail
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%monitor-startup.ps1" -ProcessId %APP_PID% -OutLog "%OUT_LOG%" -ErrLog "%ERR_LOG%" -StartupTimeout %STARTUP_TIMEOUT%
)

endlocal
exit /b %errorlevel%
