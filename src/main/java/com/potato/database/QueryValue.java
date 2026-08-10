package com.potato.database;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * A typed value that can be bound to a {@link PreparedStatement}.
 *
 * <p>Query and insert values are represented by one of the {@code long}, {@code int}
 * or {@code String} implementations, each of which knows how to bind itself.</p>
 */
public sealed interface QueryValue permits QueryValue.LongValue, QueryValue.IntValue, QueryValue.StringValue {
    /**
     * Binds this value to the given parameter position.
     *
     * @param statement  the statement to bind onto
     * @param index      the 1-based parameter index
     * @throws SQLException if the value cannot be bound
     */
    void bind(PreparedStatement statement, int index) throws SQLException;

    /**
     * A {@code long} value.
     *
     * @param value  the value
     */
    record LongValue(long value) implements QueryValue {
        @Override
        public void bind(PreparedStatement statement, int index) throws SQLException {
            statement.setLong(index, value);
        }
    }

    /**
     * An {@code int} value.
     *
     * @param value  the value
     */
    record IntValue(int value) implements QueryValue {
        @Override
        public void bind(PreparedStatement statement, int index) throws SQLException {
            statement.setInt(index, value);
        }
    }

    /**
     * A {@code String} value.
     *
     * @param value  the value
     */
    record StringValue(String value) implements QueryValue {
        @Override
        public void bind(PreparedStatement statement, int index) throws SQLException {
            statement.setString(index, value);
        }
    }
}
