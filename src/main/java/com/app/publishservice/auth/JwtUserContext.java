package com.app.publishservice.auth;

import java.time.OffsetDateTime;
import java.util.Map;

public record JwtUserContext(
        String userId,
        String username,
        OffsetDateTime expiresAt,
        Map<String, Object> claims
) {
}
