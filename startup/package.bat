@echo off
setlocal

set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=dev"

if /I not "%PROFILE%"=="dev" if /I not "%PROFILE%"=="sit" if /I not "%PROFILE%"=="prod" (
  echo Usage: package.bat [dev^|sit^|prod]
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"

pushd "%PROJECT_DIR%" >nul
call mvn.cmd -s settings.xml clean package -P%PROFILE% -DskipTests
set "EXIT_CODE=%ERRORLEVEL%"
popd >nul

if not "%EXIT_CODE%"=="0" (
  exit /b %EXIT_CODE%
)

echo Package complete for profile=%PROFILE%.
endlocal
exit /b 0
