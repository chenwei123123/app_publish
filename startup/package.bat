@echo off
setlocal EnableDelayedExpansion

set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=dev"

if /I not "%PROFILE%"=="dev" if /I not "%PROFILE%"=="sit" if /I not "%PROFILE%"=="prod" (
  echo Usage: package.bat [dev^|sit^|prod]
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "TARGET_DIR=%PROJECT_DIR%\target"
set "DIST_DIR=%TARGET_DIR%\app_publish"
set "ZIP_FILE=%TARGET_DIR%\app_publish-%PROFILE%.zip"

pushd "%PROJECT_DIR%" >nul
call mvn.cmd -s settings.xml clean package -P%PROFILE% -DskipTests
set "EXIT_CODE=%ERRORLEVEL%"
popd >nul

if not "%EXIT_CODE%"=="0" (
  exit /b %EXIT_CODE%
)

set "JAR_FILE="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$targetDir = [IO.Path]::GetFullPath('%TARGET_DIR%'); $jar = Get-ChildItem -Path $targetDir -Filter 'app-publish-service-*-%PROFILE%.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($jar) { $jar.FullName }"`) do set "JAR_FILE=%%I"

echo Package complete for profile=%PROFILE%.
echo.
if defined JAR_FILE (
  echo Jar: !JAR_FILE!
) else (
  echo Jar: ^(not found under %TARGET_DIR%^)
)
if exist "%ZIP_FILE%" (
  echo Zip: %ZIP_FILE%
) else (
  echo Zip: ^(not found^)
)
if exist "%DIST_DIR%" (
  echo Deploy dir: %DIST_DIR%
) else (
  echo Deploy dir: ^(not found^)
)
if exist "%DIST_DIR%\config" (
  echo Config dir: %DIST_DIR%\config
)

echo.
echo Recommended startup commands:
echo   Windows source: script\windows\start.bat %PROFILE%
echo   Windows package: target\app_publish\script\start.bat
echo   Linux package: ./target/app_publish/script/start.sh
echo.
echo Runtime management:
echo   Logs: target\app_publish\script\logs.bat
echo   Restart: target\app_publish\script\restart.bat
echo   Stop: target\app_publish\script\stop.bat

endlocal
exit /b 0
