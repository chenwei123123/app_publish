package com.app.publishservice.api;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.api.dto.AppVersionResponse;
import com.app.publishservice.api.dto.PackageUploadResponse;
import com.app.publishservice.service.PackageVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/apps/{appId}/versions")
@Tag(name = "安装包管理", description = "安装包上传、版本解析和版本查询接口")
public class PackageController {

    private final PackageVersionService packageVersionService;

    public PackageController(PackageVersionService packageVersionService) {
        this.packageVersionService = packageVersionService;
    }

    @GetMapping
    @Operation(summary = "查询版本列表", description = "查询指定应用下的所有安装包版本")
    public ApiResponse<List<AppVersionResponse>> list(
            @Parameter(description = "应用 ID", required = true) @PathVariable Long appId
    ) {
        return ApiResponse.success(packageVersionService.listVersions(appId));
    }

    @GetMapping("/{versionId}")
    @Operation(summary = "查询版本详情", description = "根据应用 ID 和版本 ID 查询安装包版本详情")
    public ApiResponse<AppVersionResponse> get(
            @Parameter(description = "应用 ID", required = true) @PathVariable Long appId,
            @Parameter(description = "版本 ID", required = true) @PathVariable Long versionId
    ) {
        return ApiResponse.success(packageVersionService.getVersion(appId, versionId));
    }

    @PostMapping("/upload")
    @Operation(summary = "上传安装包", description = "上传应用安装包并解析版本信息，可附带版本校验条件")
    public ApiResponse<PackageUploadResponse> upload(
            @Parameter(description = "应用 ID", required = true) @PathVariable Long appId,
            @Parameter(description = "安装包文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "版本更新说明") @RequestParam(value = "updateLog", required = false) String updateLog,
            @Parameter(description = "期望的版本名称，用于上传后校验") @RequestParam(value = "expectedVersionName", required = false) String expectedVersionName,
            @Parameter(description = "期望的版本号，用于上传后校验") @RequestParam(value = "expectedVersionCode", required = false) String expectedVersionCode,
            @Parameter(description = "期望是否为加固包") @RequestParam(value = "expectedReinforced", required = false) Boolean expectedReinforced
    ) throws IOException {
        return ApiResponse.success(packageVersionService.upload(
                appId,
                file,
                updateLog,
                expectedVersionName,
                expectedVersionCode,
                expectedReinforced
        ));
    }
}
