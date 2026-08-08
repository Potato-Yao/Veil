package com.potato;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

public abstract class DatabaseManager {
    protected final DataSource dataSource;
    protected final ArrayList<String> keyColumns;
    protected final ArrayList<String> metadataColumns;

    public DatabaseManager(DataSource dataSource, HashMap<String, KeyType> keyColumns) {
        this.dataSource = dataSource;

        this.keyColumns = new ArrayList<>();
        this.keyColumns.add("key TEXT PRIMARY KEY");

        if (keyColumns != null) {
            keyColumns.forEach((name, type) -> {
                this.keyColumns.add(name + " " + type);
            });
        }

        this.metadataColumns = new ArrayList<>();
        this.metadataColumns.add("file_name TEXT NOT NULL");
        this.metadataColumns.add("file_extension TEXT NOT NULL");
        this.metadataColumns.add("file_size BIGINT NOT NULL");
        this.metadataColumns.add("md5 TEXT NOT NULL");
        this.metadataColumns.add("created_at TEXT NOT NULL");
        this.metadataColumns.add("last_accessed_at TEXT");
    }

    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public abstract void createTable(String name) throws SQLException;

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
