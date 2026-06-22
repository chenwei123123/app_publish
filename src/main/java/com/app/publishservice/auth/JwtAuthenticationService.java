package com.app.publishservice.auth;

import com.app.publishservice.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtAuthenticationService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {
    };
    private static final ZoneOffset EAST_EIGHT_ZONE_OFFSET = ZoneOffset.ofHours(8);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public JwtUserContext parseAndValidate(String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("Authentication token is blank");
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Authentication token format is invalid");
        }

        Map<String, Object> header = readJwtPart(parts[0], "header");
        Map<String, Object> claims = readJwtPart(parts[1], "payload");
        validateSignature(header, parts[0], parts[1], parts[2]);

        OffsetDateTime expiresAt = resolveExpiration(claims);
        if (!expiresAt.toInstant().isAfter(Instant.now())) {
            throw new IllegalArgumentException("Authentication token has expired");
        }

        OffsetDateTime notBefore = resolveInstant(claims.get("nbf"));
        if (notBefore != null && Instant.now().isBefore(notBefore.toInstant())) {
            throw new IllegalArgumentException("Authentication token is not active yet");
        }

        String userId = firstText(claims, "userId", "user_id", "uid", "id");
        String username = firstNonBlank(
                resolveAudienceUsername(claims),
                firstText(claims, "username", "userName", "account", "accountName", "sub", "name")
        );
        if (!StringUtils.hasText(userId) && !StringUtils.hasText(username)) {
            throw new IllegalArgumentException("Authentication token does not contain user identity");
        }

        return new JwtUserContext(userId, username, expiresAt, Map.copyOf(new LinkedHashMap<>(claims)));
    }

    private Map<String, Object> readJwtPart(String part, String label) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(part);
            return objectMapper.readValue(decoded, MAP_TYPE_REFERENCE);
        } catch (IllegalArgumentException | IOException ex) {
            throw new IllegalArgumentException("Authentication token " + label + " is invalid", ex);
        }
    }

    private void validateSignature(Map<String, Object> header, String encodedHeader, String encodedPayload, String encodedSignature) {
        String algorithm = String.valueOf(header.getOrDefault("alg", ""));
        if (!StringUtils.hasText(algorithm)) {
            throw new IllegalArgumentException("Authentication token algorithm is missing");
        }
        String secret = appProperties.getJwtAuth().getSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalArgumentException("JWT auth secret is not configured");
        }

        String macAlgorithm = switch (algorithm.toUpperCase()) {
            case "HS256" -> "HmacSHA256";
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> throw new IllegalArgumentException("Unsupported JWT algorithm: " + algorithm);
        };
        String content = encodedHeader + "." + encodedPayload;
        String expectedSignature = sign(content, secret.trim(), macAlgorithm);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                encodedSignature.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new IllegalArgumentException("Authentication token signature is invalid");
        }
    }

    private String sign(String content, String secret, String macAlgorithm) {
        try {
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlgorithm));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to validate JWT signature", ex);
        }
    }

    private OffsetDateTime resolveExpiration(Map<String, Object> claims) {
        OffsetDateTime expiresAt = resolveInstant(claims.get("exp"));
        if (expiresAt == null) {
            throw new IllegalArgumentException("Authentication token expiration is missing");
        }
        return expiresAt;
    }

    private OffsetDateTime resolveInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            long raw = number.longValue();
            return toEastEightOffsetDateTime(raw);
        }
        try {
            String text = String.valueOf(value).trim();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            long raw = Long.parseLong(text);
            return toEastEightOffsetDateTime(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Authentication token time claim is invalid", ex);
        }
    }

    private OffsetDateTime toEastEightOffsetDateTime(long raw) {
        Instant instant = raw > 9999999999L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        return instant.atOffset(EAST_EIGHT_ZONE_OFFSET);
    }

    private String firstText(Map<String, Object> claims, String... keys) {
        for (String key : keys) {
            String text = claimText(claims.get(key));
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String resolveAudienceUsername(Map<String, Object> claims) {
        String audience = firstNonBlank(claimText(claims.get("audience")), claimText(claims.get("aud")));
        if (!StringUtils.hasText(audience)) {
            return "";
        }
        return decryptAudience(audience);
    }

    private String decryptAudience(String encryptedAudience) {
        String key = appProperties.getJwtAuth().getAudienceAesKey();
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("JWT audience AES key is not configured");
        }
        try {
            AesCbcPayload payload = resolveAesCbcPayload(encryptedAudience.trim());
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key.trim().getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(payload.iv())
            );
            String decrypted = new String(cipher.doFinal(payload.cipherBytes()), StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(decrypted)) {
                throw new IllegalArgumentException("Authentication token audience is empty after decryption");
            }
            return decrypted;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Authentication token audience is invalid", ex);
        }
    }

    private AesCbcPayload resolveAesCbcPayload(String value) {
        int separatorIndex = value.indexOf(':');
        if (separatorIndex > 0 && separatorIndex < value.length() - 1) {
            byte[] iv = decodeBase64(value.substring(0, separatorIndex));
            byte[] cipherBytes = decodeBase64(value.substring(separatorIndex + 1));
            validateIv(iv);
            return new AesCbcPayload(iv, cipherBytes);
        }

        byte[] combined = decodeBase64(value);
        if (combined.length > 16) {
            byte[] iv = new byte[16];
            byte[] cipherBytes = new byte[combined.length - 16];
            System.arraycopy(combined, 0, iv, 0, 16);
            System.arraycopy(combined, 16, cipherBytes, 0, cipherBytes.length);
            return new AesCbcPayload(iv, cipherBytes);
        }

        byte[] configuredIv = resolveConfiguredAudienceIv();
        validateIv(configuredIv);
        return new AesCbcPayload(configuredIv, combined);
    }

    private void validateIv(byte[] iv) {
        if (iv == null || iv.length != 16) {
            throw new IllegalArgumentException("Authentication token audience IV is invalid");
        }
    }

    private byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            return Base64.getUrlDecoder().decode(value);
        }
    }

    private byte[] resolveConfiguredAudienceIv() {
        String configuredIv = appProperties.getJwtAuth().getAudienceAesIv();
        if (!StringUtils.hasText(configuredIv)) {
            throw new IllegalArgumentException("Authentication token audience IV is invalid");
        }
        String normalized = configuredIv.trim();
        if (normalized.length() == 32 && normalized.matches("[0-9A-Fa-f]+")) {
            byte[] result = new byte[16];
            for (int index = 0; index < 16; index++) {
                result[index] = (byte) Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16);
            }
            return result;
        }
        if (normalized.length() == 16) {
            return normalized.getBytes(StandardCharsets.UTF_8);
        }
        return decodeBase64(normalized);
    }

    private String claimText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String text = claimText(item);
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
            return "";
        }
        if (value.getClass().isArray()) {
            Object[] values = (Object[]) value;
            for (Object item : values) {
                String text = claimText(item);
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
            return "";
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private record AesCbcPayload(byte[] iv, byte[] cipherBytes) {
    }
}
