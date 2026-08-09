package com.potato;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class DatabaseManager {
    protected final DataSource dataSource;
    protected final ArrayList<String> keyColumns;
    protected final ArrayList<String> additionalKeyColumnNames;
    protected final ArrayList<String> metadataColumns;

    public DatabaseManager(DataSource dataSource, HashMap<String, KeyType> keyColumns) {
        this.dataSource = dataSource;

        this.keyColumns = new ArrayList<>();
        this.keyColumns.add("key TEXT PRIMARY KEY");

        this.additionalKeyColumnNames = new ArrayList<>();
        if (keyColumns != null) {
            keyColumns.forEach((name, type) -> {
                this.keyColumns.add(name + " " + type);
                this.additionalKeyColumnNames.add(name);
            });
        }

        this.metadataColumns = new ArrayList<>();
        this.metadataColumns.add("file_name TEXT NOT NULL");
        this.metadataColumns.add("file_extension TEXT NOT NULL");
        this.metadataColumns.add("file_size BIGINT NOT NULL");
        this.metadataColumns.add("md5 TEXT NOT NULL");
        this.metadataColumns.add("created_at TEXT NOT NULL");
        this.metadataColumns.add("last_accessed_at TEXT");
        this.metadataColumns.add("storage_type TEXT NOT NULL");
        this.metadataColumns.add("storage_location TEXT NOT NULL");
    }

    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public abstract void createTable(String name) throws SQLException;

    public ArrayList<String> getAdditionalKeyColumnNames() {
        return additionalKeyColumnNames;
    }

    public void insert(String namespace, String key, Map<String, String> additionKeys,
                       String fileName, String extension, long size, String md5,
                       String createdAt, String storageType, String storageLocation) {
        executeInsert(namespace, key, additionKeys, fileName, extension, size, md5,
                createdAt, storageType, storageLocation, false);
    }

    public void upsert(String namespace, String key, Map<String, String> additionKeys,
                       String fileName, String extension, long size, String md5,
                       String createdAt, String storageType, String storageLocation) {
        executeInsert(namespace, key, additionKeys, fileName, extension, size, md5,
                createdAt, storageType, storageLocation, true);
    }

    public String getStorageLocation(String namespace, String key) {
        String sql = "SELECT storage_location FROM " + Config.DATABASE_PREFIX + "_" + namespace
                + " WHERE key = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("storage_location") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void executeInsert(String namespace, String key, Map<String, String> additionKeys,
                               String fileName, String extension, long size, String md5,
                               String createdAt, String storageType, String storageLocation,
                               boolean overwrite) {
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        columns.add("key");
        values.add(key);

        if (additionKeys != null) {
            for (Map.Entry<String, String> entry : additionKeys.entrySet()) {
                if (!additionalKeyColumnNames.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Unknown key column: \"" + entry.getKey() + "\"");
                }
                columns.add(entry.getKey());
                values.add(entry.getValue());
            }
        }

        columns.add("file_name");
        values.add(fileName);
        columns.add("file_extension");
        values.add(extension);
        columns.add("file_size");
        values.add(size);
        columns.add("md5");
        values.add(md5);
        columns.add("created_at");
        values.add(createdAt);
        columns.add("storage_type");
        values.add(storageType);
        columns.add("storage_location");
        values.add(storageLocation);

        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(Config.DATABASE_PREFIX).append("_").append(namespace)
                .append(" (").append(String.join(", ", columns)).append(") VALUES (").append(placeholders).append(")");

        if (overwrite) {
            List<String> updates = new ArrayList<>();
            for (int i = 1; i < columns.size(); i++) {
                updates.add(columns.get(i) + " = excluded." + columns.get(i));
            }
            sql.append(" ON CONFLICT(key) DO UPDATE SET ").append(String.join(", ", updates));
        }

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) {
                Object value = values.get(i);
                if (value instanceof Long) {
                    statement.setLong(i + 1, (Long) value);
                } else {
                    statement.setString(i + 1, String.valueOf(value));
                }
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DataSource dataSource;
        private final HashMap<String, KeyType> keyColumns = new HashMap<>();

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        public Builder keyColumn(String name, KeyType type) {
            keyColumns.put(name, type);
            return this;
        }

        public SqliteDatabaseManager build() {
            return new SqliteDatabaseManager(dataSource, keyColumns);
        }
    }
}
