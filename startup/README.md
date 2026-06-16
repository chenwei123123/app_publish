# Startup

Windows compatibility entry scripts.

## Scripts

- `start-dev.bat` / `start-sit.bat` / `start-prod.bat`
- `stop-dev.bat` / `stop-sit.bat` / `stop-prod.bat`
- `restart-dev.bat` / `restart-sit.bat` / `restart-prod.bat`
- `logs-dev.bat` / `logs-sit.bat` / `logs-prod.bat`
- `start.bat [dev|sit|prod]`
- `stop.bat [dev|sit|prod]`
- `restart.bat [dev|sit|prod]`
- `logs.bat [dev|sit|prod]`
- `package.bat [dev|sit|prod]`

## Behavior

- `start*.bat` forwards to `script\windows\start.bat`
- `stop*.bat` forwards to `script\windows\stop.bat`
- `restart*.bat` forwards to `script\windows\restart.bat`
- `logs*.bat` forwards to `script\windows\logs.bat`
- The actual PID files and logs are written by `script\windows\*.bat`
- `package.bat` runs `mvn -s settings.xml clean package -P<profile> -DskipTests` and prints the generated jar, zip, config directory, and recommended startup commands
- Packaging generates `target/app_publish/` and `target/app_publish-<profile>.zip`
- Prefer using `script\windows\*.bat` directly for new deployments

## Prerequisites

- Java 17 is installed and available in `PATH`, or `JAVA_HOME` is configured
- Maven is available as `mvn.cmd`

## Examples

```cmd
startup\start-dev.bat
startup\stop-dev.bat
startup\restart-dev.bat
startup\logs-dev.bat
startup\package.bat prod
```
