package com.potato.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@link FileManager} implementation backed by the local filesystem.
 *
 * <p>All locations are resolved against a root path (the current working directory by
 * default) and normalized. Reading a location opens an {@link OutputStream} that
 * overwrites the target file, creating any parent directories as needed.</p>
 */
public class DiskFileManager extends FileManager {
    private final Path rootPath;

    /**
     * Creates a file manager rooted at the current working directory.
     */
    public DiskFileManager() {
        this(Path.of(""));
    }

    /**
     * Creates a file manager rooted at the given path.
     *
     * @param rootPath  the root directory that all locations resolve against
     */
    public DiskFileManager(Path rootPath) {
        this.rootPath = rootPath;
    }

    /**
     * Creates a new {@link DiskFileManager} with the same root path.
     *
     * @return a new {@link DiskFileManager}
     */
    @Override
    public FileManager build() {
        return new DiskFileManager(rootPath);
    }

    /**
     * Writes the stream contents to {@code rootPath.resolve(location)}, creating parent
     * directories as needed.
     *
     * @param location     the relative location of the object
     * @param inputStream  the stream to read the object data from
     */
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

    /**
     * Opens an {@link OutputStream} to {@code rootPath.resolve(location)}, creating
     * parent directories as needed.
     *
     * @param location  the relative location of the object
     * @return an {@link OutputStream} for writing the object data
     */
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

    /**
     * Opens an {@link InputStream} to {@code rootPath.resolve(location)}.
     *
     * @param location  the relative location of the object
     * @return an {@link InputStream} for reading the object data
     */
    @Override
    public InputStream read(String location) {
        try {
            return Files.newInputStream(resolve(location));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deletes the object at {@code rootPath.resolve(location)} if it exists.
     *
     * @param location  the relative location of the object
     */
    @Override
    public void delete(String location) {
        Path target = resolve(location);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Atomically moves the object at {@code rootPath.resolve(from)} to
     * {@code rootPath.resolve(to)}, replacing any existing destination. Falls back to
     * a non-atomic move on filesystems that do not support atomic moves.
     *
     * @param from  the current relative location of the object
     * @param to    the destination relative location
     */
    @Override
    public void rename(String from, String to) {
        Path source = resolve(from);
        Path target = resolve(to);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallback) {
                throw new UncheckedIOException(fallback);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resolves a location against the root path and normalizes it.
     *
     * @param location  the relative location of the object
     * @return the absolute target path
     */
    private Path resolve(String location) {
        return rootPath.resolve(location).normalize();
    }
}
