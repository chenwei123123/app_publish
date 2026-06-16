@echo off
setlocal

set "DEFAULT_PROFILE=__DEFAULT_PROFILE__"
set "PROFILE_PLACEHOLDER=__DEFAULT"
set "PROFILE_PLACEHOLDER=%PROFILE_PLACEHOLDER%_PROFILE__"
if "%DEFAULT_PROFILE%"=="%PROFILE_PLACEHOLDER%" set "DEFAULT_PROFILE=dev"
set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=%DEFAULT_PROFILE%"

if /I not "%PROFILE%"=="dev" if /I not "%PROFILE%"=="sit" if /I not "%PROFILE%"=="prod" (
  echo Usage: logs.bat [dev^|sit^|prod]
  exit /b 1
)

set "APP_NAME=app-publish-service"
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "DIRECT_PARENT=%%~fI"
if exist "%DIRECT_PARENT%\script" (
  set "PROJECT_DIR=%DIRECT_PARENT%"
) else (
  for %%I in ("%SCRIPT_DIR%..\..") do set "PROJECT_DIR=%%~fI"
)
set "LOG_DIR=%PROJECT_DIR%\log"
set "OUT_LOG=%LOG_DIR%\%APP_NAME%-%PROFILE%.out.log"
set "ERR_LOG=%LOG_DIR%\%APP_NAME%-%PROFILE%.err.log"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%OUT_LOG%" type nul > "%OUT_LOG%"
if not exist "%ERR_LOG%" type nul > "%ERR_LOG%"

echo Following logs:
echo   %OUT_LOG%
echo   %ERR_LOG%
echo Press Ctrl+C to exit log following.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$out = [IO.Path]::GetFullPath('%OUT_LOG%');" ^
  "$err = [IO.Path]::GetFullPath('%ERR_LOG%');" ^
  "Get-Content -Path @($out, $err) -Wait -Tail 50"

endlocal
exit /b 0
