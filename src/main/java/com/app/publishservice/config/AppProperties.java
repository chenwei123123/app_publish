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

        private String apkUrl32 = "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/%s_cmschina_armeabi_%s/cms_yht_32.apk";
        private String apkUrl64 = "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/%s_cmschina_arm64_%s/cms_yht_64.apk";
        private String baseUrl;
        private boolean streamUploadEnabled;
        private String authorization;
        private long downloadTimeoutSeconds = 30L;

        public String getApkUrl32() {
            return apkUrl32;
        }

        public void setApkUrl32(String apkUrl32) {
            this.apkUrl32 = apkUrl32;
        }

        public String getApkUrl64() {
            return apkUrl64;
        }

        public void setApkUrl64(String apkUrl64) {
            this.apkUrl64 = apkUrl64;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isStreamUploadEnabled() {
            return streamUploadEnabled;
        }

        public void setStreamUploadEnabled(boolean streamUploadEnabled) {
            this.streamUploadEnabled = streamUploadEnabled;
        }

        public String getAuthorization() {
            return authorization;
        }

        public void setAuthorization(String authorization) {
            this.authorization = authorization;
        }

        public long getDownloadTimeoutSeconds() {
            return downloadTimeoutSeconds;
        }

        public void setDownloadTimeoutSeconds(long downloadTimeoutSeconds) {
            this.downloadTimeoutSeconds = downloadTimeoutSeconds;
        }
    }
}
