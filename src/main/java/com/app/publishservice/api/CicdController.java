package com.app.publishservice.api;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.service.CicdReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/cicd")
@Tag(name = "CI/CD 发布", description = "CI/CD 场景下一键上传并触发发布的接口")
public class CicdController {

    private final CicdReleaseService cicdReleaseService;

    public CicdController(CicdReleaseService cicdReleaseService) {
        this.cicdReleaseService = cicdReleaseService;
    }

    @PostMapping("/releases/trigger")
    @Operation(summary = "触发 CI/CD 发布", description = "上传安装包并直接触发指定渠道的发布流程")
    public ApiResponse<List<ReleaseRecordResponse>> trigger(
            @Parameter(description = "应用 ID", required = true) @RequestParam("appId") Long appId,
            @Parameter(description = "渠道类型列表，多个值用英文逗号分隔", required = true) @RequestParam("storeTypes") String storeTypes,
            @Parameter(description = "安装包文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "版本更新说明") @RequestParam(value = "updateLog", required = false) String updateLog,
            @Parameter(description = "期望版本名称，用于上传后校验") @RequestParam(value = "expectedVersionName", required = false) String expectedVersionName,
            @Parameter(description = "期望版本号，用于上传后校验") @RequestParam(value = "expectedVersionCode", required = false) String expectedVersionCode,
            @Parameter(description = "是否期望为加固包") @RequestParam(value = "expectedReinforced", required = false) Boolean expectedReinforced
    ) throws IOException {
        return ApiResponse.success(cicdReleaseService.trigger(
                appId,
                Arrays.stream(storeTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                updateLog,
                expectedVersionName,
                expectedVersionCode,
                expectedReinforced,
                file
        ));
    }
}
