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
import com.app.publishservice.domain.entity.AppReleaseTaskLog;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.AppType;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.repository.AppApiTokenCacheRepository;
import com.app.publishservice.repository.AppInfoRepository;
import com.app.publishservice.repository.AppReleaseRecordRepository;
import com.app.publishservice.repository.AppReleaseTaskLogRepository;
import com.app.publishservice.repository.AppStoreConfigRepository;
import com.app.publishservice.repository.AppVersionRepository;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AppManagementService {

    private final AppInfoRepository appInfoRepository;
    private final AppStoreConfigRepository storeConfigRepository;
    private final AppVersionRepository appVersionRepository;
    private final AppReleaseRecordRepository releaseRecordRepository;
    private final AppReleaseTaskLogRepository releaseTaskLogRepository;
    private final AppApiTokenCacheRepository tokenCacheRepository;

    public AppManagementService(
            AppInfoRepository appInfoRepository,
            AppStoreConfigRepository storeConfigRepository,
            AppVersionRepository appVersionRepository,
            AppReleaseRecordRepository releaseRecordRepository,
            AppReleaseTaskLogRepository releaseTaskLogRepository,
            AppApiTokenCacheRepository tokenCacheRepository
    ) {
        this.appInfoRepository = appInfoRepository;
        this.storeConfigRepository = storeConfigRepository;
        this.appVersionRepository = appVersionRepository;
        this.releaseRecordRepository = releaseRecordRepository;
        this.releaseTaskLogRepository = releaseTaskLogRepository;
        this.tokenCacheRepository = tokenCacheRepository;
    }

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

    @Transactional
    public AppResponse updateApp(Long appId, AppUpsertRequest request) {
        request.setId(appId);
        return saveApp(request);
    }

    @Transactional
    public void deleteApp(Long appId) {
        requireApp(appId);

        List<Long> releaseRecordIds = releaseRecordRepository.selectList(
                Wrappers.<AppReleaseRecord>lambdaQuery()
                        .eq(AppReleaseRecord::getAppId, appId)
                        .select(AppReleaseRecord::getId)
        ).stream().map(AppReleaseRecord::getId).toList();
        if (!releaseRecordIds.isEmpty()) {
            releaseTaskLogRepository.delete(
                    Wrappers.<AppReleaseTaskLog>lambdaQuery().in(AppReleaseTaskLog::getReleaseRecordId, releaseRecordIds)
            );
        }
        releaseRecordRepository.delete(Wrappers.<AppReleaseRecord>lambdaQuery().eq(AppReleaseRecord::getAppId, appId));
        appVersionRepository.delete(Wrappers.<AppVersion>lambdaQuery().eq(AppVersion::getAppId, appId));
        appInfoRepository.deleteById(appId);
    }

    @Transactional(readOnly = true)
    public AppInfo requireApp(Long appId) {
        AppInfo appInfo = appInfoRepository.selectById(appId);
        if (appInfo == null) {
            throw new NotFoundException("App not found");
        }
        return appInfo;
    }

    @Transactional(readOnly = true)
    public AppDetailResponse getApp(Long appId) {
        AppInfo appInfo = requireApp(appId);
        List<AppVersionResponse> versions = appVersionRepository.selectList(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appId)
                        .orderByDesc(AppVersion::getVersionCode)
                        .orderByDesc(AppVersion::getCreateTime)
        ).stream().map(this::toVersionResponse).toList();
        List<ReleaseRecordResponse> releaseRecords = releaseRecordRepository.selectReleaseRecordsByAppId(appId).stream()
                .map(this::toReleaseRecordResponse)
                .toList();
        return toDetailResponse(appInfo, versions, releaseRecords);
    }

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

    @Transactional(readOnly = true)
    public List<AppStoreConfig> listStoreConfigs() {
        return storeConfigRepository.selectList(
                Wrappers.<AppStoreConfig>lambdaQuery()
                        .orderByAsc(AppStoreConfig::getStoreType)
                        .orderByAsc(AppStoreConfig::getId)
        );
    }

    @Transactional(readOnly = true)
    public List<StoreConfigResponse> getStoreConfigResponses() {
        return listStoreConfigs().stream().map(this::toStoreConfigResponse).toList();
    }

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

    @Transactional(readOnly = true)
    public AppStoreConfig requireStoreConfig(StoreType storeType) {
        AppStoreConfig storeConfig = findStoreConfigByStoreType(storeType).orElse(null);
        if (storeConfig == null) {
            throw new NotFoundException("Store config not found: " + storeType.getCode());
        }
        return storeConfig;
    }

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
        config.setMiPublicKey(request.getMiPublicKey());
        config.setMiPrivateKey(request.getMiPrivateKey());
        config.setToken(request.getToken());
        config.setIpWhitelist(request.getIpWhitelist());
        config.setApiStatus(request.getApiStatus() == null ? 1 : request.getApiStatus());
        saveOrUpdate(storeConfigRepository, config);
        return toStoreConfigResponse(requireStoreConfigById(config.getId()));
    }

    @Transactional
    public StoreConfigResponse updateStoreConfig(Long configId, StoreConfigRequest request) {
        request.setId(configId);
        return saveStoreConfig(request);
    }

    @Transactional(readOnly = true)
    public StoreConfigResponse getStoreConfig(Long configId) {
        return toStoreConfigResponse(requireStoreConfigById(configId));
    }

    @Transactional
    public void deleteStoreConfig(Long configId) {
        requireStoreConfigById(configId);
        tokenCacheRepository.delete(
                Wrappers.<com.app.publishservice.domain.entity.AppApiTokenCache>lambdaQuery()
                        .eq(com.app.publishservice.domain.entity.AppApiTokenCache::getStoreConfigId, configId)
        );
        storeConfigRepository.deleteById(configId);
    }

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

    private AppResponse toResponse(AppInfo appInfo) {
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
                appInfo.getUpdateTime()
        );
    }

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

    private AppVersionResponse toVersionResponse(AppVersion version) {
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

    private StoreConfigResponse toStoreConfigResponse(AppStoreConfig config) {
        return new StoreConfigResponse(
                config.getId(),
                config.getStoreType().getCode(),
                config.getAccountName(),
                config.getEmail(),
                config.getPhone(),
                config.getClientId(),
                config.getClientSecret(),
                config.getMiPublicKey(),
                config.getMiPrivateKey(),
                config.getToken(),
                config.getIpWhitelist(),
                config.getApiStatus(),
                config.getCreateUser(),
                config.getUpdateUser(),
                config.getCreateTime(),
                config.getUpdateTime()
        );
    }

    private <T> void saveOrUpdate(BaseMapper<T> mapper, T entity) {
        BeanWrapper wrapper = new BeanWrapperImpl(entity);
        Object id = wrapper.getPropertyValue("id");
        if (id == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    private void initializeAppVersionIfNeeded(AppInfo appInfo, Integer versionCode, String buildCode) {
        String normalizedBuildCode = normalizeBuildCode(buildCode);
        if (versionCode == null) {
            if (buildCode != null) {
                throw new IllegalArgumentException("buildCode requires versionCode");
            }
            return;
        }
        if (versionCode < 1) {
            throw new IllegalArgumentException("Version code must be greater than 0");
        }

        AppVersion existingVersion = appVersionRepository.selectOne(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appInfo.getId())
                        .eq(AppVersion::getVersionCode, versionCode)
                        .last("limit 1")
        );
        if (existingVersion != null) {
            boolean changed = false;
            String resolvedVersionName = String.valueOf(versionCode);
            if (!Objects.equals(existingVersion.getVersionName(), resolvedVersionName)) {
                existingVersion.setVersionName(resolvedVersionName);
                changed = true;
            }
            if (!Objects.equals(existingVersion.getBuildCode(), normalizedBuildCode)) {
                existingVersion.setBuildCode(normalizedBuildCode);
                existingVersion.setPackageUrlLow(null);
                existingVersion.setPackageUrlHigh(null);
                changed = true;
            }
            if (changed) {
                appVersionRepository.updateById(existingVersion);
            }
            return;
        }

        AppVersion latestVersion = appVersionRepository.selectOne(
                Wrappers.<AppVersion>lambdaQuery()
                        .eq(AppVersion::getAppId, appInfo.getId())
                        .orderByDesc(AppVersion::getVersionCode)
                        .orderByDesc(AppVersion::getCreateTime)
                        .last("limit 1")
        );
        if (latestVersion != null && versionCode <= latestVersion.getVersionCode()) {
            throw new IllegalArgumentException("Version code must increase, latest=" + latestVersion.getVersionCode());
        }

        AppVersion initialVersion = new AppVersion();
        initialVersion.setAppId(appInfo.getId());
        initialVersion.setAppInfo(appInfo);
        initialVersion.setVersionName(String.valueOf(versionCode));
        initialVersion.setVersionCode(versionCode);
        initialVersion.setBuildCode(normalizedBuildCode);
        initialVersion.setUpdateLog("创建应用时自动初始化版本记录");
        initialVersion.setIsReinforce(0);
        appVersionRepository.insert(initialVersion);
    }

    private String normalizeBuildCode(String buildCode) {
        if (!StringUtils.hasText(buildCode)) {
            return null;
        }
        return buildCode.trim();
    }

    private long normalizeCurrent(Long current) {
        return current == null || current < 1 ? 1 : current;
    }

    private long normalizeSize(Long size) {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, 100);
    }
}
