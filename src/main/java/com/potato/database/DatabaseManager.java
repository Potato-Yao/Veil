package com.potato.database;

import com.potato.object.ObjectMetadata;
import com.potato.object.ObjectReference;
import com.potato.object.ObjectStatement;

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
 * file name, extension, size, MD5, timestamps and storage location. The primary key
 * spans the {@code key} column and every additional key column, so an object's
 * identity is the composite of all of them and is enforced as a database constraint.</p>
 *
 * <p>Concrete implementations are built with {@link #builder()}; see
 * {@link SqliteDatabaseManager} and {@link PostgresDatabaseManager} for the SQLite- and
 * PostgreSQL-based implementations.</p>
 */
public abstract class DatabaseManager {
    protected final DataSource dataSource;
    protected final ArrayList<String> keyColumns;
    protected final ArrayList<String> additionalKeyColumnNames;
    protected final Map<String, KeyType> keyTypes;
    protected final ArrayList<String> metadataColumns;
    protected final ArrayList<String> metadataColumnNames;

    public DatabaseManager(DataSource dataSource, HashMap<String, KeyType> keyColumns) {
        this.dataSource = dataSource;

        this.keyColumns = new ArrayList<>();
        this.keyColumns.add("key TEXT NOT NULL");

        this.additionalKeyColumnNames = new ArrayList<>();
        if (keyColumns != null) {
            keyColumns.forEach((name, type) -> {
                this.keyColumns.add(name + " " + type);
                this.additionalKeyColumnNames.add(name);
            });
        }
        this.keyTypes = keyColumns == null ? Map.of() : Map.copyOf(keyColumns);

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
     *         and the metadata columns, with a composite primary key over the primary key
     *         and all additional key columns
     */
    private String columnDefinitions() {
        List<String> columns = new ArrayList<>(keyColumns);
        columns.addAll(metadataColumns);
        List<String> primaryKey = new ArrayList<>();
        primaryKey.add("key");
        primaryKey.addAll(additionalKeyColumnNames);
        columns.add("PRIMARY KEY (" + String.join(", ", primaryKey) + ")");
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
     * {@link #upsert(String, ObjectStatement, ObjectMetadata)} to replace an existing
     * row. The metadata is persisted exactly as given, including access statistics and
     * timestamps; pass an {@link ObjectMetadata} with {@code null} last-access and zero
     * access count for a fresh insert.</p>
     *
     * @param namespace  the namespace the object belongs to
     * @param statement  the statement carrying the primary key and additional key values
     * @param metadata   the metadata of the object to persist
     * @throws IllegalArgumentException if the statement does not fit an insert or contains an unknown column
     */
    public void insert(String namespace, ObjectStatement statement, ObjectMetadata metadata) {
        upsertMetadata(namespace, statement, metadata, false);
    }

    /**
     * Inserts or replaces the metadata row for an object.
     *
     * <p>When a row with the same key already exists, its metadata columns are updated
     * in place while access statistics are left untouched.</p>
     *
     * @param namespace  the namespace the object belongs to
     * @param statement  the statement carrying the primary key and additional key values
     * @param metadata   the metadata of the object to persist
     * @throws IllegalArgumentException if the statement does not fit an insert or contains an unknown column
     */
    public void upsert(String namespace, ObjectStatement statement, ObjectMetadata metadata) {
        upsertMetadata(namespace, statement, metadata, true);
    }

    /**
     * Returns the storage location of the object with the given identity.
     *
     * @param namespace  the namespace of the object
     * @param statement  the statement carrying the full identity (primary key and
     *                   additional key values)
     * @return the storage location, or {@code null} if no such object exists
     * @throws IllegalArgumentException if the statement does not carry the full identity
     */
    public String getStorageLocation(String namespace, ObjectStatement statement) {
        requireIdentity(statement);
        List<QueryValue> params = new ArrayList<>();
        String where = String.join(" AND ", buildKeyConditions(statement, params));
        String sql = "SELECT storage_location FROM " + Config.DATABASE_PREFIX + "_" + namespace
                + " WHERE " + where;
        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            bindParameters(preparedStatement, params);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("storage_location") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the metadata of the object with the given identity.
     *
     * @param namespace  the namespace of the object
     * @param statement  the statement carrying the full identity (primary key and
     *                   additional key values)
     * @return the object's metadata, or {@code null} if no such object exists
     * @throws IllegalArgumentException if the statement does not carry the full identity
     */
    public ObjectMetadata getMetadata(String namespace, ObjectStatement statement) {
        requireIdentity(statement);
        List<QueryValue> params = new ArrayList<>();
        String where = String.join(" AND ", buildKeyConditions(statement, params));
        String sql = "SELECT file_name, file_extension, file_size, md5, created_at, last_accessed_at,"
                + " storage_type, storage_location, access_count FROM " + Config.DATABASE_PREFIX + "_" + namespace
                + " WHERE " + where;
        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            bindParameters(preparedStatement, params);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * Deletes the metadata row of the object with the given identity.
     *
     * @param namespace  the namespace of the object
     * @param statement  the statement carrying the full identity (primary key and
     *                   additional key values)
     * @return {@code true} if a row was removed, {@code false} if no such object exists
     * @throws IllegalArgumentException if the statement does not carry the full identity
     */
    public boolean delete(String namespace, ObjectStatement statement) {
        requireIdentity(statement);
        List<QueryValue> params = new ArrayList<>();
        String where = String.join(" AND ", buildKeyConditions(statement, params));
        String sql = "DELETE FROM " + Config.DATABASE_PREFIX + "_" + namespace + " WHERE " + where;
        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            bindParameters(preparedStatement, params);
            return preparedStatement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Records access to the object with the given identity, updating its last access
     * timestamp and incrementing its access count.
     *
     * @param namespace  the namespace of the object
     * @param statement  the statement carrying the full identity (primary key and
     *                   additional key values)
     * @throws IllegalArgumentException if the statement does not carry the full identity
     */
    public void updateAccess(String namespace, ObjectStatement statement) {
        requireIdentity(statement);
        List<QueryValue> params = new ArrayList<>();
        params.add(new QueryValue.StringValue(Instant.now().toString()));
        String where = String.join(" AND ", buildKeyConditions(statement, params));
        String sql = "UPDATE " + Config.DATABASE_PREFIX + "_" + namespace
                + " SET last_accessed_at = ?, access_count = access_count + 1 WHERE " + where;
        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            bindParameters(preparedStatement, params);
            preparedStatement.executeUpdate();
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
                    Map<String, String> kv = new HashMap<>();
                    for (String column : additionalKeyColumnNames) {
                        String value = resultSet.getString(column);
                        if (value != null) {
                            kv.put(column, value);
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
                    results.add(new ObjectReference(key, kv, metadata));
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
     * Updates metadata rows of a namespace.
     *
     * <p>When the statement carries a primary key, it is treated as a single-row update
     * targeted by key and must not carry conditions. Otherwise it is a batch update
     * applying the statement's assignments to every row matching its conditions.
     * The assignments form the {@code SET} clause; only metadata columns are updatable.
     * Returns the number of rows changed.</p>
     *
     * @param namespace  the namespace to update
     * @param statement  the statement carrying the assignments, an optional key and conditions
     * @return the number of updated rows
     * @throws IllegalArgumentException if the statement has no key or assignments, carries
     *                                  conditions for a keyed update, or references an
     *                                  unknown or non-updatable column
     */
    public long executeUpdate(String namespace, ObjectStatement statement) {
        boolean byKey = statement.key() != null;
        statement.validateFor(byKey ? ObjectStatement.Operation.UPDATE_BY_KEY : ObjectStatement.Operation.UPDATE);

        List<QueryValue> params = new ArrayList<>();
        List<String> setClauses = new ArrayList<>();
        for (ObjectStatement.Assignment assignment : statement.assignments()) {
            validateUpdateColumn(assignment.column());
            setClauses.add(assignment.column() + " = ?");
            params.add(assignment.value());
        }

        List<String> conditions = new ArrayList<>();
        if (byKey) {
            requireIdentity(statement);
            conditions.addAll(buildKeyConditions(statement, params));
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
     * Ensures the statement carries a primary key.
     *
     * @param statement  the statement to validate
     * @throws IllegalArgumentException if the statement has no key
     */
    private void requireKey(ObjectStatement statement) {
        if (statement.key() == null || statement.key().isEmpty()) {
            throw new IllegalArgumentException("Statement must carry a key");
        }
    }

    /**
     * Ensures the statement carries the full identity of an object: a non-empty primary
     * key and a value for every additional key column.
     *
     * @param statement  the statement to validate
     * @throws IllegalArgumentException if the statement is missing the primary key or any
     *                                  additional key column value
     */
    private void requireIdentity(ObjectStatement statement) {
        requireKey(statement);
        for (String column : additionalKeyColumnNames) {
            if (statement.kv().get(column) == null) {
                throw new IllegalArgumentException("Statement must carry a value for key column \"" + column + "\"");
            }
        }
    }

    /**
     * Builds the equality conditions matching the full identity of an object, appending
     * their parameters to the given list.
     *
     * @param statement  the statement carrying the full identity
     * @param params     the list to append the identity parameters to
     * @return the rendered identity conditions, one per key column
     */
    private List<String> buildKeyConditions(ObjectStatement statement, List<QueryValue> params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("key = ?");
        params.add(new QueryValue.StringValue(statement.key()));
        for (String column : additionalKeyColumnNames) {
            conditions.add(column + " = ?");
            params.add(keyValue(column, statement.kv().get(column)));
        }
        return conditions;
    }

    /**
     * Converts a key column value from its string form into a {@link QueryValue} bound
     * with the column's declared type.
     *
     * @param column  the additional key column name
     * @param value   the value as a string
     * @return the typed query value
     * @throws IllegalArgumentException if the value does not fit the column's type
     */
    private QueryValue keyValue(String column, String value) {
        if (keyTypes.get(column) == KeyType.LONG) {
            try {
                return new QueryValue.LongValue(Long.parseLong(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Key column \"" + column + "\" expects a long, got: \"" + value + "\"", e);
            }
        }
        return new QueryValue.StringValue(value);
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
     * Persists the metadata row of an object, inserting it or replacing an existing row.
     *
     * <p>Dynamically assembles the column list from the additional key columns and the
     * standard metadata columns. When {@code overwrite} is {@code false}, the insert
     * fails if a row with the same identity already exists. When {@code overwrite} is
     * {@code true}, the statement is appended with {@code ON CONFLICT (key, ...)} over
     * the full identity to replace the existing row's metadata columns; key columns and
     * access statistics are left untouched because the identity is immutable.</p>
     *
     * @param namespace  the namespace the object belongs to
     * @param statement  the statement carrying the primary key and additional key values
     * @param metadata   the metadata of the object to persist
     * @param overwrite  whether to replace an existing row with the same identity
     * @throws IllegalArgumentException if the statement does not carry the full identity or contains an unknown column
     */
    public void upsertMetadata(String namespace, ObjectStatement statement, ObjectMetadata metadata, boolean overwrite) {
        statement.validateFor(ObjectStatement.Operation.INSERT);
        requireIdentity(statement);
        for (Map.Entry<String, String> entry : statement.kv().entrySet()) {
            if (!additionalKeyColumnNames.contains(entry.getKey())) {
                throw new IllegalArgumentException("Unknown key column: \"" + entry.getKey() + "\"");
            }
        }

        List<String> columns = new ArrayList<>();
        List<QueryValue> values = new ArrayList<>();
        columns.add("key");
        values.add(new QueryValue.StringValue(statement.key()));
        for (String column : additionalKeyColumnNames) {
            columns.add(column);
            values.add(keyValue(column, statement.kv().get(column)));
        }

        columns.add("file_name");
        values.add(new QueryValue.StringValue(metadata.fileName()));
        columns.add("file_extension");
        values.add(new QueryValue.StringValue(metadata.fileExtension()));
        columns.add("file_size");
        values.add(new QueryValue.LongValue(metadata.fileSize()));
        columns.add("md5");
        values.add(new QueryValue.StringValue(metadata.md5()));
        columns.add("created_at");
        values.add(new QueryValue.StringValue(metadata.createdAt()));
        columns.add("storage_type");
        values.add(new QueryValue.StringValue(metadata.storageType()));
        columns.add("storage_location");
        values.add(new QueryValue.StringValue(metadata.storageLocation()));
        columns.add("last_accessed_at");
        values.add(new QueryValue.StringValue(metadata.lastAccessedAt()));
        columns.add("access_count");
        values.add(new QueryValue.LongValue(metadata.accessCount()));

        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(Config.DATABASE_PREFIX).append("_").append(namespace)
                .append(" (").append(String.join(", ", columns)).append(") VALUES (").append(placeholders).append(")");

        if (overwrite) {
            List<String> updates = new ArrayList<>();
            for (String column : metadataColumnNames) {
                if (column.equals("last_accessed_at") || column.equals("access_count")) {
                    continue;
                }
                updates.add(column + " = excluded." + column);
            }
            List<String> conflictTarget = new ArrayList<>();
            conflictTarget.add("key");
            conflictTarget.addAll(additionalKeyColumnNames);
            sql.append(" ON CONFLICT (").append(String.join(", ", conflictTarget))
                    .append(") DO UPDATE SET ").append(String.join(", ", updates));
        }

        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) {
                values.get(i).bind(preparedStatement, i + 1);
            }
            preparedStatement.executeUpdate();
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
