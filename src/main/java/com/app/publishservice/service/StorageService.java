package com.app.publishservice.service;

import com.app.publishservice.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class StorageService {

    private final Path rootPath;

    public StorageService(AppProperties properties) throws IOException {
        this.rootPath = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        Files.createDirectories(rootPath);
    }

    public Path allocatePath(String originalFilename) throws IOException {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.'))
                : "";
        Path targetDir = rootPath.resolve(day);
        Files.createDirectories(targetDir);
        return targetDir.resolve(UUID.randomUUID() + extension);
    }
}

