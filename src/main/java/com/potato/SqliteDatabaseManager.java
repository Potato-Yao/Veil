package com.potato;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * SQLite-based {@link DatabaseManager}.
 *
 * <p>Created via {@link DatabaseManager#builder()}. Manages metadata tables named
 * {@code veil_metadata_<namespace>} in the configured SQLite database.</p>
 */
public class SqliteDatabaseManager extends DatabaseManager {
    SqliteDatabaseManager(DataSource dataSource, HashMap<String, KeyType> keyColumns) {
        super(dataSource, keyColumns);
    }

    /**
     * Creates the metadata table for a namespace if it does not already exist.
     *
     * <p>The table is named {@code veil_metadata_<name>} and contains the key columns
     * and the standard metadata columns.</p>
     *
     * @param name  the namespace to create the table for
     * @throws SQLException if the table could not be created
     */
    @Override
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
}
