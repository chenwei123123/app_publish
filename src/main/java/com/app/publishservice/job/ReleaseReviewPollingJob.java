package com.app.publishservice.job;

import com.app.publishservice.service.ReleaseOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReleaseReviewPollingJob {

    private static final Logger log = LoggerFactory.getLogger(ReleaseReviewPollingJob.class);

    private final ReleaseOrchestrationService releaseOrchestrationService;

    public ReleaseReviewPollingJob(ReleaseOrchestrationService releaseOrchestrationService) {
        this.releaseOrchestrationService = releaseOrchestrationService;
    }

    @Scheduled(fixedDelayString = "${app.review-poll-delay-ms:600000000}")
    public void poll() {
        log.debug("Trigger release review polling job");
        releaseOrchestrationService.pollAuditResults();
    }
}
