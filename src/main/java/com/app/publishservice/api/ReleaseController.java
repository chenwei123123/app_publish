package com.app.publishservice.api;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.api.dto.PageResponse;
import com.app.publishservice.api.dto.ReleaseRecordPageResponse;
import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.ReleaseSubmitRequest;
import com.app.publishservice.service.ReleaseOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/releases")
@Tag(name = "发布管理", description = "发版提交与发版记录查询接口")
public class ReleaseController {

    private final ReleaseOrchestrationService releaseOrchestrationService;

    public ReleaseController(ReleaseOrchestrationService releaseOrchestrationService) {
        this.releaseOrchestrationService = releaseOrchestrationService;
    }

    @PostMapping("/submit")
    @Operation(summary = "提交发版", description = "将指定版本提交到一个或多个应用商店进行发版")
    public ApiResponse<List<ReleaseRecordResponse>> submit(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "发版提交请求体")
            @Valid @RequestBody ReleaseSubmitRequest request
    ) {
        return ApiResponse.success(releaseOrchestrationService.submit(request));
    }

    @GetMapping
    @Operation(summary = "查询发版记录", description = "分页查询发版记录列表")
    public ApiResponse<PageResponse<ReleaseRecordPageResponse>> list(
            @Parameter(description = "当前页，兼容 pageNum")
            @RequestParam(value = "current", required = false) Long current,
            @Parameter(description = "每页大小，兼容 pageSize")
            @RequestParam(value = "size", required = false) Long size,
            @Parameter(description = "当前页别名，未传 current 时生效")
            @RequestParam(value = "pageNum", required = false) Long pageNum,
            @Parameter(description = "每页大小别名，未传 size 时生效")
            @RequestParam(value = "pageSize", required = false) Long pageSize,
            @Parameter(description = "关键字，支持按商店类型、应用名称、包名、应用描述或版本号模糊匹配")
            @RequestParam(value = "key", required = false) String key
    ) {
        return ApiResponse.success(releaseOrchestrationService.pageReleaseRecords(
                current != null ? current : pageNum,
                size != null ? size : pageSize,
                key
        ));
    }

    @GetMapping("/{releaseId}")
    @Operation(summary = "查询发版详情", description = "根据发版记录 ID 查询发版详情")
    public ApiResponse<ReleaseRecordResponse> get(
            @Parameter(description = "发版记录 ID", required = true) @PathVariable Long releaseId
    ) {
        return ApiResponse.success(releaseOrchestrationService.getReleaseRecord(releaseId));
    }

    @GetMapping("/appId/{appId}")
    @Operation(summary = "根据应用 ID 查询发版记录", description = "根据应用 ID 查询该应用下的发版记录列表")
    public ApiResponse<PageResponse<ReleaseRecordPageResponse>> queryReleaseByAppId(
            @Parameter(description = "应用 ID", required = true) @PathVariable Long appId
    ) {
        return ApiResponse.success(releaseOrchestrationService.queryReleaseByAppId(appId));
    }
}
