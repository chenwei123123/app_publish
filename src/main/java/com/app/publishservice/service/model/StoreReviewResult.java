package com.app.publishservice.service.model;

import com.app.publishservice.domain.enums.ReleaseStatus;

public record StoreReviewResult(ReleaseStatus releaseStatus, String responseLog, String rejectReason) {
}

