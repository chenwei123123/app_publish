package com.app.publishservice.service;

import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.ReleaseRecordPageResponse;
import com.app.publishservice.api.dto.ReleaseSubmitRequest;
import com.app.publishservice.api.dto.ReleaseTaskLogResponse;
import com.app.publishservice.api.dto.PageResponse;
import com.app.publishservice.common.exception.NotFoundException;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppReleaseTaskLog;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseMode;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.repository.AppReleaseRecordRepository;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReleaseOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrchestrationService.class);

    private final PackageVersionService packageVersionService;
    private final AppManagementService appManagementService;
    private final AppReleaseRecordRepository releaseRecordRepository;
    private final TokenService tokenService;
    private final StorePublisher storePublisher;
    private final ReleaseLogService releaseLogService;

    public ReleaseOrchestrationService(
            PackageVersionService packageVersionService,
            AppManagementService appManagementService,
            AppReleaseRecordRepository releaseRecordRepository,
            TokenService tokenService,
            StorePublisher storePublisher,
            ReleaseLogService releaseLogService
    ) {
        this.packageVersionService = packageVersionService;
        this.appManagementService = appManagementService;
        this.releaseRecordRepository = releaseRecordRepository;
        this.tokenService = tokenService;
        this.storePublisher = storePublisher;
        this.releaseLogService = releaseLogService;
    }

    @Transactional
    public List<ReleaseRecordResponse> submit(ReleaseSubmitRequest request) {
        log.info("Start release submit, versionId={}, releaseMode={}, stores={}", request.getVersionId(), request.getReleaseMode(), request.getStoreTypes());
        AppVersion version = packageVersionService.requireVersion(request.getVersionId());
        List<ReleaseRecordResponse> responses = new ArrayList<>();
        ReleaseMode releaseMode = ReleaseMode.fromCode(request.getReleaseMode());
        Long releaseType = normalizeReleaseType(request.getReleaseType());
        validateReleaseRequest(request, releaseType);
        for (String storeTypeCode : request.getStoreTypes()) {
            StoreType storeType = StoreType.fromCode(storeTypeCode);
            AppStoreConfig storeConfig = appManagementService.requireStoreConfig(storeType);
            if (storeConfig.getApiStatus() != 1) {
                throw new IllegalArgumentException("Store config is disabled: " + storeTypeCode);
            }

            AppReleaseRecord record = new AppReleaseRecord();
            record.setAppId(version.getAppId());
            record.setVersionId(version.getId());
            record.setAppInfo(version.getAppInfo());
            record.setAppVersion(version);
            record.setAppName(version.getAppInfo() == null ? null : version.getAppInfo().getAppName());
            record.setPackageName(version.getAppInfo() == null ? null : version.getAppInfo().getPackageName());
            record.setAppDescription(version.getAppInfo() == null ? null : version.getAppInfo().getAppDescription());
            record.setVersionCode(version.getVersionCode());
            record.setStoreType(storeType);
            record.setReleaseMode(releaseMode);
            record.setReleaseType(releaseType);
            record.setGrayPercent(releaseType == 2L ? request.getGrayPercent() : null);
            record.setGrayStartTime(releaseType == 2L ? request.getGrayStartTime() : null);
            record.setGrayEndTime(releaseType == 2L ? request.getGrayEndTime() : null);
            record.setReleaseStatus(ReleaseStatus.API_PENDING);
            releaseRecordRepository.insert(record);
            log.info("Release record created, releaseId={}, appId={}, versionId={}, storeType={}", record.getId(), record.getAppId(), record.getVersionId(), storeType.getCode());
            releaseLogService.log(record, "CREATE_RELEASE", null, ReleaseStatus.API_PENDING, "Release task created", null);

            try {
                String token = tokenService.getValidToken(storeConfig);
                StoreSubmitResult result = storePublisher.submitRelease(storeConfig, version, record, token);
                record.setStoreReleaseId(result.storeReleaseId());
                record.setApiRequestLog(result.requestLog());
                record.setApiResponseLog(result.responseLog());
                record.setReleaseStatus(ReleaseStatus.AUDITING);
                record.setReleaseTime(LocalDateTime.now());
                releaseRecordRepository.updateById(record);
                log.info(
                        "Submit review success, releaseId={}, storeType={}, storeReleaseId={}, status={}",
                        record.getId(),
                        storeType.getCode(),
                        record.getStoreReleaseId(),
                        record.getReleaseStatus().getCode()
                );
                releaseLogService.log(record, "SUBMIT_REVIEW", ReleaseStatus.API_PENDING, ReleaseStatus.AUDITING, result.message(), result.responseLog());
            } catch (Exception ex) {
                record.setReleaseStatus(ReleaseStatus.REJECT);
                record.setRejectReason(ex.getMessage());
                record.setApiResponseLog(ex.getMessage());
                record.setFinishTime(LocalDateTime.now());
                releaseRecordRepository.updateById(record);
                log.error("Submit review failed, releaseId={}, storeType={}", record.getId(), storeType.getCode(), ex);
                releaseLogService.log(record, "SUBMIT_FAILED", ReleaseStatus.API_PENDING, ReleaseStatus.REJECT, "Submit review failed", ex.getMessage());
            }
            responses.add(toResponse(record));
        }
        return responses;
    }

    @Transactional
    public void pollAuditResults() {
        List<AppReleaseRecord> records = releaseRecordRepository.selectReviewingReleaseRecords(ReleaseStatus.AUDITING.getCode());
        log.info("Start polling audit results, pendingCount={}", records.size());
        for (AppReleaseRecord record : records) {
            AppStoreConfig storeConfig = appManagementService.requireStoreConfig(record.getStoreType());
            String token = tokenService.getValidToken(storeConfig);
            StoreReviewResult reviewResult = storePublisher.queryReview(storeConfig, record, token);
            ReleaseStatus before = record.getReleaseStatus();
            record.setApiResponseLog(reviewResult.responseLog());
            if (reviewResult.releaseStatus() != before) {
                record.setReleaseStatus(reviewResult.releaseStatus());
                record.setRejectReason(reviewResult.rejectReason());
                if (reviewResult.releaseStatus().isFinished()) {
                    record.setFinishTime(LocalDateTime.now());
                }
                releaseRecordRepository.updateById(record);
                log.info(
                        "Review status updated, releaseId={}, storeType={}, before={}, after={}",
                        record.getId(),
                        record.getStoreType().getCode(),
                        before.getCode(),
                        reviewResult.releaseStatus().getCode()
                );
                releaseLogService.log(record, "POLL_REVIEW", before, reviewResult.releaseStatus(), "Review status updated", reviewResult.responseLog());
            } else {
                releaseRecordRepository.updateById(record);
                log.debug("Review status unchanged, releaseId={}, storeType={}, status={}", record.getId(), record.getStoreType().getCode(), before.getCode());
                releaseLogService.log(record, "POLL_REVIEW", before, before, "Review status unchanged", reviewResult.responseLog());
            }
        }
    }

    @Transactional(readOnly = true)
    public ReleaseRecordResponse getReleaseRecord(Long releaseId) {
        AppReleaseRecord record = releaseRecordRepository.selectReleaseRecordDetail(releaseId);
        if (record == null) {
            throw new NotFoundException("Release record not found");
        }
        return toResponse(record);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReleaseRecordPageResponse> pageReleaseRecords(
            Long current,
            Long size,
            String key
    ) {
        long pageCurrent = normalizeCurrent(current);
        long pageSize = normalizeSize(size);
        String keyFilter = StringUtils.hasText(key) ? key.trim() : null;

        Page<AppReleaseRecord> page = releaseRecordRepository.selectReleaseRecordPage(
                new Page<>(pageCurrent, pageSize),
                keyFilter,
                null
        );
        return PageResponse.of(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getRecords().stream().map(this::toPageResponse).toList()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<ReleaseRecordPageResponse> queryReleaseByAppId(
            Long appId
    ) {
        long pageCurrent = normalizeCurrent(null);
        long pageSize = normalizeSize(null);

        Page<AppReleaseRecord> page = releaseRecordRepository.selectReleaseRecordPage(
                new Page<>(pageCurrent, pageSize),
                null,
                appId
        );
        return PageResponse.of(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getRecords().stream().map(this::toPageResponse).toList()
        );
    }

    @Transactional(readOnly = true)
    public List<ReleaseTaskLogResponse> getReleaseTaskLogs(Long releaseId) {
        requireReleaseRecord(releaseId);
        return releaseLogService.listByReleaseRecordId(releaseId).stream()
                .map(this::toLogResponse)
                .toList();
    }

    private ReleaseRecordResponse toResponse(AppReleaseRecord record) {
        return new ReleaseRecordResponse(
                record.getId(),
                record.getAppId(),
                record.getAppName(),
                record.getPackageName(),
                record.getAppDescription(),
                record.getVersionId(),
                record.getVersionCode(),
                record.getStoreType().getCode(),
                record.getReleaseMode().getCode(),
                record.getReleaseType(),
                record.getGrayPercent(),
                record.getGrayStartTime(),
                record.getGrayEndTime(),
                record.getReleaseStatus().getCode(),
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

    private ReleaseRecordPageResponse toPageResponse(AppReleaseRecord record) {
        return new ReleaseRecordPageResponse(
                record.getId(),
                record.getAppId(),
                record.getAppName(),
                record.getPackageName(),
                record.getAppDescription(),
                record.getVersionId(),
                record.getVersionCode(),
                record.getStoreType().getCode(),
                record.getReleaseMode().getCode(),
                record.getReleaseStatus().getCode(),
                record.getReleaseTime(),
                record.getFinishTime(),
                record.getCreateUser(),
                record.getUpdateUser()
        );
    }

    private ReleaseTaskLogResponse toLogResponse(AppReleaseTaskLog log) {
        return new ReleaseTaskLogResponse(
                log.getId(),
                log.getReleaseRecordId(),
                log.getAction(),
                log.getStatusBefore(),
                log.getStatusAfter(),
                log.getMessage(),
                log.getPayload(),
                log.getCreateUser(),
                log.getUpdateUser(),
                log.getCreateTime()
        );
    }

    private AppReleaseRecord requireReleaseRecord(Long releaseId) {
        AppReleaseRecord record = releaseRecordRepository.selectById(releaseId);
        if (record == null) {
            throw new NotFoundException("Release record not found");
        }
        return record;
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

    private Long normalizeReleaseType(Long releaseType) {
        return releaseType == null ? 1L : releaseType;
    }

    private void validateReleaseRequest(ReleaseSubmitRequest request, Long releaseType) {
        if (releaseType != 1L && releaseType != 2L) {
            throw new IllegalArgumentException("releaseType must be 1 or 2");
        }
        if (releaseType != 2L) {
            return;
        }
        if (request.getGrayPercent() == null) {
            throw new IllegalArgumentException("grayPercent is required when releaseType=2");
        }
        if (request.getGrayPercent() < 1 || request.getGrayPercent() > 99) {
            throw new IllegalArgumentException("grayPercent must be between 1 and 99 when releaseType=2");
        }
        if (request.getGrayStartTime() == null || request.getGrayEndTime() == null) {
            throw new IllegalArgumentException("grayStartTime and grayEndTime are required when releaseType=2");
        }
        if (!request.getGrayStartTime().isBefore(request.getGrayEndTime())) {
            throw new IllegalArgumentException("grayStartTime must be before grayEndTime");
        }
    }
}
