package com.potato;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
    protected final ArrayList<String> metadataColumnNames;

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
        this.metadataColumnNames = new ArrayList<>();
        addMetadataColumn("file_name", "TEXT NOT NULL");
        addMetadataColumn("file_extension", "TEXT NOT NULL");
        addMetadataColumn("file_size", "BIGINT NOT NULL");
        addMetadataColumn("md5", "TEXT NOT NULL");
        addMetadataColumn("created_at", "TEXT NOT NULL");
        addMetadataColumn("last_accessed_at", "TEXT");
        addMetadataColumn("storage_type", "TEXT NOT NULL");
        addMetadataColumn("storage_location", "TEXT NOT NULL");
        addMetadataColumn("access_count", "INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * Registers a metadata column and its plain name.
     *
     * @param name  the column name
     * @param type  the column type and constraints
     */
    private void addMetadataColumn(String name, String type) {
        metadataColumns.add(name + " " + type);
        metadataColumnNames.add(name);
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
     * Returns the metadata of the object with the given key.
     *
     * @param namespace  the namespace of the object
     * @param key        the primary key of the object
     * @return the object's metadata, or {@code null} if no such object exists
     */
    public ObjectMetadata getMetadata(String namespace, String key) {
        String sql = "SELECT file_name, file_extension, file_size, md5, created_at, last_accessed_at,"
                + " storage_type, storage_location, access_count FROM " + Config.DATABASE_PREFIX + "_" + namespace
                + " WHERE key = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ObjectMetadata(
                        resultSet.getString("file_name"),
                        resultSet.getString("file_extension"),
                        resultSet.getLong("file_size"),
                        resultSet.getString("md5"),
                        resultSet.getString("created_at"),
                        resultSet.getString("last_accessed_at"),
                        resultSet.getString("storage_type"),
                        resultSet.getString("storage_location"),
                        resultSet.getLong("access_count"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Deletes the metadata row of the object with the given key.
     *
     * @param namespace  the namespace of the object
     * @param key        the primary key of the object
     * @return {@code true} if a row was removed, {@code false} if no such object exists
     */
    public boolean delete(String namespace, String key) {
        String sql = "DELETE FROM " + Config.DATABASE_PREFIX + "_" + namespace + " WHERE key = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Records access to the object with the given key, updating its last access
     * timestamp and incrementing its access count.
     *
     * @param namespace  the namespace of the object
     * @param key        the primary key of the object
     */
    public void updateAccess(String namespace, String key) {
        String sql = "UPDATE " + Config.DATABASE_PREFIX + "_" + namespace
                + " SET last_accessed_at = ?, access_count = access_count + 1 WHERE key = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Runs a query over the metadata of a namespace.
     *
     * <p>Conditions reference the key column, additional key columns, or metadata
     * columns; every referenced column is validated before the statement is built.</p>
     *
     * @param namespace  the namespace to query
     * @param statement  the query to run
     * @return the matching objects, ordered and limited as specified by {@code statement}
     * @throws IllegalArgumentException if {@code statement} references an unknown column
     */
    public List<ObjectReference> query(String namespace, ObjectStatement statement) {
        statement.validateFor(ObjectStatement.Operation.QUERY);
        BuiltQuery builtQuery = buildQuery(namespace, statement, false);

        List<ObjectReference> results = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(builtQuery.sql())) {
            bindParameters(preparedStatement, builtQuery.params());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    String key = resultSet.getString("key");
                    Map<String, String> additionKeys = new HashMap<>();
                    for (String column : additionalKeyColumnNames) {
                        String value = resultSet.getString(column);
                        if (value != null) {
                            additionKeys.put(column, value);
                        }
                    }
                    ObjectMetadata metadata = new ObjectMetadata(
                            resultSet.getString("file_name"),
                            resultSet.getString("file_extension"),
                            resultSet.getLong("file_size"),
                            resultSet.getString("md5"),
                            resultSet.getString("created_at"),
                            resultSet.getString("last_accessed_at"),
                            resultSet.getString("storage_type"),
                            resultSet.getString("storage_location"),
                            resultSet.getLong("access_count"));
                    results.add(new ObjectReference(key, additionKeys, metadata));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return results;
    }

    /**
     * Counts the objects in a namespace matching the given statement's conditions.
     *
     * <p>The statement must not carry assignments. Ordering, limit and offset are
     * ignored.</p>
     *
     * @param namespace  the namespace to query
     * @param statement  the statement whose conditions should be applied
     * @return the number of matching objects
     * @throws IllegalArgumentException if {@code statement} does not fit a query or
     *                                  references an unknown column
     */
    public long count(String namespace, ObjectStatement statement) {
        statement.validateFor(ObjectStatement.Operation.QUERY);
        BuiltQuery builtQuery = buildQuery(namespace, statement, true);

        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(builtQuery.sql())) {
            bindParameters(preparedStatement, builtQuery.params());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Updates the metadata rows of a namespace matching the given statement's
     * conditions.
     *
     * <p>The statement's assignments form the {@code SET} clause; only metadata columns
     * are updatable. Returns the number of rows changed.</p>
     *
     * @param namespace  the namespace to update
     * @param statement  the statement carrying the assignments and conditions
     * @return the number of updated rows
     * @throws IllegalArgumentException if the statement has no assignments or references
     *                                  an unknown or non-updatable column
     */
    public long executeUpdate(String namespace, ObjectStatement statement) {
        return executeUpdate(namespace, null, statement);
    }

    /**
     * Updates the metadata row of a single object, targeting it by primary key.
     *
     * <p>Applies the statement's assignments to the row whose key matches
     * {@code key}, additionally restricted by any conditions in the statement. Returns
     * the number of rows changed.</p>
     *
     * @param namespace  the namespace of the object
     * @param key        the primary key of the object to update
     * @param statement  the statement carrying the assignments (and optional conditions)
     * @return the number of updated rows
     * @throws IllegalArgumentException if the statement has no assignments or references
     *                                  an unknown or non-updatable column
     */
    public long executeUpdate(String namespace, String key, ObjectStatement statement) {
        statement.validateFor(ObjectStatement.Operation.UPDATE);

        List<QueryValue> params = new ArrayList<>();
        List<String> setClauses = new ArrayList<>();
        for (ObjectStatement.Assignment assignment : statement.assignments()) {
            validateUpdateColumn(assignment.column());
            setClauses.add(assignment.column() + " = ?");
            params.add(assignment.value());
        }

        List<String> conditions = new ArrayList<>();
        if (key != null) {
            conditions.add("key = ?");
            params.add(new QueryValue.StringValue(key));
        }
        conditions.addAll(buildConditions(statement, params));

        StringBuilder sql = new StringBuilder("UPDATE ").append(Config.DATABASE_PREFIX).append("_").append(namespace)
                .append(" SET ").append(String.join(", ", setClauses));
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        return executeUpdateSql(sql.toString(), params);
    }

    /**
     * Deletes the metadata rows of a namespace matching the given statement's
     * conditions.
     *
     * <p>The statement must not carry assignments; ordering, limit and offset are
     * ignored. An empty statement deletes every row in the namespace.</p>
     *
     * @param namespace  the namespace to delete from
     * @param statement  the statement whose conditions should be applied
     * @return the number of deleted rows
     * @throws IllegalArgumentException if {@code statement} does not fit a delete or
     *                                  references an unknown column
     */
    public long executeDelete(String namespace, ObjectStatement statement) {
        statement.validateFor(ObjectStatement.Operation.DELETE);
        List<QueryValue> params = new ArrayList<>();
        List<String> conditions = buildConditions(statement, params);

        StringBuilder sql = new StringBuilder("DELETE FROM ").append(Config.DATABASE_PREFIX).append("_").append(namespace);
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        return executeUpdateSql(sql.toString(), params);
    }

    /**
     * Executes a write statement and returns the number of affected rows.
     *
     * @param sql     the SQL to execute
     * @param params  the parameters bound to the SQL, in order
     * @return the number of affected rows
     */
    private long executeUpdateSql(String sql, List<QueryValue> params) {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds the SQL and parameters for a query, optionally as a {@code COUNT(*)}.
     *
     * @param namespace  the namespace to query
     * @param statement  the statement to render
     * @param countOnly  whether to render {@code SELECT COUNT(*)} without ordering or paging
     * @return the SQL and its parameters
     */
    private BuiltQuery buildQuery(String namespace, ObjectStatement statement, boolean countOnly) {
        List<QueryValue> params = new ArrayList<>();
        List<String> conditions = buildConditions(statement, params);

        StringBuilder sql = new StringBuilder(countOnly ? "SELECT COUNT(*)" : "SELECT " + selectColumns())
                .append(" FROM ").append(Config.DATABASE_PREFIX).append("_").append(namespace);
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!countOnly) {
            if (!statement.orderBys().isEmpty()) {
                List<String> orderBys = new ArrayList<>();
                for (ObjectStatement.OrderBy orderBy : statement.orderBys()) {
                    validateQueryColumn(orderBy.column());
                    orderBys.add(orderBy.column() + " " + orderBy.direction());
                }
                sql.append(" ORDER BY ").append(String.join(", ", orderBys));
            }
            if (statement.limit() != null) {
                sql.append(" LIMIT ?");
                params.add(new QueryValue.IntValue(statement.limit()));
            }
            if (statement.offset() != null) {
                sql.append(" OFFSET ?");
                params.add(new QueryValue.IntValue(statement.offset()));
            }
        }
        return new BuiltQuery(sql.toString(), params);
    }

    /**
     * Renders the {@code WHERE} clause of the statement, validating each column and
     * collecting its parameters.
     *
     * @param statement  the statement whose conditions should be rendered
     * @param params     the list to append the condition parameters to
     * @return the rendered condition clauses
     */
    private List<String> buildConditions(ObjectStatement statement, List<QueryValue> params) {
        List<String> conditions = new ArrayList<>();
        for (ObjectStatement.Condition condition : statement.conditions()) {
            validateQueryColumn(condition.column());
            String column = condition.column();
            List<QueryValue> values = condition.params();
            switch (condition.operator()) {
                case "BETWEEN" -> {
                    conditions.add(column + " BETWEEN ? AND ?");
                    params.add(values.get(0));
                    params.add(values.get(1));
                }
                case "IN" -> {
                    conditions.add(column + " IN (" + String.join(", ", Collections.nCopies(values.size(), "?")) + ")");
                    params.addAll(values);
                }
                default -> {
                    conditions.add(column + " " + condition.operator() + " ?");
                    params.add(values.get(0));
                }
            }
        }
        return conditions;
    }

    /**
     * @return the comma-separated columns selected for a full query row
     */
    private String selectColumns() {
        List<String> columns = new ArrayList<>();
        columns.add("key");
        columns.addAll(additionalKeyColumnNames);
        columns.addAll(metadataColumnNames);
        return String.join(", ", columns);
    }

    /**
     * Binds the given parameters onto a prepared statement, in order.
     *
     * @param preparedStatement the statement to bind onto
     * @param params            the values to bind
     * @throws SQLException if a parameter cannot be bound
     */
    private void bindParameters(PreparedStatement preparedStatement, List<QueryValue> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            params.get(i).bind(preparedStatement, i + 1);
        }
    }

    /**
     * Ensures the given column is a known key or metadata column.
     *
     * @param column  the column name to validate
     * @throws IllegalArgumentException if the column is unknown
     */
    private void validateQueryColumn(String column) {
        if (!column.equals("key") && !additionalKeyColumnNames.contains(column) && !metadataColumnNames.contains(column)) {
            throw new IllegalArgumentException("Unknown column: \"" + column + "\"");
        }
    }

    /**
     * Ensures the given column is a metadata column that can be assigned in an update.
     *
     * <p>Key and additional key columns are excluded so that addressing and storage
     * integrity cannot be broken.</p>
     *
     * @param column  the column name to validate
     * @throws IllegalArgumentException if the column is not updatable
     */
    private void validateUpdateColumn(String column) {
        if (!metadataColumnNames.contains(column)) {
            throw new IllegalArgumentException("Column is not updatable: \"" + column + "\"");
        }
    }

    /**
     * A rendered query: its SQL and the parameters bound to it, in order.
     *
     * @param sql     the SQL statement
     * @param params  the parameters bound to the SQL, in order
     */
    private record BuiltQuery(String sql, List<QueryValue> params) {
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
        List<QueryValue> values = new ArrayList<>();
        columns.add("key");
        values.add(new QueryValue.StringValue(key));

        if (additionKeys != null) {
            for (Map.Entry<String, String> entry : additionKeys.entrySet()) {
                if (!additionalKeyColumnNames.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Unknown key column: \"" + entry.getKey() + "\"");
                }
                columns.add(entry.getKey());
                values.add(new QueryValue.StringValue(entry.getValue()));
            }
        }

        columns.add("file_name");
        values.add(new QueryValue.StringValue(fileName));
        columns.add("file_extension");
        values.add(new QueryValue.StringValue(extension));
        columns.add("file_size");
        values.add(new QueryValue.LongValue(size));
        columns.add("md5");
        values.add(new QueryValue.StringValue(md5));
        columns.add("created_at");
        values.add(new QueryValue.StringValue(createdAt));
        columns.add("storage_type");
        values.add(new QueryValue.StringValue(storageType));
        columns.add("storage_location");
        values.add(new QueryValue.StringValue(storageLocation));

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
                values.get(i).bind(statement, i + 1);
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
