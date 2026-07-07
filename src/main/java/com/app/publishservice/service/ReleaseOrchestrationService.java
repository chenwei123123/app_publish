package com.app.publishservice.service;

import com.app.publishservice.api.dto.ReleaseRecordResponse;
import com.app.publishservice.api.dto.ReleaseRecordPageResponse;
import com.app.publishservice.api.dto.ReleaseSubmitRequest;
import com.app.publishservice.api.dto.PageResponse;
import com.app.publishservice.common.exception.NotFoundException;
import com.app.publishservice.common.exception.SubmitPackageDownloadException;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.ReleaseMode;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.repository.AppReleaseRecordRepository;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Service
public class ReleaseOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrchestrationService.class);

    private final PackageVersionService packageVersionService;
    private final AppManagementService appManagementService;
    private final AppReleaseRecordRepository releaseRecordRepository;
    private final TokenService tokenService;
    private final StorePublisher storePublisher;
    private final Executor releaseSubmitExecutor;
    private final TransactionOperations transactionOperations;

    /**
     * 初始化ReleaseOrchestrationService。
     */
    public ReleaseOrchestrationService(
            PackageVersionService packageVersionService,
            AppManagementService appManagementService,
            AppReleaseRecordRepository releaseRecordRepository,
            TokenService tokenService,
            StorePublisher storePublisher,
            @Qualifier("releaseSubmitExecutor") Executor releaseSubmitExecutor,
            TransactionOperations transactionOperations
    ) {
        this.packageVersionService = packageVersionService;
        this.appManagementService = appManagementService;
        this.releaseRecordRepository = releaseRecordRepository;
        this.tokenService = tokenService;
        this.storePublisher = storePublisher;
        this.releaseSubmitExecutor = releaseSubmitExecutor;
        this.transactionOperations = transactionOperations;
    }

    /**
     * 提交相关数据。
     */
    public List<ReleaseRecordResponse> submit(ReleaseSubmitRequest request) {
        log.info("Start release submit, versionId={}, releaseMode={}, stores={}", request.getVersionId(), request.getReleaseMode(), request.getStoreTypes());
        SubmitPreparation preparation = transactionOperations.execute(status -> prepareSubmit(request));
        if (preparation == null) {
            throw new IllegalStateException("Release submit preparation returned no result");
        }
        if (preparation.failure() != null) {
            throw preparation.failure();
        }
        return submitToStores(preparation.version(), preparation.submitContexts());
    }

    /**
     * 处理poll 审核 Results相关逻辑。
     */
    @Transactional
    public void pollAuditResults() {
        List<AppReleaseRecord> records = releaseRecordRepository.selectReviewingReleaseRecords(ReleaseStatus.AUDITING.getCode());
        log.info("Start polling audit results, pendingCount={}", records.size());
        for (AppReleaseRecord record : records) {
            AppStoreConfig storeConfig = appManagementService.requireStoreConfig(record.getStoreType());
            StoreReviewResult reviewResult;
            try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record.getId())) {
                String token = tokenService.getValidToken(storeConfig);
                reviewResult = storePublisher.queryReview(storeConfig, record, token);
            }
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
            } else {
                releaseRecordRepository.updateById(record);
                log.debug("Review status unchanged, releaseId={}, storeType={}, status={}", record.getId(), record.getStoreType().getCode(), before.getCode());
            }
        }
    }

    /**
     * 处理prepare 提交相关逻辑。
     */
    private SubmitPreparation prepareSubmit(ReleaseSubmitRequest request) {
        AppVersion version = packageVersionService.requireVersion(request.getVersionId());
        ReleaseMode releaseMode = ReleaseMode.fromCode(request.getReleaseMode());
        Long releaseType = normalizeReleaseType(request.getReleaseType());
        validateReleaseRequest(request, releaseType);

        List<StoreConfigContext> storeConfigs = new ArrayList<>();
        for (String storeTypeCode : request.getStoreTypes()) {
            StoreType storeType = StoreType.fromCode(storeTypeCode);
            AppStoreConfig storeConfig = appManagementService.requireStoreConfig(storeType);
            if (storeConfig.getApiStatus() != 1) {
                throw new IllegalArgumentException("Store config is disabled: " + storeTypeCode);
            }
            storeConfigs.add(new StoreConfigContext(storeType, storeConfig));
        }

        List<StoreSubmitContext> submitContexts = new ArrayList<>();
        for (StoreConfigContext storeConfigContext : storeConfigs) {
            AppReleaseRecord record = createReleaseRecord(
                    version,
                    storeConfigContext.storeType(),
                    releaseMode,
                    releaseType,
                    request,
                    ReleaseStatus.DOWNLOADING
            );
            releaseRecordRepository.insert(record);
            log.info("Release record created, releaseId={}, appId={}, versionId={}, storeType={}", record.getId(), record.getAppId(), record.getVersionId(), storeConfigContext.storeType().getCode());
            submitContexts.add(new StoreSubmitContext(storeConfigContext.storeType(), storeConfigContext.storeConfig(), record));
        }

        try {
            version = packageVersionService.prepareVersionForSubmit(version);
            for (StoreSubmitContext submitContext : submitContexts) {
                AppReleaseRecord record = submitContext.record();
                record.setReleaseStatus(ReleaseStatus.DOWNLOAD_SUCCESS);
                releaseRecordRepository.updateById(record);
            }
            return SubmitPreparation.success(version, submitContexts);
        } catch (SubmitPackageDownloadException ex) {
            handleSubmitPackageFailure(submitContexts, ex);
            return SubmitPreparation.failure(ex);
        }
    }

    /**
     * 提交商店。
     */
    private List<ReleaseRecordResponse> submitToStores(AppVersion version, List<StoreSubmitContext> submitContexts) {
        List<CompletableFuture<ReleaseRecordResponse>> futures = new ArrayList<>(submitContexts.size());
        for (StoreSubmitContext submitContext : submitContexts) {
            futures.add(scheduleStoreSubmit(version, submitContext));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * 处理schedule 商店提交相关逻辑。
     */
    private CompletableFuture<ReleaseRecordResponse> scheduleStoreSubmit(AppVersion version, StoreSubmitContext submitContext) {
        try {
            return CompletableFuture.supplyAsync(() -> submitToStore(version, submitContext), releaseSubmitExecutor)
                    .exceptionally(ex -> handleUnexpectedSubmitFailure(submitContext, unwrapCompletionException(ex)));
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(handleUnexpectedSubmitFailure(submitContext, ex));
        }
    }

    /**
     * 提交商店。
     */
    private ReleaseRecordResponse submitToStore(AppVersion version, StoreSubmitContext submitContext) {
        StoreType storeType = submitContext.storeType();
        AppStoreConfig storeConfig = submitContext.storeConfig();
        AppReleaseRecord record = submitContext.record();
        if (record.getReleaseStatus() != ReleaseStatus.API_PENDING) {
            record.setReleaseStatus(ReleaseStatus.API_PENDING);
            releaseRecordRepository.updateById(record);
        }

        try {
            try (StoreRequestLogContextHolder.Scope ignored = StoreRequestLogContextHolder.open(record.getId())) {
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
            }
        } catch (Exception ex) {
            return handleUnexpectedSubmitFailure(submitContext, ex);
        }
        return toResponse(record);
    }

    /**
     * 处理Unexpected 提交 Failure。
     */
    private ReleaseRecordResponse handleUnexpectedSubmitFailure(StoreSubmitContext submitContext, Throwable ex) {
        AppReleaseRecord record = submitContext.record();
        record.setReleaseStatus(ReleaseStatus.REJECT);
        record.setRejectReason(ex.getMessage());
        record.setApiResponseLog(ex.getMessage());
        record.setFinishTime(LocalDateTime.now());
        releaseRecordRepository.updateById(record);
        log.error("Submit review failed, releaseId={}, storeType={}", record.getId(), submitContext.storeType().getCode(), ex);
        return toResponse(record);
    }

    /**
     * 处理unwrap Completion 异常相关逻辑。
     */
    private Throwable unwrapCompletionException(Throwable ex) {
        if (ex instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return ex;
    }

    /**
     * 获取发布记录。
     */
    @Transactional(readOnly = true)
    public ReleaseRecordResponse getReleaseRecord(Long releaseId) {
        AppReleaseRecord record = releaseRecordRepository.selectReleaseRecordDetail(releaseId);
        if (record == null) {
            throw new NotFoundException("Release record not found");
        }
        attachAppInfo(record);
        return toResponse(record);
    }

    /**
     * 处理分页发布记录相关逻辑。
     */
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
                page.getRecords().stream()
                        .peek(this::attachAppInfo)
                        .map(this::toPageResponse)
                        .toList()
        );
    }

    /**
     * 查询发布应用 Id。
     */
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
                page.getRecords().stream()
                        .peek(this::attachAppInfo)
                        .map(this::toPageResponse)
                        .toList()
        );
    }

    /**
     * 处理响应相关逻辑。
     */
    private ReleaseRecordResponse toResponse(AppReleaseRecord record) {
        return new ReleaseRecordResponse(
                record.getId(),
                record.getAppId(),
                record.getAppName(),
                record.getPackageName(),
                appTypeCode(record),
                record.getAppDescription(),
                record.getAppInfo() == null ? null : record.getAppInfo().getCopyrightNo(),
                record.getAppInfo() == null ? null : record.getAppInfo().getIcpNo(),
                record.getAppInfo() == null ? null : record.getAppInfo().getAppRecordNo(),
                record.getAppInfo() == null ? null : record.getAppInfo().getPrivacyUrl(),
                record.getAppInfo() == null ? null : record.getAppInfo().getUserAgreementUrl(),
                record.getAppInfo() == null ? null : record.getAppInfo().getStatus(),
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

    /**
     * 处理分页响应相关逻辑。
     */
    private ReleaseRecordPageResponse toPageResponse(AppReleaseRecord record) {
        return new ReleaseRecordPageResponse(
                record.getId(),
                record.getAppId(),
                record.getAppName(),
                record.getPackageName(),
                appTypeCode(record),
                record.getAppDescription(),
                record.getAppInfo() == null ? null : record.getAppInfo().getCopyrightNo(),
                record.getAppInfo() == null ? null : record.getAppInfo().getIcpNo(),
                record.getAppInfo() == null ? null : record.getAppInfo().getAppRecordNo(),
                record.getAppInfo() == null ? null : record.getAppInfo().getPrivacyUrl(),
                record.getAppInfo() == null ? null : record.getAppInfo().getUserAgreementUrl(),
                record.getAppInfo() == null ? null : record.getAppInfo().getStatus(),
                record.getVersionId(),
                record.getVersionCode(),
                record.getStoreType().getCode(),
                record.getReleaseMode().getCode(),
                record.getReleaseType(),
                record.getReleaseStatus().getCode(),
                record.getReleaseTime(),
                record.getFinishTime(),
                record.getApiResponseLog(),
                record.getCreateUser(),
                record.getUpdateUser()
        );
    }

    private void attachAppInfo(AppReleaseRecord record) {
        if (record == null || record.getAppId() == null) {
            return;
        }
        record.setAppInfo(appManagementService.requireApp(record.getAppId()));
    }

    private Integer appTypeCode(AppReleaseRecord record) {
        return record == null || record.getAppInfo() == null || record.getAppInfo().getAppType() == null
                ? null
                : record.getAppInfo().getAppType().getCode();
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

    /**
     * 规范化发布类型。
     */
    private Long normalizeReleaseType(Long releaseType) {
        return releaseType == null ? 1L : releaseType;
    }

    /**
     * 创建发布记录。
     */
    private AppReleaseRecord createReleaseRecord(
            AppVersion version,
            StoreType storeType,
            ReleaseMode releaseMode,
            Long releaseType,
            ReleaseSubmitRequest request,
            ReleaseStatus initialStatus
    ) {
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
        record.setReleaseStatus(initialStatus);
        return record;
    }

    /**
     * 处理提交包 Failure。
     */
    private void handleSubmitPackageFailure(List<StoreSubmitContext> submitContexts, RuntimeException ex) {
        for (StoreSubmitContext submitContext : submitContexts) {
            AppReleaseRecord record = submitContext.record();
            record.setReleaseStatus(ReleaseStatus.DOWNLOAD_FAIL);
            record.setRejectReason(ex.getMessage());
            record.setApiResponseLog(ex.getMessage());
            record.setFinishTime(LocalDateTime.now());
            releaseRecordRepository.updateById(record);
            log.error(
                    "Submit package preparation failed, releaseId={}, storeType={}",
                    record.getId(),
                    submitContext.storeType().getCode(),
                    ex
            );
        }
    }

    /**
     * 校验发布请求。
     */
    private void validateReleaseRequest(ReleaseSubmitRequest request, Long releaseType) {
        if (releaseType != 1L && releaseType != 2L) {
            throw new IllegalArgumentException("releaseType must be 1 or 2");
        }
        if (releaseType != 2L) {
            return;
        }
        for (String storeTypeCode : request.getStoreTypes()) {
            StoreType storeType = StoreType.fromCode(storeTypeCode);
            if ("oppo".equalsIgnoreCase(storeType.getCode())
                    || "xiaomi".equalsIgnoreCase(storeType.getCode())
                    || "yingyongbao".equalsIgnoreCase(storeType.getCode())
            ) {
                throw new IllegalArgumentException(storeType.getCode() + " does not support staged release submit via API");
            }
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

    private record SubmitPreparation(
            AppVersion version,
            List<StoreSubmitContext> submitContexts,
            SubmitPackageDownloadException failure
    ) {
        /**
         * 处理success相关逻辑。
         */
        private static SubmitPreparation success(AppVersion version, List<StoreSubmitContext> submitContexts) {
            return new SubmitPreparation(version, submitContexts, null);
        }

        /**
         * 处理failure相关逻辑。
         */
        private static SubmitPreparation failure(SubmitPackageDownloadException failure) {
            return new SubmitPreparation(null, List.of(), failure);
        }
    }

    private record StoreConfigContext(StoreType storeType, AppStoreConfig storeConfig) {
    }

    private record StoreSubmitContext(StoreType storeType, AppStoreConfig storeConfig, AppReleaseRecord record) {
    }
}
