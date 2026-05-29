package com.app.publishservice.service.model;

import java.time.LocalDateTime;

public record TokenPayload(String tokenType, String tokenValue, LocalDateTime expireTime) {
}

