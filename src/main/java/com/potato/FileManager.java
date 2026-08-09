package com.potato;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstract contract for the file storage layer.
 *
 * <p>Responsible for storing, retrieving, and deleting raw object bytes. Objects are
 * addressed by a relative {@code location} string produced by the
 * {@link ObjectManager}. Implementations may back onto the local disk, an object store,
 * or any other medium; see {@link DiskFileManager} for the filesystem implementation.</p>
 */
public abstract class FileManager {
    /**
     * Creates a fresh instance of this file manager.
     *
     * @return a new, independently configured {@link FileManager}
     */
    public abstract FileManager build();

    /**
     * Writes the contents of the given stream to the specified location.
     *
     * @param location     the relative location of the object
     * @param inputStream  the stream to read the object data from
     */
    public abstract void put(String location, InputStream inputStream);

    /**
     * Opens an output stream that writes to the specified location.
     *
     * @param location  the relative location of the object
     * @return an {@link OutputStream} for writing the object data
     */
    public abstract OutputStream get(String location);

    /**
     * Opens an input stream that reads from the specified location.
     *
     * @param location  the relative location of the object
     * @return an {@link InputStream} for reading the object data
     */
    public abstract InputStream read(String location);

    /**
     * Deletes the object at the specified location.
     *
     * @param location  the relative location of the object
     */
    public abstract void delete(String location);
}
