# Startup

Windows helper scripts for local development and packaging.

## Scripts

- `start-dev.bat` / `start-sit.bat` / `start-prod.bat`
- `stop-dev.bat` / `stop-sit.bat` / `stop-prod.bat`
- `start.bat [dev|sit|prod]`
- `stop.bat [dev|sit|prod]`
- `package.bat [dev|sit|prod]`

## Behavior

- `start*.bat` runs `mvn -s settings.xml spring-boot:run`
- PID files are written to `startup/run/`
- Console output and error logs are written to `startup/logs/`
- `package.bat` runs `mvn -s settings.xml clean package -P<profile> -DskipTests`
- Packaging generates `target/app_publish/` and `target/app_publish-<profile>.zip`

## Prerequisites

- Java 17 is installed and available in `PATH`, or `JAVA_HOME` is configured
- Maven is available as `mvn.cmd`

## Examples

```cmd
startup\start-dev.bat
startup\stop-dev.bat
startup\package.bat prod
```
