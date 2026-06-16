# Deploy Scripts

This directory contains deployment startup and shutdown scripts for different operating systems.
The packaged distribution also contains a `config/` directory. Edit the `application*.yml` files there to change runtime configuration without rebuilding the jar.

## Directory Layout

- `linux/start.sh`
- `linux/stop.sh`
- `linux/restart.sh`
- `linux/logs.sh`
- `windows/start.bat`
- `windows/stop.bat`
- `windows/restart.bat`
- `windows/logs.bat`

## Prerequisites

- Package the project first. The package step generates `target/app_publish/` and `target/app_publish-<profile>.zip`
- Java 17 is installed and available in `PATH`, or `JAVA_HOME` is configured

Example package command:

```bash
mvn -s settings.xml clean package -Pdev -DskipTests
```

## Linux

Grant execute permission before first use:

```bash
chmod +x script/linux/start.sh script/linux/stop.sh script/linux/restart.sh script/linux/logs.sh
```

Start:

```bash
script/linux/start.sh dev
script/linux/start.sh sit
script/linux/start.sh prod
```

The script starts the service in the background, follows the real-time log, and prints whether startup succeeded or failed.
Press `Ctrl+C` to stop log following without stopping the service.
The script automatically loads external config from `config/`.
The startup output prints both the script default profile and the effective profile so it is clear whether the environment came from the package or from an explicit command argument.
When the script is packaged by Maven, its default profile is automatically set to the packaging profile such as `dev`, `sit`, or `prod`.

Stop:

```bash
script/linux/stop.sh dev
script/linux/stop.sh sit
script/linux/stop.sh prod
```

Restart:

```bash
script/linux/restart.sh dev
script/linux/restart.sh sit
script/linux/restart.sh prod
```

Only follow logs:

```bash
script/linux/logs.sh dev
```

## Windows

Start:

```bat
script\windows\start.bat dev
script\windows\start.bat sit
script\windows\start.bat prod
```

The script starts the service in the background, follows the real-time log, and prints whether startup succeeded or failed.
Press `Ctrl+C` to stop log following without stopping the service.
The script automatically loads external config from `config\`.
The startup output prints both the script default profile and the effective profile so it is clear whether the environment came from the package or from an explicit command argument.
When the script is packaged by Maven, its default profile is automatically set to the packaging profile such as `dev`, `sit`, or `prod`.

Stop:

```bat
script\windows\stop.bat dev
script\windows\stop.bat sit
script\windows\stop.bat prod
```

Restart:

```bat
script\windows\restart.bat dev
script\windows\restart.bat sit
script\windows\restart.bat prod
```

Only follow logs:

```bat
script\windows\logs.bat dev
```

## Runtime Files

- External config: `config/`
- PID files: `script/run/`
- Application logs: `log/`

## Packaged Layout

After `mvn package`, the distributable layout is:

- `target/app_publish/app-publish-service-<version>-<profile>.jar`
- `target/app_publish/config/application.yml`
- `target/app_publish/config/application-<profile>.yml`
- `target/app_publish/log/`
- `target/app_publish/script/start.bat`
- `target/app_publish/script/stop.bat`
- `target/app_publish/script/restart.bat`
- `target/app_publish/script/logs.bat`
- `target/app_publish/script/monitor-startup.ps1`
- `target/app_publish/script/start.sh`
- `target/app_publish/script/stop.sh`
- `target/app_publish/script/restart.sh`
- `target/app_publish/script/logs.sh`

## Optional Environment Variables

- `JAVA_OPTS`: JVM parameters, for example `-Xms512m -Xmx1024m`
- `APP_ARGS`: additional Spring Boot startup parameters
- `STARTUP_TIMEOUT`: maximum seconds to wait before declaring startup successful if the process is still running, default `120`
- `NO_TAIL=1`: start or restart the service without entering log follow mode
