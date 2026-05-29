package com.app.publishservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String storageRoot = "storage";
    private long tokenRefreshAheadSeconds = 300L;
    private long reviewPollDelayMs = 60000L;
    private long reviewAutoPassSeconds = 180L;
    private PackageRepositoryProperties packageRepository = new PackageRepositoryProperties();
    private StoreApiProperties storeApi = new StoreApiProperties();

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public long getTokenRefreshAheadSeconds() {
        return tokenRefreshAheadSeconds;
    }

    public void setTokenRefreshAheadSeconds(long tokenRefreshAheadSeconds) {
        this.tokenRefreshAheadSeconds = tokenRefreshAheadSeconds;
    }

    public long getReviewPollDelayMs() {
        return reviewPollDelayMs;
    }

    public void setReviewPollDelayMs(long reviewPollDelayMs) {
        this.reviewPollDelayMs = reviewPollDelayMs;
    }

    public long getReviewAutoPassSeconds() {
        return reviewAutoPassSeconds;
    }

    public void setReviewAutoPassSeconds(long reviewAutoPassSeconds) {
        this.reviewAutoPassSeconds = reviewAutoPassSeconds;
    }

    public PackageRepositoryProperties getPackageRepository() {
        return packageRepository;
    }

    public void setPackageRepository(PackageRepositoryProperties packageRepository) {
        this.packageRepository = packageRepository;
    }

    public StoreApiProperties getStoreApi() {
        return storeApi;
    }

    public void setStoreApi(StoreApiProperties storeApi) {
        this.storeApi = storeApi;
    }

    public static class PackageRepositoryProperties {

        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
