package com.app.publishservice.util;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ApkDownloadUtil {

    static final String APK_URL_32 = "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/%s_cmschina_armeabi_%s/cms_yht_32.apk";
    static final String APK_URL_64 = "https://artifacts.cmschina.com.cn:443/artifact/cms_app-generic-release-wx/android/%s_cmschina_arm64_%s/cms_yht_64.apk";
    private static final String AUTH_VALUE = "Basic eHVjaGFvNjpBS0NwOGpSN0U5G9LZjZzMWY1N1pGcG9Za2F5SHpqSE52bkRUZWhpY2NZWDDwOUZyNG1YeTJuaHgyV3dmQkdtZ1JoWWdWZXVG";

    private ApkDownloadUtil() {
    }

    public static void downloadApk32(Integer versionCode, String buildCode, String savePath) throws IOException {
        downloadApk(buildApk32Url(versionCode, buildCode), savePath);
    }

    public static void downloadApk64(Integer versionCode, String buildCode, String savePath) throws IOException {
        downloadApk(buildApk64Url(versionCode, buildCode), savePath);
    }

    public static String buildApk32Url(Integer versionCode, String buildCode) {
        return APK_URL_32.formatted(requireVersionCode(versionCode), requireBuildCode(buildCode));
    }

    public static String buildApk64Url(Integer versionCode, String buildCode) {
        return APK_URL_64.formatted(requireVersionCode(versionCode), requireBuildCode(buildCode));
    }

    public static String defaultAuthorizationValue() {
        return AUTH_VALUE;
    }

    private static void downloadApk(String downloadUrl, String savePath) throws IOException {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, defaultAuthorizationValue());
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(downloadUrl, HttpMethod.GET, request, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IOException("Failed to download apk, status=" + response.getStatusCode());
        }
        Path target = Path.of(savePath);
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, response.getBody());
    }

    private static String requireVersionCode(Integer versionCode) {
        if (versionCode == null || versionCode < 1) {
            throw new IllegalArgumentException("versionCode must be greater than 0");
        }
        return String.valueOf(versionCode);
    }

    private static String requireBuildCode(String buildCode) {
        if (buildCode == null || buildCode.trim().isEmpty()) {
            throw new IllegalArgumentException("buildCode must not be blank");
        }
        return buildCode.trim();
    }
}
