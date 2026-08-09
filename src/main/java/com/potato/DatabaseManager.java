package com.potato;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract persistence layer for object metadata.
 *
 * <p>Each namespace gets a table named {@code veil_metadata_<namespace>} holding a
 * primary {@code key} column, any additional key columns (defined via
 * {@link Builder#keyColumn(String, KeyType)}), and standard metadata columns such as
 * file name, extension, size, MD5, timestamps and storage location.</p>
 *
 * <p>Concrete implementations are built with {@link #builder()}; see
 * {@link SqliteDatabaseManager} and {@link PostgresDatabaseManager} for the SQLite- and
 * PostgreSQL-based implementations.</p>
 */
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

    /**
     * Creates the metadata table for the given namespace.
     *
     * <p>The table is named {@code veil_metadata_<name>} and contains the key columns
     * and the standard metadata columns.</p>
     *
     * @param name  the namespace to create the table for
     * @throws SQLException if the table could not be created
     */
    public void createTable(String name) throws SQLException {
        String tableName = Config.DATABASE_PREFIX + "_" + name;
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" + columnDefinitions() + ")";

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * @return the full column definitions for a metadata table, joining the key columns
     *         and the metadata columns
     */
    private String columnDefinitions() {
        List<String> columns = new ArrayList<>(keyColumns);
        columns.addAll(metadataColumns);
        return String.join(", ", columns);
    }

    /**
     * @return the names of the additional key columns defined for this database manager
     */
    public ArrayList<String> getAdditionalKeyColumnNames() {
        return additionalKeyColumnNames;
    }

    /**
     * Inserts a new metadata row for an object.
     *
     * <p>Fails if a row with the same key already exists. Use
     * {@link #upsert(String, String, Map, String, String, long, String, String, String, String)}
     * to replace an existing row.</p>
     *
     * @param namespace        the namespace the object belongs to
     * @param key              the primary key of the object
     * @param additionKeys     values for the additional key columns, or {@code null}
     * @param fileName         the stored file name
     * @param extension        the file extension
     * @param size             the size of the file in bytes
     * @param md5              the MD5 digest of the file contents in hex
     * @param createdAt        ISO-8601 timestamp of creation
     * @param storageType      the type of storage (e.g. {@code "DISK"})
     * @param storageLocation  the location of the file within the file manager
     * @throws IllegalArgumentException if {@code additionKeys} contains an unknown column
     */
    public void insert(String namespace, String key, Map<String, String> additionKeys,
                       String fileName, String extension, long size, String md5,
                       String createdAt, String storageType, String storageLocation) {
        executeInsert(namespace, key, additionKeys, fileName, extension, size, md5,
                createdAt, storageType, storageLocation, false);
    }

    /**
     * Inserts or replaces the metadata row for an object.
     *
     * <p>When a row with the same key already exists, its metadata columns are updated
     * in place.</p>
     *
     * @param namespace        the namespace the object belongs to
     * @param key              the primary key of the object
     * @param additionKeys     values for the additional key columns, or {@code null}
     * @param fileName         the stored file name
     * @param extension        the file extension
     * @param size             the size of the file in bytes
     * @param md5              the MD5 digest of the file contents in hex
     * @param createdAt        ISO-8601 timestamp of creation
     * @param storageType      the type of storage (e.g. {@code "DISK"})
     * @param storageLocation  the location of the file within the file manager
     * @throws IllegalArgumentException if {@code additionKeys} contains an unknown column
     */
    public void upsert(String namespace, String key, Map<String, String> additionKeys,
                       String fileName, String extension, long size, String md5,
                       String createdAt, String storageType, String storageLocation) {
        executeInsert(namespace, key, additionKeys, fileName, extension, size, md5,
                createdAt, storageType, storageLocation, true);
    }

    /**
     * Returns the storage location of the object with the given key.
     *
     * @param namespace  the namespace of the object
     * @param key        the primary key of the object
     * @return the storage location, or {@code null} if no such object exists
     */
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

    /**
     * Builds and executes the INSERT (or UPSERT) statement for an object's metadata.
     *
     * <p>Dynamically assembles the column list from the additional key columns and the
     * standard metadata columns. When {@code overwrite} is {@code true}, the statement
     * is appended with {@code ON CONFLICT(key) DO UPDATE} to replace existing rows.</p>
     *
     * @param namespace        the namespace the object belongs to
     * @param key              the primary key of the object
     * @param additionKeys     values for the additional key columns, or {@code null}
     * @param fileName         the stored file name
     * @param extension        the file extension
     * @param size             the size of the file in bytes
     * @param md5              the MD5 digest of the file contents in hex
     * @param createdAt        ISO-8601 timestamp of creation
     * @param storageType      the type of storage (e.g. {@code "DISK"})
     * @param storageLocation  the location of the file within the file manager
     * @param overwrite        whether to replace an existing row with the same key
     * @throws IllegalArgumentException if {@code additionKeys} contains an unknown column
     */
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

    /**
     * Starts building a {@link DatabaseManager}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for configuring and constructing a {@link DatabaseManager}.
     *
     * <p>The {@link DataSource} is required and additional key columns may be declared
     * with {@link #keyColumn(String, KeyType)}. {@link #build()} returns a
     * {@link DatabaseManager} for the configured {@link DatabaseType}, defaulting to
     * {@link SqliteDatabaseManager}.</p>
     */
    public static class Builder {
        private DataSource dataSource;
        private DatabaseType databaseType = DatabaseType.SQLITE;
        private final HashMap<String, KeyType> keyColumns = new HashMap<>();

        /**
         * Sets the data source used for metadata persistence.
         *
         * @param dataSource  the {@link DataSource} to use
         * @return this builder
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * Sets the database engine backing this database manager.
         *
         * @param databaseType  the database type to use
         * @return this builder
         */
        public Builder databaseType(DatabaseType databaseType) {
            this.databaseType = databaseType;
            return this;
        }

        /**
         * Declares an additional key column.
         *
         * <p>Additional keys extend the primary key so that objects can be addressed by
         * more than one value, for example a {@code user_id}.</p>
         *
         * @param name  the column name
         * @param type  the column type
         * @return this builder
         */
        public Builder keyColumn(String name, KeyType type) {
            keyColumns.put(name, type);
            return this;
        }

        /**
         * Builds the configured {@link DatabaseManager}.
         *
         * @return a new {@link DatabaseManager} for the configured {@link DatabaseType}
         */
        public DatabaseManager build() {
            return switch (databaseType) {
                case SQLITE -> new SqliteDatabaseManager(dataSource, keyColumns);
                case POSTGRES -> new PostgresDatabaseManager(dataSource, keyColumns);
            };
        }
    }
}
