package com.potato;

import javax.sql.DataSource;
import java.util.HashMap;

/**
 * PostgreSQL-based {@link DatabaseManager}.
 *
 * <p>Created via {@link DatabaseManager#builder()}. Manages metadata tables named
 * {@code veil_metadata_<namespace>} in the configured PostgreSQL database.</p>
 */
public class PostgresDatabaseManager extends DatabaseManager {
    PostgresDatabaseManager(DataSource dataSource, HashMap<String, KeyType> keyColumns) {
        super(dataSource, keyColumns);
    }
}
