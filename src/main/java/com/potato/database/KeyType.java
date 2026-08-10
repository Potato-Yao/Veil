package com.potato.database;

/**
 * SQL column types available for additional key columns.
 */
public enum KeyType {
    TEXT,
    LONG;

    @Override
    public String toString() {
        return switch (this) {
            case TEXT -> "TEXT";
            case LONG -> "BIGINT";
        };
    }
}
