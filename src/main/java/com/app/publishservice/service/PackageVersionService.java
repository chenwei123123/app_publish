package com.app.publishservice.service;

import com.app.publishservice.api.dto.AppVersionResponse;
import com.app.publishservice.api.dto.PackageUploadResponse;
import com.app.publishservice.common.exception.NotFoundException;
import com.app.publishservice.config.AppProperties;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.AppType;
import com.app.publishservice.repository.AppVersionRepository;
import com.app.publishservice.service.model.PackageMetadata;
import com.app.publishservice.util.ApkDownloadUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
public class PackageVersionService {

    private static final Logger log = LoggerFactory.getLogger(PackageVersionService.class);

    private final AppManagementService appManagementService;
    private final AppVersionRepository appVersionRepository;
    private final StorageService storageService;
    private final PackageInspectorService packageInspectorService;
    private final AppProperties appProperties;

    public PackageVersionService(
            AppManagementService appManagementService,
            AppVersionRepository appVersionRepository,
            StorageService storageService,
            PackageInspectorService packageInspectorService,
            AppProperties appProperties
    ) {
        this.appManagementService = appManagementService;
        this.appVersionRepository = appVersionRepository;
        this.storageService = storageService;
        this.packageInspectorService = packageInspectorService;
        this.appProperties = appProperties;
    }

    @Transactional
    public PackageUploadResponse upload(
            Long appId,
            MultipartFile file,
            String updateLog,
            String expectedVersionName,
            Integer expectedVersionCode,
            Boolean expectedReinforced
    ) throws IOException {
        log.info("Start package upload, appId={}, fileName={}", appId, file.getOriginalFilename());
        AppInfo appInfo = appManagementService.requireApp(appId);
        Path target = storageService.allocatePath(file.getOriginalFilename());
        PackageMetadata metadata = packageInspectorService.inspect(file, target);
        validatePackageAgainstApp(appInfo, metadata);
        validateExpectedMetadata(metadata, expectedVersionName, expectedVersionCode, expectedReinforced);
        ensureVersionIsNew(appId, metadata.versionName(), metadata.versionCode());

        AppVersion appVersion = new AppVersion();
        appVersion.setAppId(appId);
        appVersion.setAppInfo(appInfo);
        appVersion.setVersionName(metadata.versionName());
        appVersion.setVersionCode(metadata.versionCode());
        appVersion.setPackageUrlLow(target.toString());
        appVersion.setUpdateLog(updateLog);
        appVersion.setIsReinforce(metadata.reinforced() ? 1 : 0);
        appVersionRepository.insert(appVersion);
        log.info(
                "Package upload success, appId={}, versionId={}, versionName={}, versionCode={}, packageType={}",
                appId,
                appVersion.getId(),
                metadata.versionName(),
                metadata.versionCode(),
                metadata.packageType()
        );

        return new PackageUploadResponse(
                appVersion.getId(),
                appId,
                metadata.packageType(),
                target.toString(),
                metadata.versionName(),
                metadata.versionCode(),
                metadata.reinforced(),
                metadata.checksum(),
                appVersion.getCreateTime()
        );
    }

    @Transactional(readOnly = true)
    public AppVersion requireVersion(Long versionId) {
        AppVersion version = appVersionRepository.selectById(versionId);
        if (version == null) {
            throw new NotFoundException("Version not found");
        }
        version.setAppInfo(appManagementService.requireApp(version.getAppId()));
        return version;
    }

    @Transactional
    public AppVersion prepareVersionForSubmit(AppVersion version) {
        if (StringUtils.hasText(version.getBuildCode())) {
            if (appProperties.getPackageRepository().isStreamUploadEnabled()) {
                String apk32Url = ApkDownloadUtil.buildApk32Url(version.getVersionCode(), version.getBuildCode());
                String apk64Url = ApkDownloadUtil.buildApk64Url(version.getVersionCode(), version.getBuildCode());
                version.setPackageUrlLow(apk32Url);
                version.setPackageUrlHigh(apk64Url);
                appVersionRepository.updateById(version);
                log.info(
                        "Submit package URLs prepared, versionId={}, versionCode={}, buildCode={}, apk32Url={}, apk64Url={}",
                        version.getId(),
                        version.getVersionCode(),
                        version.getBuildCode(),
                        apk32Url,
                        apk64Url
                );
                return version;
            }

            Path apk32Target = allocateSubmitPackagePath(buildPackageFileName(version, "32"));
            Path apk64Target = allocateSubmitPackagePath(buildPackageFileName(version, "64"));
            try {
                ApkDownloadUtil.downloadApk32(version.getVersionCode(), version.getBuildCode(), apk32Target.toString());
                ApkDownloadUtil.downloadApk64(version.getVersionCode(), version.getBuildCode(), apk64Target.toString());
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to download submit packages", ex);
            }
            version.setPackageUrlLow(apk32Target.toString());
            version.setPackageUrlHigh(apk64Target.toString());
            appVersionRepository.updateById(version);
            log.info(
                    "Submit packages downloaded, versionId={}, versionCode={}, buildCode={}, apk32Path={}, apk64Path={}",
                    version.getId(),
                    version.getVersionCode(),
                    version.getBuildCode(),
                    apk32Target,
                    apk64Target
            );
        }
        return version;
    }

    @Transactional(readOnly = true)
    public List<AppVersionResponse> listVersions(Long appId) {
        appManagementService.requireApp(appId);
        return appVersionRepository.selectList(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appId)
                        .orderByDesc(AppVersion::getVersionCode)
                        .orderByDesc(AppVersion::getCreateTime)
        ).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AppVersionResponse getVersion(Long appId, Long versionId) {
        AppVersion version = requireVersion(versionId);
        if (!version.getAppId().equals(appId)) {
            throw new NotFoundException("Version not found under app: " + appId);
        }
        return toResponse(version);
    }

    private void validatePackageAgainstApp(AppInfo appInfo, PackageMetadata metadata) {
        boolean iosPackage = "ipa".equalsIgnoreCase(metadata.packageType());
        if (appInfo.getAppType() == AppType.IOS && !iosPackage) {
            throw new IllegalArgumentException("iOS apps can only upload IPA packages");
        }
        if (appInfo.getAppType() == AppType.ANDROID && iosPackage) {
            throw new IllegalArgumentException("Android apps cannot upload IPA packages");
        }
    }

    private void validateExpectedMetadata(
            PackageMetadata metadata,
            String expectedVersionName,
            Integer expectedVersionCode,
            Boolean expectedReinforced
    ) {
        if (expectedVersionName != null && !expectedVersionName.equals(metadata.versionName())) {
            throw new IllegalArgumentException("Version name mismatch: expected " + expectedVersionName + ", actual " + metadata.versionName());
        }
        if (expectedVersionCode != null && !expectedVersionCode.equals(metadata.versionCode())) {
            throw new IllegalArgumentException("Version code mismatch: expected " + expectedVersionCode + ", actual " + metadata.versionCode());
        }
        if (expectedReinforced != null && expectedReinforced != metadata.reinforced()) {
            throw new IllegalArgumentException("Reinforce status mismatch: expected " + expectedReinforced + ", actual " + metadata.reinforced());
        }
    }

    private void ensureVersionIsNew(Long appId, String versionName, Integer versionCode) {
        Long duplicateCount = appVersionRepository.selectCount(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appId)
                        .eq(AppVersion::getVersionName, versionName)
                        .eq(AppVersion::getVersionCode, versionCode)
        );
        if (duplicateCount != null && duplicateCount > 0) {
            throw new IllegalArgumentException("Version already exists");
        }

        List<AppVersion> versions = appVersionRepository.selectList(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appId)
                        .orderByDesc(AppVersion::getVersionCode)
                        .orderByDesc(AppVersion::getCreateTime)
                        .last("limit 1")
        );
        if (!versions.isEmpty()) {
            AppVersion lastVersion = versions.getFirst();
            if (versionCode <= lastVersion.getVersionCode()) {
                throw new IllegalArgumentException("Version code must increase, latest=" + lastVersion.getVersionCode());
            }
        }
    }

    private AppVersionResponse toResponse(AppVersion version) {
        return new AppVersionResponse(
                version.getId(),
                version.getAppId(),
                version.getVersionName(),
                version.getVersionCode(),
                version.getBuildCode(),
                version.getPackageUrl(),
                version.getPackageUrlLow(),
                version.getPackageUrlHigh(),
                version.getUpdateLog(),
                version.getIsReinforce() != null && version.getIsReinforce() == 1,
                version.getCreateUser(),
                version.getUpdateUser(),
                version.getCreateTime()
        );
    }

    private Path allocateSubmitPackagePath(String fileName) {
        try {
            return storageService.allocatePath(fileName);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to allocate local path for submit package", ex);
        }
    }

    private String buildPackageFileName(AppVersion version, String archLabel) {
        String baseName = StringUtils.hasText(version.getVersionName())
                ? version.getVersionName()
                : String.valueOf(version.getVersionCode());
        String sanitizedBuildCode = StringUtils.hasText(version.getBuildCode())
                ? version.getBuildCode().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "build";
        return baseName.replaceAll("[^a-zA-Z0-9._-]", "_")
                + "-" + version.getVersionCode()
                + "-" + sanitizedBuildCode
                + "-" + archLabel
                + ".apk";
    }
}
