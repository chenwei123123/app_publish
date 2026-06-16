package com.app.publishservice.api;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.api.dto.AppDetailResponse;
import com.app.publishservice.api.dto.AppResponse;
import com.app.publishservice.api.dto.AppUpsertRequest;
import com.app.publishservice.service.AppManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/apps")
@Tag(name = "应用管理", description = "应用基础信息的新增、修改、查询和删除接口")
public class AppController {

    private final AppManagementService appManagementService;

    /**
     * 初始化AppController。
     */
    public AppController(AppManagementService appManagementService) {
        this.appManagementService = appManagementService;
    }

    /**
     * 保存相关数据。
     */
    @PostMapping
    @Operation(summary = "创建应用", description = "创建应用基础信息")
    public ApiResponse<AppResponse> save(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "应用新增请求体")
            @Valid @RequestBody AppUpsertRequest request
    ) {
        return ApiResponse.success(appManagementService.saveApp(request));
    }

    /**
     * 更新相关数据。
     */
    @PutMapping("/{appId}")
    @Operation(summary = "更新应用", description = "根据应用 ID 更新应用基础信息")
    public ApiResponse<AppResponse> update(
            @Parameter(description = "应用 ID", required = true) @PathVariable Long appId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "应用更新请求体")
            @Valid @RequestBody AppUpsertRequest request
    ) {
        return ApiResponse.success(appManagementService.updateApp(appId, request));
    }

    /**
     * 查询相关数据。
     */
    @GetMapping
    @Operation(summary = "查询应用列表", description = "按应用名称、应用描述或包名关键字筛选应用")
    public ApiResponse<List<AppResponse>> list(
            @Parameter(description = "关键字，支持按应用名称、应用描述或包名模糊匹配")
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return ApiResponse.success(appManagementService.listApps(keyword));
    }

    /**
     * 获取相关数据。
     */
    @GetMapping("/{appId}")
    @Operation(summary = "查询应用详情", description = "根据应用 ID 查询应用详情、关联版本和关联发版记录")
    public ApiResponse<AppDetailResponse> get(
            @Parameter(description = "应用 ID", required = true) @PathVariable Long appId
    ) {
        return ApiResponse.success(appManagementService.getApp(appId));
    }

    /**
     * 删除相关数据。
     */
    @DeleteMapping("/{appId}")
    @Operation(summary = "删除应用", description = "删除应用及其关联的版本、发版记录等数据")
    public ApiResponse<Void> delete(
            @Parameter(description = "应用 ID", required = true) @PathVariable Long appId
    ) {
        appManagementService.deleteApp(appId);
        return ApiResponse.success(null, "OK");
    }
}
