package com.app.publishservice.filter;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.auth.CurrentUserContextHolder;
import com.app.publishservice.auth.JwtAuthenticationService;
import com.app.publishservice.auth.JwtUserContext;
import com.app.publishservice.config.AppProperties;
import com.app.publishservice.handler.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.Cipher;
import jakarta.servlet.http.Cookie;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtAuthenticationFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSetCookieAndRequestAttributesWhenJwtIsValid() throws Exception {
        MockMvc mockMvc = mockMvc(jwtFilter(appProperties("test-secret")));
        String token = createJwt(
                "test-secret",
                Map.of("alg", "HS256", "typ", "JWT"),
                claims("1001", "demo-user", Instant.now().plusSeconds(1800).getEpochSecond())
        );

        mockMvc.perform(get("/test/auth-user")
                        .header("Authentication", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("1001"))
                .andExpect(jsonPath("$.data.username").value("demo-user"))
                .andExpect(cookie().exists("Authentication"));
    }

    @Test
    void shouldRejectExpiredJwt() throws Exception {
        MockMvc mockMvc = mockMvc(jwtFilter(appProperties("test-secret")));
        String token = createJwt(
                "test-secret",
                Map.of("alg", "HS256", "typ", "JWT"),
                claims("1001", "demo-user", Instant.now().minusSeconds(60).getEpochSecond())
        );

        mockMvc.perform(get("/test/auth-user")
                        .header("Authentication", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authentication token has expired"))
                .andExpect(cookie().maxAge("Authentication", 0));
    }

    @Test
    void shouldPassThroughWhenAuthenticationHeaderIsMissing() throws Exception {
        MockMvc mockMvc = mockMvc(jwtFilter(appProperties("test-secret")));

        mockMvc.perform(get("/test/no-auth")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authentication header or cookie is required"));
    }

    @Test
    void shouldAuthenticateByCookieWhenHeaderIsMissing() throws Exception {
        MockMvc mockMvc = mockMvc(jwtFilter(appProperties("test-secret")));
        String token = createJwt(
                "test-secret",
                Map.of("alg", "HS256", "typ", "JWT"),
                claims("1002", "cookie-user", Instant.now().plusSeconds(1800).getEpochSecond())
        );

        mockMvc.perform(get("/test/auth-user")
                        .cookie(new Cookie("Authentication", token))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("1002"))
                .andExpect(jsonPath("$.data.username").value("cookie-user"))
                .andExpect(cookie().exists("Authentication"));
    }

    @Test
    void shouldReadUsernameFromAudienceClaim() throws Exception {
        MockMvc mockMvc = mockMvc(jwtFilter(appProperties("test-secret")));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", "1004");
        claims.put("audience", encryptAudience("audience-user"));
        claims.put("exp", Instant.now().plusSeconds(1800).getEpochSecond());
        String token = createJwt(
                "test-secret",
                Map.of("alg", "HS256", "typ", "JWT"),
                claims
        );

        mockMvc.perform(get("/test/auth-user")
                        .header("Authentication", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("1004"))
                .andExpect(jsonPath("$.data.username").value("audience-user"));
    }

    @Test
    void shouldReadProvidedAudienceSampleByConfiguredIv() throws Exception {
        MockMvc mockMvc = mockMvc(jwtFilter(appProperties("test-secret")));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", "1005");
        claims.put("audience", "FdhyDH7VVT3Sig/eJB7tkA==");
        claims.put("exp", Instant.now().plusSeconds(1800).getEpochSecond());
        String token = createJwt(
                "test-secret",
                Map.of("alg", "HS256", "typ", "JWT"),
                claims
        );

        mockMvc.perform(get("/test/auth-user")
                        .header("Authentication", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("1005"))
                .andExpect(jsonPath("$.data.username").value("baizn"));
    }

    @Test
    void shouldReadCurrentUserFromContextHolder() {
        CurrentUserContextHolder currentUserContextHolder = new CurrentUserContextHolder();
        MockHttpServletRequest request = new MockHttpServletRequest();
        JwtUserContext userContext = new JwtUserContext(
                "1003",
                "holder-user",
                Instant.now().plusSeconds(60),
                Map.of("userId", "1003", "username", "holder-user")
        );
        request.setAttribute(JwtAuthenticationFilter.REQUEST_ATTRIBUTE_JWT_USER, userContext);
        ServletRequestAttributes requestAttributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(requestAttributes);
        try {
            assertTrue(currentUserContextHolder.getCurrentUser().isPresent());
            assertEquals("1003", currentUserContextHolder.requireCurrentUser().userId());
            assertEquals("1003", currentUserContextHolder.getCurrentUserId().orElseThrow());
            assertEquals("holder-user", currentUserContextHolder.getCurrentUsername().orElseThrow());
        } finally {
            RequestContextHolder.resetRequestAttributes();
            requestAttributes.requestCompleted();
        }
    }

    private JwtAuthenticationFilter jwtFilter(AppProperties appProperties) {
        return new JwtAuthenticationFilter(
                appProperties,
                new JwtAuthenticationService(appProperties, objectMapper),
                objectMapper
        );
    }

    private MockMvc mockMvc(JwtAuthenticationFilter filter) {
        return MockMvcBuilders.standaloneSetup(new JwtFilterTestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
    }

    private AppProperties appProperties(String secret) {
        AppProperties appProperties = new AppProperties();
        appProperties.getJwtAuth().setEnabled(true);
        appProperties.getJwtAuth().setSecret(secret);
        appProperties.getJwtAuth().setAudienceAesIv("9UG5hcXgbA9N5jgyNwFSAA==");
        return appProperties;
    }

    private String encryptAudience(String plainText) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec("K6MIDdFi1NGk685H".getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv)
        );
        byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private Map<String, Object> claims(String userId, String username, long exp) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("exp", exp);
        return claims;
    }

    private String createJwt(String secret, Map<String, Object> header, Map<String, Object> claims) throws Exception {
        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(header));
        String encodedClaims = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(claims));
        String content = encodedHeader + "." + encodedClaims;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        return content + "." + signature;
    }

    @RestController
    static class JwtFilterTestController {

        @GetMapping("/test/auth-user")
        ApiResponse<Map<String, Object>> authUser(
                @RequestAttribute(JwtAuthenticationFilter.REQUEST_ATTRIBUTE_JWT_USER) JwtUserContext jwtUserContext
        ) {
            return ApiResponse.success(Map.of(
                    "userId", jwtUserContext.userId(),
                    "username", jwtUserContext.username()
            ));
        }

        @GetMapping("/test/no-auth")
        ApiResponse<String> noAuth() {
            return ApiResponse.success("anonymous");
        }
    }
}
