package com.app.publishservice.api;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.api.dto.PageResponse;
import com.app.publishservice.api.dto.StoreConfigRequest;
import com.app.publishservice.api.dto.StoreConfigResponse;
import com.app.publishservice.service.AppManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/store-configs")
@Tag(name = "应用商店账号配置", description = "应用商店账号配置的新增、修改、查询和状态维护接口")
public class StoreConfigController {

    private final AppManagementService appManagementService;

    public StoreConfigController(AppManagementService appManagementService) {
        this.appManagementService = appManagementService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "创建应用商店账号", description = "创建应用商店账号配置")
    public ApiResponse<StoreConfigResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "应用商店账号配置请求体")
            @Valid @RequestBody StoreConfigRequest request
    ) {
        return ApiResponse.success(appManagementService.saveStoreConfig(request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "创建应用商店账号", description = "以表单方式创建应用商店账号配置，支持上传小米图标文件")
    public ApiResponse<StoreConfigResponse> createMultipart(@Valid @ModelAttribute StoreConfigRequest request) {
        return ApiResponse.success(appManagementService.saveStoreConfig(request));
    }

    @PutMapping(value = "/{configId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "更新应用商店账号", description = "根据配置 ID 更新应用商店账号配置")
    public ApiResponse<StoreConfigResponse> update(
            @Parameter(description = "配置 ID", required = true) @PathVariable Long configId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "应用商店账号配置更新请求体")
            @Valid @RequestBody StoreConfigRequest request
    ) {
        return ApiResponse.success(appManagementService.updateStoreConfig(configId, request));
    }

    @PutMapping(value = "/{configId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "更新应用商店账号", description = "以表单方式根据配置 ID 更新应用商店账号配置，支持上传小米图标文件")
    public ApiResponse<StoreConfigResponse> updateMultipart(
            @Parameter(description = "配置 ID", required = true) @PathVariable Long configId,
            @Valid @ModelAttribute StoreConfigRequest request
    ) {
        return ApiResponse.success(appManagementService.updateStoreConfig(configId, request));
    }

    @DeleteMapping("/{configId}")
    @Operation(summary = "删除应用商店账号", description = "根据配置 ID 删除应用商店账号配置")
    public ApiResponse<Void> delete(
            @Parameter(description = "配置 ID", required = true) @PathVariable Long configId
    ) {
        appManagementService.deleteStoreConfig(configId);
        return ApiResponse.success(null, "OK");
    }

    @GetMapping
    @Operation(summary = "查询应用商店账号列表", description = "分页查询应用商店账号配置")
    public ApiResponse<PageResponse<StoreConfigResponse>> list(
            @Parameter(description = "当前页，兼容 pageNum")
            @RequestParam(value = "current", required = false) Long current,
            @Parameter(description = "每页大小，兼容 pageSize")
            @RequestParam(value = "size", required = false) Long size,
            @Parameter(description = "当前页别名，未传 current 时生效")
            @RequestParam(value = "pageNum", required = false) Long pageNum,
            @Parameter(description = "每页大小别名，未传 size 时生效")
            @RequestParam(value = "pageSize", required = false) Long pageSize,
            @Parameter(description = "关键字，支持按商店类型、账号名、邮箱、手机号、隐私地址或应用 ID 模糊匹配")
            @RequestParam(value = "key", required = false) String key
    ) {
        return ApiResponse.success(appManagementService.pageStoreConfigResponses(
                current != null ? current : pageNum,
                size != null ? size : pageSize,
                key)
        );
    }

    @GetMapping("/{configId}")
    @Operation(summary = "查询应用商店账号详情", description = "根据配置 ID 查询应用商店账号配置")
    public ApiResponse<StoreConfigResponse> get(
            @Parameter(description = "配置 ID", required = true) @PathVariable Long configId
    ) {
        return ApiResponse.success(appManagementService.getStoreConfig(configId));
    }

    @PutMapping("/{configId}/status")
    @Operation(summary = "更新应用商店账号状态", description = "启用或禁用应用商店账号配置")
    public ApiResponse<StoreConfigResponse> updateStatus(
            @Parameter(description = "配置 ID", required = true) @PathVariable Long configId,
            @Parameter(description = "API 状态，1 表示启用，0 表示禁用", required = true)
            @RequestParam("apiStatus") Integer apiStatus
    ) {
        return ApiResponse.success(appManagementService.updateStoreConfigStatus(configId, apiStatus));
    }
}
