package com.app.publishservice.util;

import com.app.publishservice.config.AppProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public final class ApkDownloadUtil {

    static final String DEFAULT_APK_URL_32 = "https://artifacts.cmschina.com.cn:443/artifactory/cms_app-generic-release-wx/android/%s_cmschina_armeabi_%s/cms_yht_32.apk";
    static final String DEFAULT_APK_URL_64 = "https://artifacts.cmschina.com.cn:443/artifactory/cms_app-generic-release-wx/android/%s_cmschina_arm64_%s/cms_yht_64.apk";
    static final String DEFAULT_APP_URL = "https://artifacts.cmschina.com.cn:443/artifactory/INT_CMSAPP_HARMONY-generic-release-wx/harmony/%s_%s/CMSApp_HM-yht_release-signed.app";
    static final String DEFAULT_AUTH_VALUE = "Basic eHVjaGFvNjpBS0NwOGpSN0U5G9LZjZzMWY1N1pGcG9Za2F5SHpqSE52bkRUZWhpY2NZWDDwOUZyNG1YeTJuaHgyV3dmQkdtZ1JoWWdWZXVG";
    private static volatile String apkUrl32Template = DEFAULT_APK_URL_32;
    private static volatile String apkUrl64Template = DEFAULT_APK_URL_64;
    private static volatile String appUrlTemplate = DEFAULT_APP_URL;
    private static volatile String authorizationValue = DEFAULT_AUTH_VALUE;
    private static volatile int timeoutMillis = 300000;

    /**
     * 初始化ApkDownloadUtil。
     */
    public ApkDownloadUtil(AppProperties appProperties) {
        configure(appProperties.getPackageRepository());
    }

    /**
     * 下载Apk32。
     */
    public static void downloadApk32(String versionCode, String buildCode, String savePath) throws Exception {
        downloadPackage(buildApk32Url(versionCode, buildCode), savePath);
    }

    /**
     * 下载Apk64。
     */
    public static void downloadApk64(String versionCode, String buildCode, String savePath) throws Exception {
        downloadPackage(buildApk64Url(versionCode, buildCode), savePath);
    }

    public static void downloadApp(String versionCode, String buildCode, String savePath) throws Exception {
        downloadPackage(buildAppUrl(versionCode, buildCode), savePath);
    }

    /**
     * 构建Apk32 URL。
     */
    public static String buildApk32Url(String versionCode, String buildCode) {
        return apkUrl32Template.formatted(requireVersionCode(versionCode), requireBuildCode(buildCode));
    }

    /**
     * 构建Apk64 URL。
     */
    public static String buildApk64Url(String versionCode, String buildCode) {
        return apkUrl64Template.formatted(requireVersionCode(versionCode), requireBuildCode(buildCode));
    }

    public static String buildAppUrl(String versionCode, String buildCode) {
        return appUrlTemplate.formatted(requireVersionCode(versionCode), requireBuildCode(buildCode));
    }

    /**
     * 获取默认授权值。
     */
    public static String defaultAuthorizationValue() {
        return authorizationValue;
    }

    /**
     * 提取文件名称。
     */
    public static String extractFileName(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("downloadUrl must not be blank");
        }
        String normalized = downloadUrl.trim().split("[?#]", 2)[0];
        int separatorIndex = normalized.lastIndexOf('/');
        if (separatorIndex < 0 || separatorIndex == normalized.length() - 1) {
            throw new IllegalArgumentException("Cannot extract file name from downloadUrl: " + downloadUrl);
        }
        return normalized.substring(separatorIndex + 1);
    }

    /**
     * 下载APK。
     */
    private static void downloadPackage(String downloadUrl, String savePath) throws Exception {
        Path target = Path.of(savePath);
        if (Files.exists(target) && Files.isRegularFile(target)) {
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, defaultAuthorizationValue());
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(downloadUrl, HttpMethod.GET, request, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IOException("Failed to download package, status=" + response.getStatusCode());
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, response.getBody());
    }

    /**
     * 校验版本编码。
     */
    private static String requireVersionCode(String versionCode) {
        return VersionCodeUtil.requireNonBlank(versionCode);
    }

    /**
     * 校验Build 编码。
     */
    private static String requireBuildCode(String buildCode) {
        if (buildCode == null || buildCode.trim().isEmpty()) {
            throw new IllegalArgumentException("buildCode must not be blank");
        }
        return buildCode.trim();
    }

    /**
     * 处理configure相关逻辑。
     */
    public static void configure(AppProperties.PackageRepositoryProperties properties) {
        if (properties == null) {
            apkUrl32Template = DEFAULT_APK_URL_32;
            apkUrl64Template = DEFAULT_APK_URL_64;
            appUrlTemplate = DEFAULT_APP_URL;
            authorizationValue = DEFAULT_AUTH_VALUE;
            timeoutMillis = 300000;
            return;
        }
        apkUrl32Template = StringUtils.hasText(properties.getApkUrl32()) ? properties.getApkUrl32().trim() : DEFAULT_APK_URL_32;
        apkUrl64Template = StringUtils.hasText(properties.getApkUrl64()) ? properties.getApkUrl64().trim() : DEFAULT_APK_URL_64;
        appUrlTemplate = StringUtils.hasText(properties.getAppUrl()) ? properties.getAppUrl().trim() : DEFAULT_APP_URL;
        authorizationValue = StringUtils.hasText(properties.getAuthorization()) ? properties.getAuthorization().trim() : DEFAULT_AUTH_VALUE;
        long timeoutSeconds = Math.max(properties.getDownloadTimeoutSeconds(), 1L);
        timeoutMillis = Math.toIntExact(Math.min(timeoutSeconds * 1000L, Integer.MAX_VALUE));
    }
}
