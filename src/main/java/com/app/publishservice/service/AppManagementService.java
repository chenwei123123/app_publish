package com.app.publishservice.service;

import com.app.publishservice.api.dto.AppDetailResponse;
import com.app.publishservice.api.dto.AppResponse;
import com.app.publishservice.api.dto.AppUpsertRequest;
import com.app.publishservice.api.dto.AppVersionResponse;
import com.app.publishservice.api.dto.PageResponse;
import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.StoreConfigRequest;
import com.app.publishservice.api.dto.StoreConfigResponse;
import com.app.publishservice.common.exception.NotFoundException;
import com.app.publishservice.domain.entity.AppInfo;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.AppType;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.util.VersionCodeUtil;
import com.app.publishservice.repository.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AppManagementService {

    private final AppInfoRepository appInfoRepository;
    private final AppStoreConfigRepository storeConfigRepository;
    private final AppVersionRepository appVersionRepository;
    private final AppReleaseRecordRepository releaseRecordRepository;
    private final AppApiTokenCacheRepository tokenCacheRepository;
    private final AppStoreRequestLogRepository appStoreRequestLogRepository;

    /**
     * 初始化AppManagementService。
     */
    public AppManagementService(
            AppInfoRepository appInfoRepository,
            AppStoreConfigRepository storeConfigRepository,
            AppVersionRepository appVersionRepository,
            AppReleaseRecordRepository releaseRecordRepository,
            AppApiTokenCacheRepository tokenCacheRepository,
            AppStoreRequestLogRepository appStoreRequestLogRepository
    ) {
        this.appInfoRepository = appInfoRepository;
        this.storeConfigRepository = storeConfigRepository;
        this.appVersionRepository = appVersionRepository;
        this.releaseRecordRepository = releaseRecordRepository;
        this.tokenCacheRepository = tokenCacheRepository;
        this.appStoreRequestLogRepository = appStoreRequestLogRepository;
    }

    /**
     * 保存应用。
     */
    @Transactional
    public AppResponse saveApp(AppUpsertRequest request) {
        AppInfo appInfo = request.getId() == null ? new AppInfo() : requireApp(request.getId());
        appInfo.setAppName(request.getAppName());
        appInfo.setPackageName(request.getPackageName());
        appInfo.setAppType(AppType.fromCode(request.getAppType()));
        appInfo.setAppDescription(request.getAppDescription());
        appInfo.setCopyrightNo(request.getCopyrightNo());
        appInfo.setIcpNo(request.getIcpNo());
        appInfo.setAppRecordNo(request.getAppRecordNo());
        appInfo.setPrivacyUrl(request.getPrivacyUrl());
        appInfo.setUserAgreementUrl(request.getUserAgreementUrl());
        appInfo.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        saveOrUpdate(appInfoRepository, appInfo);
        initializeAppVersionIfNeeded(appInfo, request.getVersionCode(), request.getBuildCode());
        return toResponse(appInfo);
    }

    /**
     * 更新应用。
     */
    @Transactional
    public AppResponse updateApp(Long appId, AppUpsertRequest request) {
        request.setId(appId);
        return saveApp(request);
    }

    /**
     * 删除应用。
     */
    @Transactional
    public void deleteApp(Long appId) {
        requireApp(appId);
        List<AppReleaseRecord> releaseRecords = releaseRecordRepository.selectList(
                Wrappers.<AppReleaseRecord>lambdaQuery()
                        .eq(AppReleaseRecord::getAppId, appId)
        );
        if (!releaseRecords.isEmpty()) {
            List<Long> releaseRecordIds = new ArrayList<>(releaseRecords.size());
            for (AppReleaseRecord releaseRecord : releaseRecords) {
                if (releaseRecord.getId() != null) {
                    releaseRecordIds.add(releaseRecord.getId());
                }
            }
            if (!releaseRecordIds.isEmpty()) {
                appStoreRequestLogRepository.delete(
                        Wrappers.<com.app.publishservice.domain.entity.AppStoreRequestLog>lambdaQuery()
                                .in(com.app.publishservice.domain.entity.AppStoreRequestLog::getReleaseRecordId, releaseRecordIds)
                );
            }
        }
        releaseRecordRepository.delete(Wrappers.<AppReleaseRecord>lambdaQuery().eq(AppReleaseRecord::getAppId, appId));
        appVersionRepository.delete(Wrappers.<AppVersion>lambdaQuery().eq(AppVersion::getAppId, appId));
        appInfoRepository.deleteById(appId);
    }

    /**
     * 校验应用。
     */
    @Transactional(readOnly = true)
    public AppInfo requireApp(Long appId) {
        AppInfo appInfo = appInfoRepository.selectById(appId);
        if (appInfo == null) {
            throw new NotFoundException("App not found");
        }
        return appInfo;
    }

    /**
     * 获取应用。
     */
    @Transactional(readOnly = true)
    public AppDetailResponse getApp(Long appId) {
        AppInfo appInfo = requireApp(appId);
        List<AppVersionResponse> versions = listVersionResponses(appId);
        List<ReleaseRecordResponse> releaseRecords = listReleaseRecordResponses(appId);
        return toDetailResponse(appInfo, versions, releaseRecords);
    }

    /**
     * 查询应用。
     */
    @Transactional(readOnly = true)
    public List<AppResponse> listApps( String keyword) {
        return appInfoRepository.selectList(
                Wrappers.<AppInfo>lambdaQuery()
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(AppInfo::getAppName, keyword)
                                .or()
                                .like(AppInfo::getAppDescription, keyword)
                                .or()
                                .like(AppInfo::getPackageName, keyword))
                        .orderByDesc(AppInfo::getUpdateTime)
                        .orderByDesc(AppInfo::getId)
        ).stream().map(this::toResponse).toList();
    }

    /**
     * 查询商店配置。
     */
    @Transactional(readOnly = true)
    public List<AppStoreConfig> listStoreConfigs() {
        return storeConfigRepository.selectList(
                Wrappers.<AppStoreConfig>lambdaQuery()
                        .orderByAsc(AppStoreConfig::getStoreType)
                        .orderByAsc(AppStoreConfig::getId)
        );
    }

    /**
     * 获取商店配置响应。
     */
    @Transactional(readOnly = true)
    public List<StoreConfigResponse> getStoreConfigResponses() {
        return listStoreConfigs().stream().map(this::toStoreConfigResponse).toList();
    }

    /**
     * 处理分页商店配置响应相关逻辑。
     */
    @Transactional(readOnly = true)
    public PageResponse<StoreConfigResponse> pageStoreConfigResponses(Long current, Long size, String key) {
        long pageCurrent = normalizeCurrent(current);
        long pageSize = normalizeSize(size);
        String keyFilter = StringUtils.hasText(key) ? key.trim() : null;

        Page<AppStoreConfig> page = storeConfigRepository.selectPage(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<AppStoreConfig>lambdaQuery().and(StringUtils.hasText(keyFilter), q -> q
                        // 在这里加多个字段
                        .like(AppStoreConfig::getStoreType, keyFilter)
                        .or()
                        .like(AppStoreConfig::getAccountName, keyFilter)
                        .or()
                        .like(AppStoreConfig::getEmail, keyFilter)
                        .or()
                        .like(AppStoreConfig::getPhone, keyFilter)
                        .or()
                        .like(AppStoreConfig::getPrivacyUrl, keyFilter)
                        .or()
                        .like(AppStoreConfig::getAppId, keyFilter)
                ).orderByAsc(AppStoreConfig::getStoreType)
                        .orderByAsc(AppStoreConfig::getId)
        );
        return PageResponse.of(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getRecords().stream().map(this::toStoreConfigResponse).toList()
        );
    }

    /**
     * 校验商店配置。
     */
    @Transactional(readOnly = true)
    public AppStoreConfig requireStoreConfig(StoreType storeType) {
        AppStoreConfig storeConfig = findStoreConfigByStoreType(storeType).orElse(null);
        if (storeConfig == null) {
            throw new NotFoundException("Store config not found: " + storeType.getCode());
        }
        return storeConfig;
    }

    /**
     * 校验商店配置 Id。
     */
    @Transactional(readOnly = true)
    public AppStoreConfig requireStoreConfigById(Long configId) {
        AppStoreConfig storeConfig = storeConfigRepository.selectOne(
                Wrappers.<AppStoreConfig>lambdaQuery()
                        .eq(AppStoreConfig::getId, configId)
                        .last("limit 1")
        );
        if (storeConfig == null) {
            throw new NotFoundException("Store config not found: " + configId);
        }
        return storeConfig;
    }

    /**
     * 查找商店配置商店类型。
     */
    @Transactional(readOnly = true)
    public Optional<AppStoreConfig> findStoreConfigByStoreType(StoreType storeType) {
        return Optional.ofNullable(
                storeConfigRepository.selectOne(
                        Wrappers.<AppStoreConfig>lambdaQuery()
                                .eq(AppStoreConfig::getStoreType, storeType)
                                .last("limit 1")
                )
        );
    }

    /**
     * 保存商店配置。
     */
    @Transactional
    public StoreConfigResponse saveStoreConfig(StoreConfigRequest request) {
        StoreType storeType = StoreType.fromCode(request.getStoreType());
        AppStoreConfig config = request.getId() == null
                ? findStoreConfigByStoreType(storeType).orElseGet(AppStoreConfig::new)
                : requireStoreConfigById(request.getId());
        config.setStoreType(storeType);
        config.setAccountName(request.getAccountName());
        config.setEmail(request.getEmail());
        config.setPhone(request.getPhone());
        config.setClientId(request.getClientId());
        config.setClientSecret(request.getClientSecret());
        config.setPublicKey(resolveStoreConfigPublicKey(storeType, config, request));
        config.setPrivateKey(request.getPrivateKey());
        config.setToken(request.getToken());
        config.setIpWhitelist(request.getIpWhitelist());
        config.setPrivacyUrl(normalizeNullableText(request.getPrivacyUrl()));
        config.setAppId(normalizeNullableText(request.getAppId()));
        config.setIcon(resolveStoreConfigIcon(config, request));
        config.setApiStatus(request.getApiStatus() == null ? 1 : request.getApiStatus());
        saveOrUpdate(storeConfigRepository, config);
        return toStoreConfigResponse(requireStoreConfigById(config.getId()));
    }

    /**
     * 更新商店配置。
     */
    @Transactional
    public StoreConfigResponse updateStoreConfig(Long configId, StoreConfigRequest request) {
        request.setId(configId);
        return saveStoreConfig(request);
    }

    /**
     * 获取商店配置。
     */
    @Transactional(readOnly = true)
    public StoreConfigResponse getStoreConfig(Long configId) {
        return toStoreConfigResponse(requireStoreConfigById(configId));
    }

    /**
     * 删除商店配置。
     */
    @Transactional
    public void deleteStoreConfig(Long configId) {
        requireStoreConfigById(configId);
        appStoreRequestLogRepository.delete(Wrappers.<com.app.publishservice.domain.entity.AppStoreRequestLog>lambdaQuery()
                .eq(com.app.publishservice.domain.entity.AppStoreRequestLog::getStoreConfigId, configId));
        tokenCacheRepository.delete(
                Wrappers.<com.app.publishservice.domain.entity.AppApiTokenCache>lambdaQuery()
                        .eq(com.app.publishservice.domain.entity.AppApiTokenCache::getStoreConfigId, configId)
        );
        storeConfigRepository.deleteById(configId);
    }

    /**
     * 更新商店配置状态。
     */
    @Transactional
    public StoreConfigResponse updateStoreConfigStatus(Long configId, Integer apiStatus) {
        if (apiStatus == null || (apiStatus != 0 && apiStatus != 1)) {
            throw new IllegalArgumentException("apiStatus must be 0 or 1");
        }
        AppStoreConfig storeConfig = requireStoreConfigById(configId);
        storeConfig.setApiStatus(apiStatus);
        storeConfigRepository.updateById(storeConfig);
        return toStoreConfigResponse(requireStoreConfigById(configId));
    }

    /**
     * 处理响应相关逻辑。
     */
    private AppResponse toResponse(AppInfo appInfo) {
        List<AppVersionResponse> versions = listVersionResponses(appInfo.getId());
        List<ReleaseRecordResponse> releaseRecords = listReleaseRecordResponses(appInfo.getId());
        return new AppResponse(
                appInfo.getId(),
                appInfo.getAppName(),
                appInfo.getPackageName(),
                appInfo.getAppType().getCode(),
                appInfo.getAppDescription(),
                appInfo.getCopyrightNo(),
                appInfo.getIcpNo(),
                appInfo.getAppRecordNo(),
                appInfo.getPrivacyUrl(),
                appInfo.getUserAgreementUrl(),
                appInfo.getStatus(),
                appInfo.getCreateUser(),
                appInfo.getUpdateUser(),
                appInfo.getCreateTime(),
                appInfo.getUpdateTime(),
                versions,
                releaseRecords
        );
    }

    /**
     * 处理详情响应相关逻辑。
     */
    private AppDetailResponse toDetailResponse(
            AppInfo appInfo,
            List<AppVersionResponse> versions,
            List<ReleaseRecordResponse> releaseRecords
    ) {
        return new AppDetailResponse(
                appInfo.getId(),
                appInfo.getAppName(),
                appInfo.getPackageName(),
                appInfo.getAppType().getCode(),
                appInfo.getAppDescription(),
                appInfo.getCopyrightNo(),
                appInfo.getIcpNo(),
                appInfo.getAppRecordNo(),
                appInfo.getPrivacyUrl(),
                appInfo.getUserAgreementUrl(),
                appInfo.getStatus(),
                appInfo.getCreateUser(),
                appInfo.getUpdateUser(),
                appInfo.getCreateTime(),
                appInfo.getUpdateTime(),
                versions,
                releaseRecords
        );
    }

    /**
     * 处理版本响应相关逻辑。
     */
    private AppVersionResponse toVersionResponse(AppVersion version) {
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
     * 查询版本响应。
     */
    private List<AppVersionResponse> listVersionResponses(Long appId) {
        return appVersionRepository.selectList(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appId)
        ).stream()
                .sorted(appVersionComparator())
                .map(this::toVersionResponse)
                .toList();
    }

    /**
     * 查询发布记录响应。
     */
    private List<ReleaseRecordResponse> listReleaseRecordResponses(Long appId) {
        return releaseRecordRepository.selectReleaseRecordsByAppId(appId).stream()
                .map(this::toReleaseRecordResponse)
                .toList();
    }

    /**
     * 处理发布记录响应相关逻辑。
     */
    private ReleaseRecordResponse toReleaseRecordResponse(AppReleaseRecord record) {
        return new ReleaseRecordResponse(
                record.getId(),
                record.getAppId(),
                record.getAppName(),
                record.getPackageName(),
                record.getAppDescription(),
                record.getVersionId(),
                record.getVersionCode(),
                record.getStoreType() == null ? null : record.getStoreType().getCode(),
                record.getReleaseMode() == null ? null : record.getReleaseMode().getCode(),
                record.getReleaseType(),
                record.getGrayPercent(),
                record.getGrayStartTime(),
                record.getGrayEndTime(),
                record.getReleaseStatus() == null ? null : record.getReleaseStatus().getCode(),
                record.getStoreReleaseId(),
                record.getRejectReason(),
                record.getApiRequestLog(),
                record.getApiResponseLog(),
                record.getReleaseTime(),
                record.getFinishTime(),
                record.getCreateUser(),
                record.getUpdateUser()
        );
    }

    /**
     * 处理商店配置响应相关逻辑。
     */
    private StoreConfigResponse toStoreConfigResponse(AppStoreConfig config) {
        return new StoreConfigResponse(
                config.getId(),
                config.getStoreType().getCode(),
                config.getAccountName(),
                config.getEmail(),
                config.getPhone(),
                config.getClientId(),
                config.getClientSecret(),
                config.getPublicKey(),
                config.getPrivateKey(),
                config.getToken(),
                config.getIpWhitelist(),
                config.getPrivacyUrl(),
                config.getIcon(),
                config.getAppId(),
                config.getApiStatus(),
                config.getCreateUser(),
                config.getUpdateUser(),
                config.getCreateTime(),
                config.getUpdateTime()
        );
    }

    private String resolveStoreConfigIcon(AppStoreConfig config, StoreConfigRequest request) {
        MultipartFile iconFile = request.getIconFile();
        if (iconFile != null && !iconFile.isEmpty()) {
            try {
                return Base64.getEncoder().encodeToString(iconFile.getBytes());
            } catch (IOException ex) {
                throw new IllegalStateException("读取图标文件失败", ex);
            }
        }
        if (request.getIcon() != null) {
            return normalizeStoreConfigIcon(request.getIcon());
        }
        return config.getIcon();
    }

    private String resolveStoreConfigPublicKey(StoreType storeType, AppStoreConfig config, StoreConfigRequest request) {
        MultipartFile publicKeyFile = request.getPublicKeyFile();
        if (publicKeyFile != null && !publicKeyFile.isEmpty()) {
            try {
                return normalizeStoreConfigPublicKey(storeType, publicKeyFile.getBytes());
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read public key file", ex);
            }
        }
        if (request.getPublicKey() != null) {
            return normalizeNullableText(request.getPublicKey());
        }
        return config.getPublicKey();
    }

    private String normalizeStoreConfigPublicKey(StoreType storeType, byte[] publicKeyBytes) {
        if (storeType != null && "xiaomi".equalsIgnoreCase(storeType.getCode())) {
            String certificatePublicKey = tryExtractCertificatePublicKey(publicKeyBytes);
            if (certificatePublicKey != null) {
                return certificatePublicKey;
            }
        }
        return normalizeNullableText(new String(publicKeyBytes, StandardCharsets.UTF_8));
    }

    private String tryExtractCertificatePublicKey(byte[] certificateBytes) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(
                    new ByteArrayInputStream(certificateBytes)
            );
            PublicKey publicKey = certificate.getPublicKey();
            return formatPublicKeyPem(publicKey.getEncoded());
        } catch (GeneralSecurityException ex) {
            return null;
        }
    }

    private String formatPublicKeyPem(byte[] publicKeyBytes) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(publicKeyBytes)
                + "\n-----END PUBLIC KEY-----";
    }

    private String normalizeStoreConfigIcon(String value) {
        String normalized = normalizeNullableText(value);
        if (normalized == null) {
            return null;
        }
        String base64Value = normalized;
        if (normalized.startsWith("data:")) {
            int commaIndex = normalized.indexOf(',');
            if (commaIndex < 0) {
                throw new IllegalArgumentException("icon data uri format is invalid");
            }
            base64Value = normalized.substring(commaIndex + 1).trim();
        }
        try {
            Base64.getDecoder().decode(base64Value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("icon must be a valid base64 image");
        }
        return base64Value;
    }

    private String normalizeNullableText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 保存Update。
     */
    private <T> void saveOrUpdate(BaseMapper<T> mapper, T entity) {
        BeanWrapper wrapper = new BeanWrapperImpl(entity);
        Object id = wrapper.getPropertyValue("id");
        if (id == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    /**
     * 处理initialize 应用版本 If Needed相关逻辑。
     */
    private void initializeAppVersionIfNeeded(AppInfo appInfo, String versionCode, String buildCode) {
        String normalizedBuildCode = normalizeBuildCode(buildCode);
        String normalizedVersionCode = VersionCodeUtil.normalize(versionCode);
        if (normalizedVersionCode == null) {
            if (buildCode != null) {
                throw new IllegalArgumentException("buildCode requires versionCode");
            }
            return;
        }
        normalizedVersionCode = VersionCodeUtil.requireNonBlank(normalizedVersionCode);

        AppVersion existingVersion = appVersionRepository.selectOne(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appInfo.getId())
                        .eq(AppVersion::getVersionCode, normalizedVersionCode)
                        .last("limit 1")
        );
        if (existingVersion != null) {

            if (!Objects.equals(existingVersion.getVersionName(), normalizedVersionCode)) {
                existingVersion.setVersionName(normalizedVersionCode);
            }
            if (!Objects.equals(existingVersion.getBuildCode(), normalizedBuildCode)) {
                existingVersion.setBuildCode(normalizedBuildCode);
                existingVersion.setPackageUrl32(null);
                existingVersion.setPackageUrl64(null);
            }
            existingVersion.setCreateTime(LocalDateTime.now());
            appVersionRepository.updateById(existingVersion);

            return;
        }

        AppVersion initialVersion = new AppVersion();
        initialVersion.setAppId(appInfo.getId());
        initialVersion.setAppInfo(appInfo);
        initialVersion.setVersionName(normalizedVersionCode);
        initialVersion.setVersionCode(normalizedVersionCode);
        initialVersion.setBuildCode(normalizedBuildCode);
        initialVersion.setUpdateLog("创建应用时自动初始化版本记录");
        initialVersion.setIsReinforce(0);
        appVersionRepository.insert(initialVersion);
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
     * 规范化Build 编码。
     */
    private String normalizeBuildCode(String buildCode) {
        if (!StringUtils.hasText(buildCode)) {
            return null;
        }
        return buildCode.trim();
    }

    /**
     * 规范化Current。
     */
    private long normalizeCurrent(Long current) {
        return current == null || current < 1 ? 1 : current;
    }

    /**
     * 规范化Size。
     */
    private long normalizeSize(Long size) {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, 100);
    }
}
