package com.potato;

import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

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

    public static synchronized VeilConfiguration init(DataSource dataSource, FileManager mainStorageManager, FileManager cacheManager) {
        if (instance != null) {
            throw new IllegalStateException("The VeilConfiguration is already initialized");
        }

        FileManager cache = cacheManager == null ? mainStorageManager : cacheManager;
        instance = new VeilConfiguration(dataSource, mainStorageManager, cache);

        return instance;
    }

    public static VeilConfiguration initForDev() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:./veil_metadata.db");
        FileManager mainStorageManager = new FileManager();
        return init(dataSource, mainStorageManager, null);
    }

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
