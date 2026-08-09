package com.potato;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DiskFileManager extends FileManager {
    private final Path rootPath;

    public DiskFileManager() {
        this(Path.of(""));
    }

    public DiskFileManager(Path rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    public FileManager build() {
        return new DiskFileManager(rootPath);
    }

    @Override
    public void put(String location, InputStream inputStream) {
        Path target = resolve(location);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                inputStream.transferTo(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public OutputStream get(String location) {
        Path target = resolve(location);
        try {
            Files.createDirectories(target.getParent());
            return Files.newOutputStream(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String location) {
        Path target = resolve(location);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String location) {
        return rootPath.resolve(location).normalize();
    }
}
