# 应用发布服务

用于统一管理多应用商店发布流程的后端服务。

## 技术栈

- Java 17
- Spring Boot 3.3.x
- Spring Web
- MyBatis-Plus
- MySQL
- H2（测试）

## 核心能力

1. 管理应用基础信息和商店账号配置。
2. 上传 APK/AAB/IPA 安装包并校验版本元数据。
3. 刷新商店 Token 并提交商店审核请求。
4. 持久化发版任务记录并持续更新发布状态。
5. 轮询审核结果并保存请求/响应日志。
6. 提供 CI/CD 触发入口，支持自动化发布流程。

## 主要接口

创建应用：`POST /api/apps`

```json
{
  "appName": "Demo App",
  "packageName": "com.demo.app",
  "appType": 1,
  "appDescription": "演示应用",
  "privacyUrl": "https://example.com/privacy",
  "userAgreementUrl": "https://example.com/agreement",
  "status": 1
}
```

创建商店账号配置：`POST /api/store-configs`

```json
{
  "storeType": "huawei",
  "accountName": "demo-account",
  "clientId": "client-id",
  "clientSecret": "client-secret",
  "apiStatus": 1
}
```

上传安装包：`POST /api/apps/{appId}/versions/upload`

表单字段：

- `file`：APK/AAB/IPA 文件
- `updateLog`：版本更新说明
- `expectedVersionName`：期望版本名称
- `expectedVersionCode`：期望版本号
- `expectedReinforced`：期望是否为加固包

提交发版：`POST /api/releases/submit`

```json
{
  "versionId": 1,
  "storeTypes": ["huawei", "oppo"],
  "releaseMode": "api"
}
```

触发 CI/CD 发布：`POST /api/cicd/releases/trigger`

表单字段：

- `appId`
- `storeTypes`：逗号分隔，例如 `huawei,oppo`
- `file`
- `updateLog`
- `expectedVersionName`
- `expectedVersionCode`
- `expectedReinforced`

## 安装包元数据识别

上传处理按以下顺序识别版本号和加固状态：

1. 安装包内的 `app-publish-metadata.json`
2. 文件名约定，例如 `demo-1.0.1-build101-reinforced.apk`
3. 压缩包关键字扫描，例如 `jiagu` 或 `reinforce`

推荐的 CI 元数据文件：

```json
{
  "versionName": "1.0.1",
  "versionCode": 101,
  "reinforced": true
}
```

## 商店对接

商店适配器采用配置驱动方式。

- 本地联调可使用 `app.store-api.stores.<store>.mock-enabled=true` 开启模拟提交/审核流程。
- 切换真实接口时，配置 `baseUrl`、`tokenEndpoint`、`submitEndpoint` 和 `statusEndpoint`。

## 运行与打包

开发辅助脚本见 [startup](/D:/app_publish/startup/README.md)。
跨平台发布脚本见 [script](/D:/app_publish/script/README.md)。

打包示例：

```cmd
startup\package.bat prod
```

生成产物：

- `target/app_publish/`
- `target/app_publish-<profile>.zip`

## 测试

```cmd
mvn -s settings.xml test
```
