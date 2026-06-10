# Deploy Scripts

This directory contains deployment startup and shutdown scripts for different operating systems.

## Directory Layout

- `linux/start.sh`
- `linux/stop.sh`
- `windows/start.bat`
- `windows/stop.bat`

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
chmod +x script/linux/start.sh script/linux/stop.sh
```

Start:

```bash
script/linux/start.sh dev
script/linux/start.sh sit
script/linux/start.sh prod
```

Stop:

```bash
script/linux/stop.sh dev
script/linux/stop.sh sit
script/linux/stop.sh prod
```

## Windows

Start:

```bat
script\windows\start.bat dev
script\windows\start.bat sit
script\windows\start.bat prod
```

Stop:

```bat
script\windows\stop.bat dev
script\windows\stop.bat sit
script\windows\stop.bat prod
```

## Runtime Files

- PID files: `script/run/`
- Application logs: `log/`

## Packaged Layout

After `mvn package`, the distributable layout is:

- `target/app_publish/app-publish-service-<version>-<profile>.jar`
- `target/app_publish/log/`
- `target/app_publish/script/start.bat`
- `target/app_publish/script/stop.bat`
- `target/app_publish/script/start.sh`
- `target/app_publish/script/stop.sh`

## Optional Environment Variables

- `JAVA_OPTS`: JVM parameters, for example `-Xms512m -Xmx1024m`
- `APP_ARGS`: additional Spring Boot startup parameters
