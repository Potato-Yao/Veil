package com.potato;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

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

    public void put(String primaryKey, String fileName, InputStream source, Map<String, String> additionKey) {
        store(primaryKey, fileName, source, additionKey, false);
    }

    public void overwritePut(String primaryKey, String fileName, InputStream source, Map<String, String> additionKey) {
        store(primaryKey, fileName, source, additionKey, true);
    }

    private void store(String primaryKey, String fileName, InputStream source, Map<String, String> additionKey, boolean overwrite) {
        validateAdditionKeys(additionKey);
        String location = buildLocation(primaryKey, additionKey);

        if (overwrite) {
            String existing = databaseManager.getStorageLocation(namespace, primaryKey);
            if (existing != null && !existing.equals(location)) {
                mainStorageManager.delete(existing);
            }
        }

        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            CountingInputStream counting = new CountingInputStream(source);
            DigestInputStream digesting = new DigestInputStream(counting, md5);

            mainStorageManager.put(location, digesting);

            long size = counting.getByteCount();
            String md5Hex = HexFormat.of().formatHex(md5.digest());
            String extension = extractExtension(fileName);
            String createdAt = Instant.now().toString();
            if (overwrite) {
                databaseManager.upsert(namespace, primaryKey, additionKey, fileName, extension, size, md5Hex,
                        createdAt, "DISK", location);
            } else {
                databaseManager.insert(namespace, primaryKey, additionKey, fileName, extension, size, md5Hex,
                        createdAt, "DISK", location);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void validateAdditionKeys(Map<String, String> additionKey) {
        if (additionKey == null) {
            return;
        }
        for (String name : additionKey.keySet()) {
            if (!databaseManager.getAdditionalKeyColumnNames().contains(name)) {
                throw new IllegalArgumentException("Unknown key column: \"" + name + "\"");
            }
        }
    }

    private String buildLocation(String primaryKey, Map<String, String> additionKey) {
        StringBuilder name = new StringBuilder(primaryKey);
        if (additionKey != null) {
            for (String column : databaseManager.getAdditionalKeyColumnNames()) {
                if (additionKey.containsKey(column)) {
                    name.append('_').append(additionKey.get(column));
                }
            }
        }
        return namespace + "/" + name;
    }

    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? "" : fileName.substring(dot + 1);
    }

    public static boolean checkDuplicateNamespace(String namespace) {
        return namespaceList.contains(namespace);
    }
}
