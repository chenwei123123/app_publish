package com.app.publishservice.service;

import com.app.publishservice.api.dto.AppVersionResponse;
import com.app.publishservice.api.dto.PackageUploadResponse;
import com.app.publishservice.common.exception.NotFoundException;
import com.app.publishservice.common.exception.SubmitPackageDownloadException;
import com.app.publishservice.config.AppProperties;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.AppType;
import com.app.publishservice.repository.AppVersionRepository;
import com.app.publishservice.service.model.PackageMetadata;
import com.app.publishservice.util.ApkDownloadUtil;
import com.app.publishservice.util.VersionCodeUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class PackageVersionService {

    private static final Logger log = LoggerFactory.getLogger(PackageVersionService.class);
    static final String APK32_DOWNLOAD_FAILED_MESSAGE = "32\u4F4Dapk\u6587\u4EF6\u4E0B\u8F7D\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9\u7248\u672C\u53F7\u548C\u6784\u5EFA\u53F7";
    static final String APK64_DOWNLOAD_FAILED_MESSAGE = "64\u4F4Dapk\u6587\u4EF6\u4E0B\u8F7D\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9\u7248\u672C\u53F7\u548C\u6784\u5EFA\u53F7";
    static final String APK32_LOCAL_FILE_FAILED_MESSAGE = "32\u4F4Dapk\u672C\u5730\u6587\u4EF6\u8BFB\u53D6\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9application.yml\u4E2D\u7684app.publish-metadata.values.apk32Path";
    static final String APK64_LOCAL_FILE_FAILED_MESSAGE = "64\u4F4Dapk\u672C\u5730\u6587\u4EF6\u8BFB\u53D6\u5931\u8D25\uFF0C\u8BF7\u6838\u5BF9application.yml\u4E2D\u7684app.publish-metadata.values.apk64Path";

    private final AppManagementService appManagementService;
    private final AppVersionRepository appVersionRepository;
    private final StorageService storageService;
    private final PackageInspectorService packageInspectorService;
    private final AppProperties appProperties;
    private final Environment environment;
    private final PublishMetadataResolver publishMetadataResolver;

    /**
     * 初始化PackageVersionService。
     */
    public PackageVersionService(
            AppManagementService appManagementService,
            AppVersionRepository appVersionRepository,
            StorageService storageService,
            PackageInspectorService packageInspectorService,
            AppProperties appProperties,
            Environment environment
    ) {
        this.appManagementService = appManagementService;
        this.appVersionRepository = appVersionRepository;
        this.storageService = storageService;
        this.packageInspectorService = packageInspectorService;
        this.appProperties = appProperties;
        this.environment = environment;
        this.publishMetadataResolver = new PublishMetadataResolver(appProperties);
    }

    /**
     * 上传相关数据。
     */
    @Transactional
    public PackageUploadResponse upload(
            Long appId,
            MultipartFile file,
            String updateLog,
            String expectedVersionName,
            String expectedVersionCode,
            Boolean expectedReinforced
    ) throws IOException {
        log.info("Start package upload, appId={}, fileName={}", appId, file.getOriginalFilename());
        AppInfo appInfo = appManagementService.requireApp(appId);
        Path target = storageService.allocatePath(file.getOriginalFilename());
        PackageMetadata metadata = packageInspectorService.inspect(file, target);
        //validatePackageAgainstApp(appInfo, metadata);
        validateExpectedMetadata(metadata, expectedVersionName, expectedVersionCode, expectedReinforced);
        ensureVersionIsNew(appId, metadata.versionName(), metadata.versionCode());

        AppVersion appVersion = new AppVersion();
        appVersion.setAppId(appId);
        appVersion.setAppInfo(appInfo);
        appVersion.setVersionName(metadata.versionName());
        appVersion.setVersionCode(metadata.versionCode());
        appVersion.setPackageUrl32(target.toString());
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

    /**
     * 校验版本。
     */
    @Transactional(readOnly = true)
    public AppVersion requireVersion(Long versionId) {
        AppVersion version = appVersionRepository.selectById(versionId);
        if (version == null) {
            throw new NotFoundException("Version not found");
        }
        version.setAppInfo(appManagementService.requireApp(version.getAppId()));
        return version;
    }

    /**
     * 处理requires 提交包下载相关逻辑。
     */
    public boolean requiresSubmitPackageDownload(AppVersion version) {
        return StringUtils.hasText(version.getBuildCode()) && !appProperties.getPackageRepository().isStreamUploadEnabled();
    }

    /**
     * 处理prepare 版本提交相关逻辑。
     */
    @Transactional
    public AppVersion prepareVersionForSubmit(AppVersion version) {
        if (StringUtils.hasText(version.getBuildCode())) {
            if (environment.matchesProfiles("dev")) {
                prepareLocalSubmitPackagesFromMetadata(version);
                return version;
            }
            if (appProperties.getPackageRepository().isStreamUploadEnabled()) {
                String apk32Url = ApkDownloadUtil.buildApk32Url(version.getVersionCode(), version.getBuildCode());
                String apk64Url = ApkDownloadUtil.buildApk64Url(version.getVersionCode(), version.getBuildCode());
                version.setPackageUrl32(apk32Url);
                version.setPackageUrl64(apk64Url);
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

            String apk32Url = ApkDownloadUtil.buildApk32Url(version.getVersionCode(), version.getBuildCode());
            String apk64Url = ApkDownloadUtil.buildApk64Url(version.getVersionCode(), version.getBuildCode());
            Path apk32Target = allocateSubmitPackagePath(version.getVersionCode(), version.getBuildCode(), ApkDownloadUtil.extractFileName(apk32Url));
            Path apk64Target = allocateSubmitPackagePath(version.getVersionCode(), version.getBuildCode(), ApkDownloadUtil.extractFileName(apk64Url));
            try {
                ApkDownloadUtil.downloadApk32(version.getVersionCode(), version.getBuildCode(), apk32Target.toString());
            } catch (IOException ex) {
                throw new SubmitPackageDownloadException(APK32_DOWNLOAD_FAILED_MESSAGE, ex);
            }
            try {
                ApkDownloadUtil.downloadApk64(version.getVersionCode(), version.getBuildCode(), apk64Target.toString());
            } catch (IOException ex) {
                throw new SubmitPackageDownloadException(APK64_DOWNLOAD_FAILED_MESSAGE, ex);
            }
            version.setPackageUrl32(apk32Target.toString());
            version.setPackageUrl64(apk64Target.toString());
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

    /**
     * 查询版本。
     */
    @Transactional(readOnly = true)
    public List<AppVersionResponse> listVersions(Long appId) {
        appManagementService.requireApp(appId);
        return appVersionRepository.selectList(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appId)
        ).stream()
                .sorted(appVersionComparator())
                .map(this::toResponse)
                .toList();
    }

    /**
     * 获取版本。
     */
    @Transactional(readOnly = true)
    public AppVersionResponse getVersion(Long appId, Long versionId) {
        AppVersion version = requireVersion(versionId);
        if (!version.getAppId().equals(appId)) {
            throw new NotFoundException("Version not found under app: " + appId);
        }
        return toResponse(version);
    }

    /**
     * 校验包 Against 应用。
     */
    private void validatePackageAgainstApp(AppInfo appInfo, PackageMetadata metadata) {
        boolean iosPackage = "ipa".equalsIgnoreCase(metadata.packageType());
        if (appInfo.getAppType() == AppType.HarmonyOS && !iosPackage) {
            throw new IllegalArgumentException("HarmonyOS apps can only upload IPA packages");
        }
        if (appInfo.getAppType() == AppType.ANDROID && iosPackage) {
            throw new IllegalArgumentException("Android apps cannot upload IPA packages");
        }
    }

    /**
     * 校验Expected 元数据。
     */
    private void validateExpectedMetadata(
            PackageMetadata metadata,
            String expectedVersionName,
            String expectedVersionCode,
            Boolean expectedReinforced
    ) {
        if (expectedVersionName != null && !expectedVersionName.equals(metadata.versionName())) {
            throw new IllegalArgumentException("Version name mismatch: expected " + expectedVersionName + ", actual " + metadata.versionName());
        }
        if (expectedVersionCode != null && !VersionCodeUtil.requireNonBlank(expectedVersionCode).equals(metadata.versionCode())) {
            throw new IllegalArgumentException("Version code mismatch: expected " + expectedVersionCode + ", actual " + metadata.versionCode());
        }
        if (expectedReinforced != null && expectedReinforced != metadata.reinforced()) {
            throw new IllegalArgumentException("Reinforce status mismatch: expected " + expectedReinforced + ", actual " + metadata.reinforced());
        }
    }

    /**
     * 确保版本 Is New。
     */
    private void ensureVersionIsNew(Long appId, String versionName, String versionCode) {
        String normalizedVersionCode = VersionCodeUtil.requireNonBlank(versionCode);
        Long duplicateCount = appVersionRepository.selectCount(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appId)
                        .eq(AppVersion::getVersionName, versionName)
                        .eq(AppVersion::getVersionCode, normalizedVersionCode)
        );
        if (duplicateCount != null && duplicateCount > 0) {
            throw new IllegalArgumentException("Version already exists");
        }
    }

    /**
     * 处理响应相关逻辑。
     */
    private AppVersionResponse toResponse(AppVersion version) {
        return new AppVersionResponse(
                version.getId(),
                version.getAppId(),
                version.getVersionName(),
                version.getVersionCode(),
                version.getBuildCode(),
                version.getPackageUrl(),
                version.getPackageUrl32(),
                version.getPackageUrl64(),
                version.getUpdateLog(),
                version.getIsReinforce() != null && version.getIsReinforce() == 1,
                version.getCreateUser(),
                version.getUpdateUser(),
                version.getCreateTime()
        );
    }

    /**
     * 处理prepare Local 提交包元数据相关逻辑。
     */
    private void prepareLocalSubmitPackagesFromMetadata(AppVersion version) {
        ProjectMetadataContext metadataContext = resolveProjectMetadataContext();
        Map<String, Object> metadata = metadataContext.metadata();

        Path apk32Path = resolveProjectAssetPath(
                metadataContext.metadataPath(),
                firstNonNull(
                        metadataLookup(metadata, null, "apk32Path"),
                        metadataLookup(metadata, "vivo", "apk32Path"),
                        metadataLookup(metadata, null, "vivoApk32Path")
                )
        );
        if (apk32Path == null) {
            throw new IllegalArgumentException(APK32_LOCAL_FILE_FAILED_MESSAGE);
        }

        Path apk64Path = resolveProjectAssetPath(
                metadataContext.metadataPath(),
                firstNonNull(
                        metadataLookup(metadata, null, "apk64Path"),
                        metadataLookup(metadata, "vivo", "apk64Path"),
                        metadataLookup(metadata, null, "vivoApk64Path")
                )
        );
        if (apk64Path == null) {
            throw new IllegalArgumentException(APK64_LOCAL_FILE_FAILED_MESSAGE);
        }

        version.setPackageUrl32(apk32Path.toString());
        version.setPackageUrl64(apk64Path.toString());
        appVersionRepository.updateById(version);
        log.info(
                "Submit packages resolved from local metadata, versionId={}, versionCode={}, buildCode={}, apk32Path={}, apk64Path={}",
                version.getId(),
                version.getVersionCode(),
                version.getBuildCode(),
                apk32Path,
                apk64Path
        );
    }

    /**
     * 分配提交包路径。
     */
    private Path allocateSubmitPackagePath(String versionCode, String buildCode, String fileName) {
        try {
            return storageService.allocateDownloadPath(versionCode, buildCode, fileName);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to allocate local path for submit package", ex);
        }
    }

    /**
     * 处理应用版本 Comparator相关逻辑。
     */
    private Comparator<AppVersion> appVersionComparator() {
        return Comparator
                .comparing(AppVersion::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AppVersion::getId, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /**
     * 解析项目元数据上下文。
     */
    private ProjectMetadataContext resolveProjectMetadataContext() {
        return new ProjectMetadataContext(
                publishMetadataResolver.metadataPath(),
                publishMetadataResolver.metadata()
        );
    }

    /**
     * 解析项目 Asset 路径。
     */
    private Path resolveProjectAssetPath(Path metadataPath, Object assetLocation) {
        return publishMetadataResolver.resolveAssetPath(metadataPath, assetLocation);
    }

    /**
     * 处理元数据 Lookup相关逻辑。
     */
    private Object metadataLookup(Map<String, Object> metadata, String sectionKey, String key) {
        return publishMetadataResolver.metadataLookup(metadata, sectionKey, key);
    }

    /**
     * 获取首个Non Null。
     */
    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record ProjectMetadataContext(Path metadataPath, Map<String, Object> metadata) {
    }
}
