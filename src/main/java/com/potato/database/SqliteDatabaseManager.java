package com.potato.database;

import javax.sql.DataSource;
import java.util.HashMap;

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
}
