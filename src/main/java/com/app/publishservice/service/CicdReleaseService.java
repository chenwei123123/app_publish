package com.app.publishservice.service;

import com.app.publishservice.api.dto.PackageUploadResponse;
import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.ReleaseSubmitRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class CicdReleaseService {

    private final PackageVersionService packageVersionService;
    private final ReleaseOrchestrationService releaseOrchestrationService;

    public CicdReleaseService(PackageVersionService packageVersionService, ReleaseOrchestrationService releaseOrchestrationService) {
        this.packageVersionService = packageVersionService;
        this.releaseOrchestrationService = releaseOrchestrationService;
    }

    public List<ReleaseRecordResponse> trigger(
            Long appId,
            List<String> storeTypes,
            String updateLog,
            String expectedVersionName,
            Integer expectedVersionCode,
            Boolean expectedReinforced,
            MultipartFile packageFile
    ) throws IOException {
        PackageUploadResponse upload = packageVersionService.upload(
                appId,
                packageFile,
                updateLog,
                expectedVersionName,
                expectedVersionCode,
                expectedReinforced
        );
        ReleaseSubmitRequest request = new ReleaseSubmitRequest();
        request.setVersionId(upload.versionId());
        request.setStoreTypes(storeTypes);
        request.setReleaseMode("api");
        return releaseOrchestrationService.submit(request);
    }
}
