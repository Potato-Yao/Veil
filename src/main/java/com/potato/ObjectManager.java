package com.potato;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;

public class ObjectManager {
    private static ArrayList<String> namespaceList = new ArrayList<>();
    private final String namespace;
    private final FileManager mainStorageManager;
    private final FileManager cacheStorageManager;
    private final DatabaseManager databaseManager;

    private ObjectManager(String namespace, FileManager mainStorageManager, FileManager cacheStorageManager, DatabaseManager databaseManager) {
        this.namespace = namespace;
        this.mainStorageManager = mainStorageManager;
        this.cacheStorageManager = cacheStorageManager;
        this.databaseManager = databaseManager;
    }

    public static ObjectManager build(String namespace, DatabaseManager databaseManager) {
        if (checkDuplicateNamespace(namespace)) {
            throw new IllegalArgumentException("The namespace \"" + namespace + "\" is already taken");
        }

        VeilConfiguration configuration = VeilConfiguration.getInstance();
        try {
            databaseManager.createTable(namespace);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return new ObjectManager(namespace, configuration.getMainStorageManager(), configuration.getCacheManager(), databaseManager);
    }

    public void put(String primaryKey, InputStream source, String ...additionKey) {

    }

    public static boolean checkDuplicateNamespace(String namespace) {
        return namespaceList.contains(namespace);
    }
}
