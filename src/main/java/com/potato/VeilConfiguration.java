package com.potato;

import com.potato.object.ObjectManager;
import com.potato.storage.DiskFileManager;
import com.potato.storage.FileManager;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

/**
 * Global configuration for the Veil library.
 *
 * <p>Holds the shared {@link DataSource}, main {@link FileManager} and optional cache
 * {@link FileManager}. It is a process-wide singleton: it must be initialized exactly
 * once via {@link #init(DataSource, FileManager, FileManager)} (or
 * {@link #initForDev()}) before {@link ObjectManager} instances are built.</p>
 */
public class VeilConfiguration {
    private final DataSource dataSource;
    private final FileManager mainStorageManager;
    private final FileManager cacheManager;
    private static volatile VeilConfiguration instance;

    private VeilConfiguration(DataSource dataSource, FileManager mainStorageManager, FileManager cacheManager) {
        this.dataSource = dataSource;
        this.mainStorageManager = mainStorageManager;
        this.cacheManager = cacheManager;
    }

    /**
     * Initializes the global configuration.
     *
     * <p>Must be called exactly once before any other Veil usage. If
     * {@code cacheManager} is {@code null}, the main storage manager is used as the
     * cache manager.</p>
     *
     * @param dataSource           the data source for metadata persistence
     * @param mainStorageManager   the file manager used as primary storage
     * @param cacheManager         the file manager used as cache, or {@code null}
     * @return the initialized {@link VeilConfiguration}
     * @throws IllegalStateException if the configuration was already initialized
     */
    public static synchronized VeilConfiguration init(DataSource dataSource, FileManager mainStorageManager, FileManager cacheManager) {
        if (instance != null) {
            throw new IllegalStateException("The VeilConfiguration is already initialized");
        }

        FileManager cache = cacheManager == null ? mainStorageManager : cacheManager;
        instance = new VeilConfiguration(dataSource, mainStorageManager, cache);

        return instance;
    }

    /**
     * Initializes the global configuration for development use.
     *
     * <p>Uses a SQLite data source writing to {@code ./veil_metadata.db} and a
     * {@link DiskFileManager} rooted at the current working directory.</p>
     *
     * @return the initialized {@link VeilConfiguration}
     * @throws IllegalStateException if the configuration was already initialized
     */
    public static VeilConfiguration initForDev() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:./veil_metadata.db");
        FileManager mainStorageManager = new DiskFileManager();
        return init(dataSource, mainStorageManager, null);
    }

    /**
     * Returns the initialized global configuration.
     *
     * @return the singleton {@link VeilConfiguration}
     * @throws IllegalStateException if the configuration has not been initialized yet
     */
    public static VeilConfiguration getInstance() {
        if (instance == null) {
            throw new IllegalStateException("The VeilConfiguration is not initialized yet");
        }

        return instance;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public FileManager getMainStorageManager() {
        return mainStorageManager;
    }

    public FileManager getCacheManager() {
        return cacheManager;
    }
}
