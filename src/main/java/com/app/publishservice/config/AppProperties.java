package com.app.publishservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String storageRoot = "storage";
    private long tokenRefreshAheadSeconds = 300L;
    private long reviewPollDelayMs = 60000L;
    private long reviewAutoPassSeconds = 180L;
    private PackageRepositoryProperties packageRepository = new PackageRepositoryProperties();
    private StoreApiProperties storeApi = new StoreApiProperties();
    private PublishMetadataProperties publishMetadata = new PublishMetadataProperties();
    private JwtAuthProperties jwtAuth = new JwtAuthProperties();

    /**
     * 获取存储 Root。
     */
    public String getStorageRoot() {
        return storageRoot;
    }

    /**
     * 设置存储 Root。
     */
    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    /**
     * 获取令牌 Refresh Ahead Seconds。
     */
    public long getTokenRefreshAheadSeconds() {
        return tokenRefreshAheadSeconds;
    }

    /**
     * 设置令牌 Refresh Ahead Seconds。
     */
    public void setTokenRefreshAheadSeconds(long tokenRefreshAheadSeconds) {
        this.tokenRefreshAheadSeconds = tokenRefreshAheadSeconds;
    }

    /**
     * 获取审核 Poll Delay Ms。
     */
    public long getReviewPollDelayMs() {
        return reviewPollDelayMs;
    }

    /**
     * 设置审核 Poll Delay Ms。
     */
    public void setReviewPollDelayMs(long reviewPollDelayMs) {
        this.reviewPollDelayMs = reviewPollDelayMs;
    }

    /**
     * 获取审核 Auto Pass Seconds。
     */
    public long getReviewAutoPassSeconds() {
        return reviewAutoPassSeconds;
    }

    /**
     * 设置审核 Auto Pass Seconds。
     */
    public void setReviewAutoPassSeconds(long reviewAutoPassSeconds) {
        this.reviewAutoPassSeconds = reviewAutoPassSeconds;
    }

    /**
     * 获取包仓库。
     */
    public PackageRepositoryProperties getPackageRepository() {
        return packageRepository;
    }

    /**
     * 设置包仓库。
     */
    public void setPackageRepository(PackageRepositoryProperties packageRepository) {
        this.packageRepository = packageRepository;
    }

    /**
     * 获取商店 API。
     */
    public StoreApiProperties getStoreApi() {
        return storeApi;
    }

    /**
     * 设置商店 API。
     */
    public void setStoreApi(StoreApiProperties storeApi) {
        this.storeApi = storeApi;
    }

    /**
     * 获取发布元数据。
     */
    public PublishMetadataProperties getPublishMetadata() {
        return publishMetadata;
    }

    /**
     * 设置发布元数据。
     */
    public void setPublishMetadata(PublishMetadataProperties publishMetadata) {
        this.publishMetadata = publishMetadata;
    }

    public JwtAuthProperties getJwtAuth() {
        return jwtAuth;
    }

    public void setJwtAuth(JwtAuthProperties jwtAuth) {
        this.jwtAuth = jwtAuth;
    }

    public static class PackageRepositoryProperties {

        private String apkUrl32 = "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/%s_cmschina_armeabi_%s/cms_yht_32.apk";
        private String apkUrl64 = "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/%s_cmschina_arm64_%s/cms_yht_64.apk";
        private String baseUrl;
        private boolean streamUploadEnabled;
        private String authorization;
        private long downloadTimeoutSeconds = 30L;

        /**
         * 获取APK Url32。
         */
        public String getApkUrl32() {
            return apkUrl32;
        }

        /**
         * 设置APK Url32。
         */
        public void setApkUrl32(String apkUrl32) {
            this.apkUrl32 = apkUrl32;
        }

        /**
         * 获取APK Url64。
         */
        public String getApkUrl64() {
            return apkUrl64;
        }

        /**
         * 设置APK Url64。
         */
        public void setApkUrl64(String apkUrl64) {
            this.apkUrl64 = apkUrl64;
        }

        /**
         * 获取Base URL。
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置Base URL。
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 判断是否流上传 Enabled。
         */
        public boolean isStreamUploadEnabled() {
            return streamUploadEnabled;
        }

        /**
         * 设置流上传 Enabled。
         */
        public void setStreamUploadEnabled(boolean streamUploadEnabled) {
            this.streamUploadEnabled = streamUploadEnabled;
        }

        /**
         * 获取授权。
         */
        public String getAuthorization() {
            return authorization;
        }

        /**
         * 设置授权。
         */
        public void setAuthorization(String authorization) {
            this.authorization = authorization;
        }

        /**
         * 获取下载超时 Seconds。
         */
        public long getDownloadTimeoutSeconds() {
            return downloadTimeoutSeconds;
        }

        /**
         * 设置下载超时 Seconds。
         */
        public void setDownloadTimeoutSeconds(long downloadTimeoutSeconds) {
            this.downloadTimeoutSeconds = downloadTimeoutSeconds;
        }
    }

    public static class PublishMetadataProperties {

        private String baseDir = ".";
        private Map<String, Object> values = new LinkedHashMap<>();

        /**
         * 获取Base Dir。
         */
        public String getBaseDir() {
            return baseDir;
        }

        /**
         * 设置Base Dir。
         */
        public void setBaseDir(String baseDir) {
            this.baseDir = baseDir;
        }

        /**
         * 获取值。
         */
        public Map<String, Object> getValues() {
            return values;
        }

        /**
         * 设置值。
         */
        public void setValues(Map<String, Object> values) {
            this.values = values;
        }
    }

    public static class JwtAuthProperties {

        private boolean enabled = true;
        private String headerName = "Authentication";
        private String secret;
        private String cookieName = "Authentication";
        private String cookiePath = "/";
        private boolean cookieHttpOnly = true;
        private boolean cookieSecure;
        private String cookieSameSite = "Lax";
        private String audienceAesKey = "K6MIDdFi1NGk685H";
        private String audienceAesIv;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getCookieName() {
            return cookieName;
        }

        public void setCookieName(String cookieName) {
            this.cookieName = cookieName;
        }

        public String getCookiePath() {
            return cookiePath;
        }

        public void setCookiePath(String cookiePath) {
            this.cookiePath = cookiePath;
        }

        public boolean isCookieHttpOnly() {
            return cookieHttpOnly;
        }

        public void setCookieHttpOnly(boolean cookieHttpOnly) {
            this.cookieHttpOnly = cookieHttpOnly;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public String getCookieSameSite() {
            return cookieSameSite;
        }

        public void setCookieSameSite(String cookieSameSite) {
            this.cookieSameSite = cookieSameSite;
        }

        public String getAudienceAesKey() {
            return audienceAesKey;
        }

        public void setAudienceAesKey(String audienceAesKey) {
            this.audienceAesKey = audienceAesKey;
        }

        public String getAudienceAesIv() {
            return audienceAesIv;
        }

        public void setAudienceAesIv(String audienceAesIv) {
            this.audienceAesIv = audienceAesIv;
        }
    }
}
