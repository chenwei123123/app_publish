package com.app.publishservice.service;

import com.app.publishservice.service.model.PackageMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import com.app.publishservice.util.VersionCodeUtil;

@Service
public class PackageInspectorService {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+){1,3})(?:[-_.]?(?:build|b)(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final String[] METADATA_PATHS = {
            "app-publish-metadata.json",
            "META-INF/app-publish-metadata.json",
            "assets/app-publish-metadata.json"
    };
    private static final String[] REINFORCE_KEYWORDS = {"reinforce", "reinforced", "jiagu", "secneo", "bangcle", "ijiami"};

    private final ObjectMapper objectMapper;

    /**
     * 初始化PackageInspectorService。
     */
    public PackageInspectorService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 处理inspect相关逻辑。
     */
    public PackageMetadata inspect(Path filePath) {
        String packageType = detectPackageType(filePath.getFileName().toString());
        ArchiveMetadata archiveMetadata = readArchiveMetadata(filePath)
                .orElseGet(() -> inferFromFilename(filePath.getFileName().toString()));
        if (archiveMetadata.versionName() == null || archiveMetadata.versionCode() == null) {
            throw new IllegalArgumentException("Cannot detect package version automatically");
        }
        return new PackageMetadata(
                packageType,
                archiveMetadata.versionName(),
                archiveMetadata.versionCode(),
                archiveMetadata.reinforced(),
                sha256(filePath)
        );
    }

    /**
     * 处理inspect相关逻辑。
     */
    public PackageMetadata inspect(MultipartFile file, Path target) throws IOException {
        file.transferTo(target);
        return inspect(target);
    }

    /**
     * 读取Archive 元数据。
     */
    private Optional<ArchiveMetadata> readArchiveMetadata(Path filePath) {
        try (ZipFile zipFile = new ZipFile(filePath.toFile(), StandardCharsets.UTF_8)) {
            for (String metadataPath : METADATA_PATHS) {
                ZipEntry entry = zipFile.getEntry(metadataPath);
                if (entry != null) {
                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                        JsonNode jsonNode = objectMapper.readTree(inputStream);
                        String versionName = textOrNull(jsonNode, "versionName");
                        String versionCode = textOrNull(jsonNode, "versionCode");
                        boolean reinforced = boolOrFallback(jsonNode, "reinforced", detectReinforced(zipFile));
                        return Optional.of(new ArchiveMetadata(versionName, versionCode, reinforced));
                    }
                }
            }
            return Optional.of(inferFromFilenameAndEntries(filePath.getFileName().toString(), zipFile));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /**
     * 处理infer Filename Entries相关逻辑。
     */
    private ArchiveMetadata inferFromFilenameAndEntries(String filename, ZipFile zipFile) {
        ArchiveMetadata fallback = inferFromFilename(filename);
        return new ArchiveMetadata(
                fallback.versionName(),
                fallback.versionCode(),
                fallback.reinforced() || detectReinforced(zipFile)
        );
    }

    /**
     * 处理detect Reinforced相关逻辑。
     */
    private boolean detectReinforced(ZipFile zipFile) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            String entryName = entries.nextElement().getName().toLowerCase(Locale.ROOT);
            for (String keyword : REINFORCE_KEYWORDS) {
                if (entryName.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 处理infer Filename相关逻辑。
     */
    private ArchiveMetadata inferFromFilename(String filename) {
        Matcher matcher = VERSION_PATTERN.matcher(filename);
        String versionName = null;
        String versionCode = null;
        if (matcher.find()) {
            versionName = matcher.group(1);
            if (matcher.group(2) != null) {
                versionCode = VersionCodeUtil.normalize(matcher.group(2));
            }
        }
        boolean reinforced = filename.toLowerCase(Locale.ROOT).contains("reinforce")
                || filename.toLowerCase(Locale.ROOT).contains("jiagu")
                || filename.toLowerCase(Locale.ROOT).contains("protected");
        return new ArchiveMetadata(versionName, versionCode, reinforced);
    }

    /**
     * 处理detect 包类型相关逻辑。
     */
    private String detectPackageType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk")) {
            return "apk";
        }
        if (lower.endsWith(".aab")) {
            return "aab";
        }
        if (lower.endsWith(".ipa")) {
            return "ipa";
        }
        throw new IllegalArgumentException("Only APK, AAB and IPA are supported");
    }

    /**
     * 处理sha256相关逻辑。
     */
    private String sha256(Path filePath) {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest()) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to generate package checksum", ex);
        }
    }

    /**
     * 处理文本 Null相关逻辑。
     */
    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /**
     * 处理bool Fallback相关逻辑。
     */
    private boolean boolOrFallback(JsonNode node, String field, boolean fallback) {
        return node.has(field) ? node.get(field).asBoolean() : fallback;
    }

    private record ArchiveMetadata(String versionName, String versionCode, boolean reinforced) {
    }
}
