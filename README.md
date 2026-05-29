# App Publish Service

基于 `db/mysql/数据库表设计.docx` 实现的多商店 APP 发布后端，当前使用：

- Java 21
- Spring Boot 3.3.x
- Spring Web / MyBatis-Plus / Scheduling
- MySQL
- H2（测试）

已覆盖的核心链路：

1. 后台录入应用基础信息与商店 API 配置
2. 上传安装包，自动校验版本号与加固状态，生成版本记录
3. 自动刷新有效令牌并调用商店开放 API 提审
4. 生成发布任务记录并持续更新状态
5. 定时轮询审核结果，同步通过/驳回并保留完整日志
6. 提供 CI/CD 触发入口，实现上传到提审的自动化

## 主要接口

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

表单字段：

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

表单字段：

- `appId`
- `storeTypes`: 逗号分隔，例如 `huawei,oppo`
- `file`
- `updateLog`
- `expectedVersionName`
- `expectedVersionCode`
- `expectedReinforced`

## 安装包识别规则

上传服务按以下优先级识别版本与加固状态：

1. 安装包内 `app-publish-metadata.json`
2. 文件名规则，例如 `demo-1.0.1-build101-reinforced.apk`
3. 压缩包特征扫描，例如 `jiagu`、`reinforce`

推荐在 CI 产物中写入：

```json
{
  "versionName": "1.0.1",
  "versionCode": 101,
  "reinforced": true
}
```

## 商店适配

当前仓库只有表设计文档，没有各商店的正式开放平台参数文档，因此接入层采用可配置适配器：

- `app.store-api.stores.<store>.mock-enabled=true` 时走本地模拟提审和审核流转
- 配置 `baseUrl`、`tokenEndpoint`、`submitEndpoint`、`statusEndpoint` 后可切换为真实 HTTP 对接

## 运行与测试

```bash
mvn -s settings.xml spring-boot:run
mvn -s settings.xml test
```
