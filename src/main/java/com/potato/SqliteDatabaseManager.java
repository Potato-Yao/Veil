package com.potato;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SqliteDatabaseManager extends DatabaseManager {
    SqliteDatabaseManager(DataSource dataSource, HashMap<String, KeyType> keyColumns) {
        super(dataSource, keyColumns);
    }

    @Override
    public void createTable(String name) throws SQLException {
        String tableName = Config.DATABASE_PREFIX + "_" + name;
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" + columnDefinitions() + ")";

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String columnDefinitions() {
        List<String> columns = new ArrayList<>(keyColumns);
        columns.addAll(metadataColumns);
        return String.join(", ", columns);
    }
}
