package com.app.publishservice.service;

import com.app.publishservice.common.exception.StoreApiException;
import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class StoreRequestSupport {

    private static final Logger log = LoggerFactory.getLogger(StoreRequestSupport.class);

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final StoreRequestLogService storeRequestLogService;

    StoreRequestSupport(
            ObjectMapper objectMapper,
            AppProperties appProperties,
            StoreRequestLogService storeRequestLogService
    ) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.storeRequestLogService = storeRequestLogService;
    }

    <T> T executeStoreRequest(String action, Supplier<T> request) {
        return executeStoreRequest(null, action, request);
    }

    <T> T executeStoreRequest(
            AbstractStorePlatformPublisher.StoreRequestTrace trace,
            String action,
            Supplier<T> request
    ) {
        long startTime = System.currentTimeMillis();
        try {
            T response = request.get();
            logStoreRequestSuccess(trace, response, System.currentTimeMillis() - startTime);
            return response;
        } catch (StoreApiException ex) {
            logStoreRequestFailure(trace, ex.getStatus().value(), ex.getMessage(), null, System.currentTimeMillis() - startTime);
            throw ex;
        } catch (ResourceAccessException ex) {
            StoreApiException translated = translateResourceAccessException(action, ex);
            logStoreRequestFailure(trace, translated.getStatus().value(), translated.getMessage(), null, System.currentTimeMillis() - startTime);
            throw translated;
        } catch (RestClientResponseException ex) {
            String responseBody = decodeStoreResponseBody(ex.getResponseBodyAsByteArray());
            String message = "Store API request failed during " + action + ": status=" + ex.getStatusCode()
                    + buildResponseBodySuffix(responseBody);
            log.warn("Store API returned HTTP error during {}, status={}", action, ex.getStatusCode(), ex);
            logStoreRequestFailure(trace, ex.getStatusCode().value(), message, responseBody, System.currentTimeMillis() - startTime);
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, message, ex);
        } catch (RestClientException ex) {
            String message = "Store API request failed during " + action + ": " + ex.getMessage();
            log.warn("Store API call failed during {}", action, ex);
            logStoreRequestFailure(trace, HttpStatus.BAD_GATEWAY.value(), message, null, System.currentTimeMillis() - startTime);
            throw new StoreApiException(HttpStatus.BAD_GATEWAY, message, ex);
        }
    }

    <T> T executeStoreRequest(
            AbstractStorePlatformPublisher.StoreRequestTrace trace,
            String action,
            Supplier<T> request,
            Supplier<T> mockResponse
    ) {
        if (shouldMockStoreRequest(trace, mockResponse)) {
            long startTime = System.currentTimeMillis();
            try {
                T response = mockResponse.get();
                log.info("Use mock store request, storeType={}, action={}", storeTypeCode(trace), action);
                logStoreRequestSuccess(trace, response, System.currentTimeMillis() - startTime);
                return response;
            } catch (RuntimeException ex) {
                logStoreRequestFailure(trace, HttpStatus.BAD_GATEWAY.value(), ex.getMessage(), null, System.currentTimeMillis() - startTime);
                throw ex;
            }
        }
        return executeStoreRequest(trace, action, request);
    }

    AbstractStorePlatformPublisher.StoreRequestTrace trace(
            AppStoreConfig storeConfig,
            String action,
            String requestMethod,
            String requestUrl,
            Object requestParams,
            Object requestBody
    ) {
        return new AbstractStorePlatformPublisher.StoreRequestTrace(
                storeConfig,
                action,
                requestMethod,
                requestUrl,
                requestParams,
                requestBody
        );
    }

    Map<String, Object> requestPayload(Object body, Map<String, Object> extras) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> payload.put(String.valueOf(key), value));
        } else if (body != null) {
            payload.put("body", body);
        }
        if (extras != null && !extras.isEmpty()) {
            payload.putAll(extras);
        }
        return payload;
    }

    private void logStoreRequestSuccess(
            AbstractStorePlatformPublisher.StoreRequestTrace trace,
            Object response,
            long durationMs
    ) {
        if (trace == null || storeRequestLogService == null) {
            return;
        }
        storeRequestLogService.logSuccess(
                trace.storeConfig(),
                trace.action(),
                trace.requestMethod(),
                trace.requestUrl(),
                trace.requestParams(),
                trace.requestBody(),
                HttpStatus.OK.value(),
                stringifyResponse(response),
                durationMs
        );
    }

    private void logStoreRequestFailure(
            AbstractStorePlatformPublisher.StoreRequestTrace trace,
            Integer responseStatusCode,
            String errorMessage,
            Object responseBody,
            long durationMs
    ) {
        if (trace == null || storeRequestLogService == null) {
            return;
        }
        storeRequestLogService.logFailure(
                trace.storeConfig(),
                trace.action(),
                trace.requestMethod(),
                trace.requestUrl(),
                trace.requestParams(),
                trace.requestBody(),
                responseStatusCode,
                responseBody,
                errorMessage,
                durationMs
        );
    }

    private boolean shouldMockStoreRequest(
            AbstractStorePlatformPublisher.StoreRequestTrace trace,
            Supplier<?> mockResponse
    ) {
        return trace != null
                && trace.storeConfig() != null
                && mockResponse != null
                && endpoint(trace.storeConfig()).isMockEnabled();
    }

    private String storeTypeCode(AbstractStorePlatformPublisher.StoreRequestTrace trace) {
        if (trace == null || trace.storeConfig() == null || trace.storeConfig().getStoreType() == null) {
            return "unknown";
        }
        return trace.storeConfig().getStoreType().getCode();
    }

    private String stringifyResponse(Object response) {
        if (response == null) {
            return null;
        }
        if (response instanceof byte[] bytes) {
            return decodeStoreResponseBody(bytes);
        }
        if (response instanceof Map<?, ?> map) {
            return writeJson(map);
        }
        if (response instanceof Path path) {
            return path.toString();
        }
        return String.valueOf(response);
    }

    private String writeJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            return String.valueOf(object);
        }
    }

    private StoreApiException translateResourceAccessException(String action, ResourceAccessException ex) {
        boolean timeout = isTimeoutException(ex);
        HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        String message = timeout
                ? "Store API request timed out during " + action
                : "Store API is unavailable during " + action + ": " + ex.getMessage();
        log.warn("Store API resource access failed during {}, timeout={}", action, timeout, ex);
        return new StoreApiException(status, message, ex);
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("timed out") || normalized.contains("interrupted")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildResponseBodySuffix(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 300) {
            normalized = normalized.substring(0, 300) + "...";
        }
        return ", body=" + normalized;
    }

    private String decodeStoreResponseBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private StoreApiProperties.StoreEndpointProperties endpoint(AppStoreConfig storeConfig) {
        return appProperties.getStoreApi().getStore(storeConfig.getStoreType().getCode());
    }
}
