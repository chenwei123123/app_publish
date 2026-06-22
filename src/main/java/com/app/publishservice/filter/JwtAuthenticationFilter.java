package com.app.publishservice.filter;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.auth.JwtAuthenticationService;
import com.app.publishservice.auth.JwtUserContext;
import com.app.publishservice.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE_JWT_USER = "jwtUser";
    public static final String REQUEST_ATTRIBUTE_JWT_CLAIMS = "jwtClaims";
    public static final String REQUEST_ATTRIBUTE_JWT_TOKEN = "jwtToken";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final AppProperties appProperties;
    private final JwtAuthenticationService jwtAuthenticationService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            AppProperties appProperties,
            JwtAuthenticationService jwtAuthenticationService,
            ObjectMapper objectMapper
    ) {
        this.appProperties = appProperties;
        this.jwtAuthenticationService = jwtAuthenticationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!appProperties.getJwtAuth().isEnabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs")
                || "/openapi.yaml".equals(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            writeUnauthorizedResponse(response, "Authentication header or cookie is required");
            return;
        }
        try {
            JwtUserContext userContext = jwtAuthenticationService.parseAndValidate(token);
            request.setAttribute(REQUEST_ATTRIBUTE_JWT_USER, userContext);
            request.setAttribute(REQUEST_ATTRIBUTE_JWT_CLAIMS, userContext.claims());
            request.setAttribute(REQUEST_ATTRIBUTE_JWT_TOKEN, token);
            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(token, userContext).toString());
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException ex) {
            log.warn("JWT authentication failed, method={}, uri={}, message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            response.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
            writeUnauthorizedResponse(response, ex.getMessage());
        }
    }

    private String normalizeToken(String rawHeader) {
        String token = rawHeader.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        return token;
    }

    private String resolveToken(HttpServletRequest request) {
        String rawHeader = request.getHeader(appProperties.getJwtAuth().getHeaderName());
        if (StringUtils.hasText(rawHeader)) {
            return normalizeToken(rawHeader);
        }
        return resolveTokenFromCookie(request);
    }

    private String resolveTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        String cookieName = appProperties.getJwtAuth().getCookieName();
        for (Cookie cookie : cookies) {
            if (cookie != null && cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private ResponseCookie buildCookie(String token, JwtUserContext userContext) {
        long maxAgeSeconds = Math.max(Duration.between(java.time.Instant.now(), userContext.expiresAt().toInstant()).getSeconds(), 0L);
        return ResponseCookie.from(appProperties.getJwtAuth().getCookieName(), URLEncoder.encode(token, StandardCharsets.UTF_8))
                .httpOnly(appProperties.getJwtAuth().isCookieHttpOnly())
                .secure(appProperties.getJwtAuth().isCookieSecure())
                .sameSite(appProperties.getJwtAuth().getCookieSameSite())
                .path(appProperties.getJwtAuth().getCookiePath())
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(appProperties.getJwtAuth().getCookieName(), "")
                .httpOnly(appProperties.getJwtAuth().isCookieHttpOnly())
                .secure(appProperties.getJwtAuth().isCookieSecure())
                .sameSite(appProperties.getJwtAuth().getCookieSameSite())
                .path(appProperties.getJwtAuth().getCookiePath())
                .maxAge(Duration.ZERO)
                .build();
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.failure(message)));
    }
}
