package com.projectardor.resume.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalResumeStorage implements ResumeStorage {

    private final Path root;

    public LocalResumeStorage(@Value("${app.storage.resume-directory}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public String save(byte[] content, String extension) {
        try {
            Files.createDirectories(root);
            String storageKey = UUID.randomUUID() + extension;
            Path target = resolve(storageKey);
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            return storageKey;
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存简历文件", exception);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取简历文件", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new IllegalStateException("无法删除简历文件", exception);
        }
    }

    private Path resolve(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法的简历存储键");
        }
        return resolved;
    }
}
