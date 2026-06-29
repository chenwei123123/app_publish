package com.app.publishservice.service;

import com.app.publishservice.common.exception.StoreApiException;
import com.app.publishservice.config.AppProperties;
import com.app.publishservice.domain.entity.AppVersion;
import com.app.publishservice.util.ApkDownloadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class StorePackageSupport {

    private static final Logger log = LoggerFactory.getLogger(StorePackageSupport.class);
    private static final DateTimeFormatter PACKAGE_CACHE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String CMS_ARTIFACT_HOST = "artifacts.cmschina.com.cn";

    private final RestClient restClient;
    private final AppProperties appProperties;
    private final StoreRequestSupport requestSupport;
    private final HttpClient packageHttpClient;

    StorePackageSupport(RestClient restClient, AppProperties appProperties, StoreRequestSupport requestSupport) {
        this.restClient = restClient;
        this.appProperties = appProperties;
        this.requestSupport = requestSupport;
        this.packageHttpClient = HttpClient.newBuilder()
                .connectTimeout(packageDownloadTimeout())
                .build();
    }

    Path requireLocalPackage(AppVersion version) {
        return requireLocalPackage(version.getPackageUrl(), "Package path is empty");
    }

    Path requireLocalPackage(String packageLocation, String emptyMessage) {
        if (!StringUtils.hasText(packageLocation)) {
            throw new IllegalArgumentException(emptyMessage);
        }
        String normalizedLocation = packageLocation.trim();
        Path packagePath = toPath(normalizedLocation);
        if (packagePath != null && Files.exists(packagePath) && Files.isRegularFile(packagePath)) {
            return packagePath;
        }

        String downloadUrl = resolvePackageDownloadUrl(normalizedLocation, packagePath);
        if (!StringUtils.hasText(downloadUrl)) {
            throw new IllegalArgumentException("Package file not found: " + normalizedLocation);
        }
        return downloadPackageToLocal(downloadUrl, inferFileName(normalizedLocation));
    }

    String sha256Hex(Path path) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    messageDigest.update(buffer, 0, read);
                }
            }
            return toHex(messageDigest.digest());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read package file: " + path, ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    String md5Hex(AbstractStorePlatformPublisher.PackageContentSource packageSource) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            try (InputStream inputStream = openPackageStream(packageSource)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    messageDigest.update(buffer, 0, read);
                }
            }
            return toHex(messageDigest.digest());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read package file: " + packageSource.fileName(), ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 algorithm is not available", ex);
        }
    }

    String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign request", ex);
        }
    }

    String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            String hex = Integer.toHexString(value & 0xFF);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read file size: " + path, ex);
        }
    }

    String resolvePackageDownloadUrl(String packageLocation, Path packagePath) {
        if (isHttpUrl(packageLocation)) {
            return packageLocation;
        }

        String baseUrl = appProperties.getPackageRepository().getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }

        String relativePath = resolveRepositoryRelativePath(packageLocation, packagePath);
        if (!StringUtils.hasText(relativePath)) {
            return null;
        }
        return joinUrl(baseUrl, relativePath);
    }

    Path downloadPackageToLocal(String downloadUrl, String fileName) {
        String normalizedFileName = StringUtils.hasText(fileName) ? fileName : "package.apk";
        Path target = allocateDownloadedPackagePath(normalizedFileName);
        log.info("Download package from repository, url={}, target={}", downloadUrl, target);
        return requestSupport.executeStoreRequest(
                "download package from repository",
                () -> restClient.get()
                        .uri(downloadUrl)
                        .headers(headers -> applyPackageAuthorization(headers, downloadUrl))
                        .exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                throw new StoreApiException(
                                        HttpStatus.BAD_GATEWAY,
                                        "Failed to download package from repository: status=" + response.getStatusCode()
                                );
                            }
                            try (InputStream inputStream = response.getBody()) {
                                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                                return target;
                            } catch (IOException ex) {
                                throw new IllegalStateException("Failed to save downloaded package: " + target, ex);
                            }
                        })
        );
    }

    Object uploadResource(AbstractStorePlatformPublisher.PackageContentSource packageSource) {
        if (packageSource.localPath() != null) {
            return new FileSystemResource(packageSource.localPath());
        }
        return new RemotePackageResource(packageSource.fileName(), packageSource.remoteUrl());
    }

    Path toPath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    String inferFileName(String packageLocation) {
        String normalized = packageLocation.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    String buildQueryString(Map<String, ?> params) {
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> {
            if (value != null) {
                pairs.add(encodeQueryParam(key) + "=" + encodeQueryParam(String.valueOf(value)));
            }
        });
        return String.join("&", pairs);
    }

    private InputStream openPackageStream(AbstractStorePlatformPublisher.PackageContentSource packageSource) throws IOException {
        if (packageSource.localPath() != null) {
            return Files.newInputStream(packageSource.localPath());
        }
        return openRemotePackageStream(packageSource.remoteUrl());
    }

    private InputStream openRemotePackageStream(String downloadUrl) throws IOException {
        if (!StringUtils.hasText(downloadUrl)) {
            throw new IllegalArgumentException("Remote package url is empty");
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(packageDownloadTimeout())
                .GET();
        String authorization = resolvePackageAuthorization(downloadUrl);
        if (StringUtils.hasText(authorization)) {
            requestBuilder.header(HttpHeaders.AUTHORIZATION, authorization);
        }

        HttpResponse<InputStream> response;
        try {
            response = packageHttpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Package stream request interrupted");
            interrupted.initCause(ex);
            throw interrupted;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) {
                // close response body before surfacing the error
            }
            throw new StoreApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to open remote package stream: status=" + response.statusCode()
            );
        }
        return response.body();
    }

    private String resolveRepositoryRelativePath(String packageLocation, Path packagePath) {
        if (packagePath != null) {
            if (!packagePath.isAbsolute()) {
                return normalizeRepositoryPath(packagePath.toString());
            }

            try {
                Path storageRoot = Path.of(appProperties.getStorageRoot()).toAbsolutePath().normalize();
                Path normalizedPackagePath = packagePath.toAbsolutePath().normalize();
                if (normalizedPackagePath.startsWith(storageRoot)) {
                    return normalizeRepositoryPath(storageRoot.relativize(normalizedPackagePath).toString());
                }
            } catch (InvalidPathException ex) {
                log.debug("Skip storage root relative path resolution, packageLocation={}", packageLocation, ex);
            }

            Path fileName = packagePath.getFileName();
            return fileName == null ? null : normalizeRepositoryPath(fileName.toString());
        }
        return normalizeRepositoryPath(packageLocation);
    }

    private void applyPackageAuthorization(HttpHeaders headers, String downloadUrl) {
        String authorization = resolvePackageAuthorization(downloadUrl);
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    private String resolvePackageAuthorization(String downloadUrl) {
        if (!StringUtils.hasText(downloadUrl)) {
            return null;
        }
        if (!isPackageRepositoryUrl(downloadUrl) && !isCmsArtifactUrl(downloadUrl)) {
            return null;
        }
        String configuredAuthorization = appProperties.getPackageRepository().getAuthorization();
        if (StringUtils.hasText(configuredAuthorization)) {
            return configuredAuthorization.trim();
        }
        return isCmsArtifactUrl(downloadUrl) ? ApkDownloadUtil.defaultAuthorizationValue() : null;
    }

    private boolean isPackageRepositoryUrl(String downloadUrl) {
        String baseUrl = appProperties.getPackageRepository().getBaseUrl();
        return StringUtils.hasText(baseUrl) && downloadUrl.startsWith(baseUrl.trim());
    }

    private boolean isCmsArtifactUrl(String downloadUrl) {
        try {
            return CMS_ARTIFACT_HOST.equalsIgnoreCase(URI.create(downloadUrl).getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private Path allocateDownloadedPackagePath(String fileName) {
        try {
            Path targetDir = Path.of(appProperties.getStorageRoot())
                    .toAbsolutePath()
                    .normalize()
                    .resolve("remote-cache")
                    .resolve(LocalDate.now().format(PACKAGE_CACHE_DATE_FORMATTER));
            Path requestDir = targetDir.resolve(UUID.randomUUID().toString());
            Files.createDirectories(requestDir);
            return requestDir.resolve(fileName);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create package cache directory", ex);
        }
    }

    private boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private String normalizeRepositoryPath(String value) {
        return value.replace('\\', '/').replaceFirst("^/+", "");
    }

    private String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String joinUrl(String baseUrl, String relativePath) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedRelativePath = normalizeRepositoryPath(relativePath);
        return normalizedBaseUrl + "/" + normalizedRelativePath;
    }

    private Duration packageDownloadTimeout() {
        return Duration.ofSeconds(Math.max(appProperties.getPackageRepository().getDownloadTimeoutSeconds(), 1L));
    }

    private final class RemotePackageResource extends AbstractResource {

        private final String fileName;
        private final String downloadUrl;

        private RemotePackageResource(String fileName, String downloadUrl) {
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public String getDescription() {
            return "remote package " + downloadUrl;
        }

        @Override
        public String getFilename() {
            return fileName;
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return openRemotePackageStream(downloadUrl);
        }
    }
}
