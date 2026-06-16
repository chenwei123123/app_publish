@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "TARGET_SCRIPT=%SCRIPT_DIR%..\script\windows\logs.bat"
if not exist "%TARGET_SCRIPT%" (
  echo Target script not found: %TARGET_SCRIPT%
  exit /b 1
)

call "%TARGET_SCRIPT%" %*
set "EXIT_CODE=%ERRORLEVEL%"

endlocal
exit /b %EXIT_CODE%
