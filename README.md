# App Publish Service

Backend service for multi-store app publishing orchestration.

## Stack

- Java 17
- Spring Boot 3.3.x
- Spring Web
- MyBatis-Plus
- MySQL
- H2 for tests

## Core Capabilities

1. Manage app metadata and store API configuration.
2. Upload APK/AAB/IPA packages and validate version metadata.
3. Refresh store tokens and submit store review requests.
4. Persist release task records and continuously update release status.
5. Poll review results and keep request/response logs.
6. Expose a CI/CD trigger entry for automated publish workflows.

## Main APIs

`POST /api/apps`

```json
{
  "appName": "Demo App",
  "packageName": "com.demo.app",
  "appType": 1,
  "storeConfigs": [
    {
      "storeType": "huawei",
      "clientId": "client-id",
      "clientSecret": "client-secret"
    }
  ]
}
```

`POST /api/apps/{appId}/versions/upload`

Form fields:

- `file`: APK/AAB/IPA
- `updateLog`
- `expectedVersionName`
- `expectedVersionCode`
- `expectedReinforced`

`POST /api/releases/submit`

```json
{
  "versionId": 1,
  "storeTypes": ["huawei", "oppo"],
  "releaseMode": "api"
}
```

`POST /api/cicd/releases/trigger`

Form fields:

- `appId`
- `storeTypes`: comma-separated, for example `huawei,oppo`
- `file`
- `updateLog`
- `expectedVersionName`
- `expectedVersionCode`
- `expectedReinforced`

## Package Metadata Detection

Upload processing detects version and reinforcement state in this order:

1. `app-publish-metadata.json` inside the package
2. File naming convention, for example `demo-1.0.1-build101-reinforced.apk`
3. Archive keyword scanning, for example `jiagu` or `reinforce`

Recommended CI metadata file:

```json
{
  "versionName": "1.0.1",
  "versionCode": 101,
  "reinforced": true
}
```

## Store Integration

Store adapters are configuration-driven.

- Use `app.store-api.stores.<store>.mock-enabled=true` for local mock submit/review flows.
- Configure `baseUrl`, `tokenEndpoint`, `submitEndpoint`, and `statusEndpoint` to switch to real HTTP integrations.

## Runtime And Packaging

Development helper scripts are documented in [`startup`](/D:/app_publish/startup/README.md).
Packaged cross-platform start and stop scripts are documented in [`script`](/D:/app_publish/script/README.md).

Package example:

```cmd
startup\package.bat prod
```

Generated artifacts:

- `target/app_publish/`
- `target/app_publish-<profile>.zip`

## Test

```cmd
mvn -s settings.xml test
```
