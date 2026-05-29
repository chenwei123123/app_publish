# vivo 沙箱环境配置说明

## 1. 默认地址

`ConfigurableStorePublisher` 已内置 vivo 两套默认地址：

- 测试环境：`https://sandbox-developer-api.vivo.com.cn/router/rest`
- 正式环境：`https://developer-api.vivo.com.cn/router/rest`

## 2. 选择规则

vivo 发版、分阶段创建更新、分阶段详情查询统一按以下顺序选择请求地址：

1. 如果显式配置了 `app.store-api.stores.vivo.base-url`，优先使用该地址。
2. 如果未配置 `base-url`，则根据 `app.store-api.stores.vivo.sandbox-enabled` 自动选择：
   - `true`：使用 vivo 测试环境地址
   - `false`：使用 vivo 正式环境地址

## 3. 当前环境默认值

- `application-dev.yml`
  - `mock-enabled: true`
  - `sandbox-enabled: true`
- `application-sit.yml`
  - `mock-enabled: false`
  - `sandbox-enabled: true`
- `application-prod.yml`
  - `mock-enabled: false`
  - `sandbox-enabled: false`

## 4. 推荐环境变量

可以通过环境变量覆盖 vivo 沙箱开关：

```bash
STORE_VIVO_SANDBOX_ENABLED=true
```

也可以直接覆盖完整地址：

```bash
STORE_VIVO_BASE_URL=https://sandbox-developer-api.vivo.com.cn/router/rest
```

如果要通过环境变量覆盖 `base-url`，需要在对应环境配置中补充占位写法，例如：

```yaml
app:
  store-api:
    stores:
      vivo:
        base-url: ${STORE_VIVO_BASE_URL:}
```

## 5. 使用建议

- 本地联调和 SIT 联调优先使用 `sandbox-enabled=true`
- 生产环境保持 `sandbox-enabled=false`
- 如果 vivo 后续调整网关地址，优先通过 `base-url` 显式覆盖，不需要再次修改代码
