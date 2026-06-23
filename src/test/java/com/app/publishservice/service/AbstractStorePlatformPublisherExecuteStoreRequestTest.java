package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import com.app.publishservice.config.StoreApiProperties;
import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppStoreConfig;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.domain.enums.StoreType;
import com.app.publishservice.service.model.StoreReviewResult;
import com.app.publishservice.service.model.StoreSubmitResult;
import com.app.publishservice.service.model.TokenPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractStorePlatformPublisherExecuteStoreRequestTest {

    @Test
    void shouldReturnMockResponseWhenStoreMockEnabled() {
        TestPublisher publisher = new TestPublisher(appProperties(true));
        AtomicInteger requestCount = new AtomicInteger();
        AtomicInteger mockCount = new AtomicInteger();

        String response = publisher.execute(
                storeConfig(),
                () -> {
                    requestCount.incrementAndGet();
                    return "real-response";
                },
                () -> {
                    mockCount.incrementAndGet();
                    return "mock-response";
                }
        );

        assertEquals("mock-response", response);
        assertEquals(0, requestCount.get());
        assertEquals(1, mockCount.get());
    }

    @Test
    void shouldExecuteRealRequestWhenStoreMockDisabled() {
        TestPublisher publisher = new TestPublisher(appProperties(false));
        AtomicInteger requestCount = new AtomicInteger();
        AtomicInteger mockCount = new AtomicInteger();

        String response = publisher.execute(
                storeConfig(),
                () -> {
                    requestCount.incrementAndGet();
                    return "real-response";
                },
                () -> {
                    mockCount.incrementAndGet();
                    return "mock-response";
                }
        );

        assertEquals("real-response", response);
        assertEquals(1, requestCount.get());
        assertEquals(0, mockCount.get());
    }

    private AppProperties appProperties(boolean mockEnabled) {
        AppProperties appProperties = new AppProperties();
        StoreApiProperties.StoreEndpointProperties endpoint = new StoreApiProperties.StoreEndpointProperties();
        endpoint.setMockEnabled(mockEnabled);
        appProperties.getStoreApi().getStores().put("xiaomi", endpoint);
        return appProperties;
    }

    private AppStoreConfig storeConfig() {
        AppStoreConfig storeConfig = new AppStoreConfig();
        storeConfig.setStoreType(StoreType.fromCode("xiaomi"));
        return storeConfig;
    }

    private static final class TestPublisher extends AbstractStorePlatformPublisher {

        private TestPublisher(AppProperties appProperties) {
            super(RestClient.create(), new ObjectMapper(), appProperties);
        }

        private String execute(AppStoreConfig storeConfig, Supplier<String> request, Supplier<String> mockResponse) {
            return executeStoreRequest(
                    trace(storeConfig, "test action", "GET", "https://mock.local/test", null, null),
                    request,
                    mockResponse
            );
        }

        @Override
        public TokenPayload refreshToken(AppStoreConfig storeConfig) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoreSubmitResult submitRelease(AppStoreConfig storeConfig, AppVersion version, AppReleaseRecord record, String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoreReviewResult queryReview(AppStoreConfig storeConfig, AppReleaseRecord record, String token) {
            throw new UnsupportedOperationException();
        }
    }
}
