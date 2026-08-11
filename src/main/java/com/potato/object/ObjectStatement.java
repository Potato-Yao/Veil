package com.potato.object;

import com.potato.database.DatabaseManager;
import com.potato.database.QueryValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A database-agnostic statement over object metadata.
 *
 * <p>Describes <em>which rows</em> (via {@link Condition}s), <em>what to change</em>
 * (via {@link Assignment}s) and <em>what to store</em> (via {@link #key()} and
 * {@link #kv()}). The operation performed is chosen by the {@link DatabaseManager}
 * method it is passed to:
 * {@link DatabaseManager#query(String, ObjectStatement)},
 * {@link DatabaseManager#count(String, ObjectStatement)},
 * {@link DatabaseManager#insert(String, ObjectStatement, ObjectMetadata)},
 * {@link DatabaseManager#executeUpdate(String, ObjectStatement)} or
 * {@link DatabaseManager#executeDelete(String, ObjectStatement)}.</p>
 *
 * <p>The primary key value is set with {@link Builder#key(String)} and the values of
 * the additional key columns with {@link Builder#kv(String, String)} (or
 * {@link Builder#kv(Map)}), allowing a statement to express the values of an insert.
 * Conditions may reference the key column, additional key columns, or any metadata
 * column; column names are validated by the executing {@link DatabaseManager}.</p>
 *
 * <p>Instances are created with {@link #builder()}.</p>
 */
public class ObjectStatement {
    private final String key;
    private final Map<String, String> kv;
    private final List<Condition> conditions;
    private final List<Assignment> assignments;
    private final List<OrderBy> orderBys;
    private final Integer limit;
    private final Integer offset;

    private ObjectStatement(String key, Map<String, String> kv, List<Condition> conditions,
                            List<Assignment> assignments, List<OrderBy> orderBys, Integer limit, Integer offset) {
        this.key = key;
        this.kv = kv == null ? Map.of() : Map.copyOf(kv);
        this.conditions = List.copyOf(conditions);
        this.assignments = List.copyOf(assignments);
        this.orderBys = List.copyOf(orderBys);
        this.limit = limit;
        this.offset = offset;
    }

    /**
     * Comparison operators usable in {@link Builder#where(String, Op, String)}.
     */
    public enum Op {
        EQ,
        NEQ,
        GT,
        GTE,
        LT,
        LTE,
        LIKE;

        @Override
        public String toString() {
            return switch (this) {
                case EQ -> "=";
                case NEQ -> "<>";
                case GT -> ">";
                case GTE -> ">=";
                case LT -> "<";
                case LTE -> "<=";
                case LIKE -> "LIKE";
            };
        }
    }

    /**
     * Sort directions usable in {@link Builder#orderBy(String, Direction)}.
     */
    public enum Direction {
        ASC,
        DESC;

        @Override
        public String toString() {
            return switch (this) {
                case ASC -> "ASC";
                case DESC -> "DESC";
            };
        }
    }

    /**
     * The operation a statement is intended for, used to validate that its contents
     * fit that operation via {@link #validateFor(Operation)}.
     */
    public enum Operation {
        /**
         * A read ({@link DatabaseManager#query(String, ObjectStatement)} or
         * {@link DatabaseManager#count(String, ObjectStatement)}).
         */
        QUERY,
        /**
         * An update of one or more rows
         * ({@link DatabaseManager#executeUpdate(String, ObjectStatement)}).
         */
        UPDATE,
        /**
         * An update of a single row targeted by primary key
         * ({@link ObjectManager#updateMetadata}).
         */
        UPDATE_BY_KEY,
        /**
         * An insertion of a new row
         * ({@link DatabaseManager#insert(String, ObjectStatement, ObjectMetadata)}
         * or {@link DatabaseManager#upsert(String, ObjectStatement, ObjectMetadata)}).
         */
        INSERT,
        /**
         * A deletion ({@link DatabaseManager#executeDelete(String, ObjectStatement)}).
         */
        DELETE
    }

    /**
     * Validates that this statement's contents fit the given operation.
     *
     * @param operation  the intended operation
     * @throws IllegalArgumentException if the statement does not fit the operation
     */
    public void validateFor(Operation operation) {
        switch (operation) {
            case QUERY -> {
                if (!assignments.isEmpty()) {
                    throw new IllegalArgumentException("query does not accept assignments");
                }
            }
            case UPDATE -> {
                if (assignments.isEmpty()) {
                    throw new IllegalArgumentException("update must set at least one column");
                }
            }
            case UPDATE_BY_KEY -> {
                if (key == null) {
                    throw new IllegalArgumentException("update by key requires a key");
                }
                if (assignments.isEmpty()) {
                    throw new IllegalArgumentException("update must set at least one column");
                }
                if (!conditions.isEmpty()) {
                    throw new IllegalArgumentException("update by key does not accept conditions");
                }
            }
            case INSERT -> {
                if (key == null || key.isEmpty()) {
                    throw new IllegalArgumentException("insert requires a key");
                }
                if (!conditions.isEmpty()) {
                    throw new IllegalArgumentException("insert does not accept conditions");
                }
                if (!assignments.isEmpty()) {
                    throw new IllegalArgumentException("insert does not accept assignments");
                }
                if (!orderBys.isEmpty() || limit != null || offset != null) {
                    throw new IllegalArgumentException("insert does not accept ordering or paging");
                }
            }
            case DELETE -> {
                if (!assignments.isEmpty()) {
                    throw new IllegalArgumentException("delete does not accept assignments");
                }
            }
        }
    }

    /**
     * A single filter, normalized to a column, an SQL operator and the bound parameters.
     *
     * @param column   the column name
     * @param operator the SQL operator ({@code =}, {@code BETWEEN}, {@code IN}, ...)
     * @param params   the values bound to the statement, in order
     */
    public record Condition(String column, String operator, List<QueryValue> params) {
    }

    /**
     * A single value assignment for an {@code UPDATE}.
     *
     * @param column  the column to set
     * @param value   the value to assign
     */
    public record Assignment(String column, QueryValue value) {
    }

    /**
     * A single sort directive.
     *
     * @param column    the column name
     * @param direction the sort direction
     */
    public record OrderBy(String column, Direction direction) {
    }

    /**
     * @return the primary key value, or {@code null} if not set
     */
    public String key() {
        return key;
    }

    /**
     * @return the values of the additional key columns, or an empty map if none
     */
    public Map<String, String> kv() {
        return kv;
    }

    /**
     * @return the conditions, AND-combined at execution time
     */
    public List<Condition> conditions() {
        return conditions;
    }

    /**
     * @return the value assignments, applied as the {@code SET} clause at execution time
     */
    public List<Assignment> assignments() {
        return assignments;
    }

    /**
     * @return the sort directives, applied in order
     */
    public List<OrderBy> orderBys() {
        return orderBys;
    }

    /**
     * @return the maximum number of rows to return, or {@code null} for no limit
     */
    public Integer limit() {
        return limit;
    }

    /**
     * @return the number of rows to skip, or {@code null} for no offset
     */
    public Integer offset() {
        return offset;
    }

    /**
     * Starts building an {@link ObjectStatement}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing an {@link ObjectStatement}.
     *
     * <p>All conditions are AND-combined. An {@code offset} requires a {@code limit}.</p>
     */
    public static class Builder {
        private String key;
        private Map<String, String> kv = Map.of();
        private final List<Condition> conditions = new ArrayList<>();
        private final List<Assignment> assignments = new ArrayList<>();
        private final List<OrderBy> orderBys = new ArrayList<>();
        private Integer limit;
        private Integer offset;

        /**
         * Sets the primary key value of the object.
         *
         * @param key  the primary key
         * @return this builder
         */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /**
         * Adds a single value for an additional key column.
         *
         * @param column  the additional key column name
         * @param value   the value for that column
         * @return this builder
         */
        public Builder kv(String column, String value) {
            Map<String, String> merged = new HashMap<>(kv);
            merged.put(column, value);
            this.kv = merged;
            return this;
        }

        /**
         * Sets the values for the additional key columns.
         *
         * @param kv  the additional key column values, or {@code null}
         * @return this builder
         */
        public Builder kv(Map<String, String> kv) {
            this.kv = kv == null ? Map.of() : kv;
            return this;
        }

        /**
         * Adds a {@code long} comparison condition ({@code column <op> value}).
         *
         * @param column  the column to filter on
         * @param op      the comparison operator
         * @param value   the value to compare against
         * @return this builder
         */
        public Builder where(String column, Op op, long value) {
            return addCondition(column, op, new QueryValue.LongValue(value));
        }

        /**
         * Adds an {@code int} comparison condition ({@code column <op> value}).
         *
         * @param column  the column to filter on
         * @param op      the comparison operator
         * @param value   the value to compare against
         * @return this builder
         */
        public Builder where(String column, Op op, int value) {
            return addCondition(column, op, new QueryValue.IntValue(value));
        }

        /**
         * Adds a {@code String} comparison condition ({@code column <op> value}).
         *
         * @param column  the column to filter on
         * @param op      the comparison operator
         * @param value   the value to compare against
         * @return this builder
         */
        public Builder where(String column, Op op, String value) {
            return addCondition(column, op, new QueryValue.StringValue(value));
        }

        /**
         * Adds a {@code long} range condition ({@code column BETWEEN low AND high}).
         *
         * @param column  the column to filter on
         * @param low     the inclusive lower bound
         * @param high    the inclusive upper bound
         * @return this builder
         */
        public Builder between(String column, long low, long high) {
            return addCondition(column, "BETWEEN", new QueryValue.LongValue(low), new QueryValue.LongValue(high));
        }

        /**
         * Adds an {@code int} range condition ({@code column BETWEEN low AND high}).
         *
         * @param column  the column to filter on
         * @param low     the inclusive lower bound
         * @param high    the inclusive upper bound
         * @return this builder
         */
        public Builder between(String column, int low, int high) {
            return addCondition(column, "BETWEEN", new QueryValue.IntValue(low), new QueryValue.IntValue(high));
        }

        /**
         * Adds a {@code String} range condition ({@code column BETWEEN low AND high}).
         *
         * @param column  the column to filter on
         * @param low     the inclusive lower bound
         * @param high    the inclusive upper bound
         * @return this builder
         */
        public Builder between(String column, String low, String high) {
            return addCondition(column, "BETWEEN", new QueryValue.StringValue(low), new QueryValue.StringValue(high));
        }

        /**
         * Adds a {@code long} membership condition ({@code column IN (values)}).
         *
         * @param column  the column to filter on
         * @param values  the values to match against; must not be empty
         * @return this builder
         * @throws IllegalArgumentException if {@code values} is empty
         */
        public Builder inLongs(String column, Collection<Long> values) {
            return addIn(column, values.stream().<QueryValue>map(QueryValue.LongValue::new).toList());
        }

        /**
         * Adds an {@code int} membership condition ({@code column IN (values)}).
         *
         * @param column  the column to filter on
         * @param values  the values to match against; must not be empty
         * @return this builder
         * @throws IllegalArgumentException if {@code values} is empty
         */
        public Builder inInts(String column, Collection<Integer> values) {
            return addIn(column, values.stream().<QueryValue>map(QueryValue.IntValue::new).toList());
        }

        /**
         * Adds a {@code String} membership condition ({@code column IN (values)}).
         *
         * @param column  the column to filter on
         * @param values  the values to match against; must not be empty
         * @return this builder
         * @throws IllegalArgumentException if {@code values} is empty
         */
        public Builder inStrings(String column, Collection<String> values) {
            return addIn(column, values.stream().<QueryValue>map(QueryValue.StringValue::new).toList());
        }

        /**
         * Adds a {@code long} assignment ({@code column = value}) for an update.
         *
         * @param column  the column to set
         * @param value   the value to assign
         * @return this builder
         */
        public Builder set(String column, long value) {
            return addAssignment(column, new QueryValue.LongValue(value));
        }

        /**
         * Adds an {@code int} assignment ({@code column = value}) for an update.
         *
         * @param column  the column to set
         * @param value   the value to assign
         * @return this builder
         */
        public Builder set(String column, int value) {
            return addAssignment(column, new QueryValue.IntValue(value));
        }

        /**
         * Adds a {@code String} assignment ({@code column = value}) for an update.
         *
         * @param column  the column to set
         * @param value   the value to assign
         * @return this builder
         */
        public Builder set(String column, String value) {
            return addAssignment(column, new QueryValue.StringValue(value));
        }

        /**
         * Adds a sort directive. Multiple directives are applied in order.
         *
         * @param column    the column to sort by
         * @param direction the sort direction
         * @return this builder
         */
        public Builder orderBy(String column, Direction direction) {
            orderBys.add(new OrderBy(column, direction));
            return this;
        }

        /**
         * Limits the number of returned rows.
         *
         * @param limit  a non-negative maximum number of rows
         * @return this builder
         * @throws IllegalArgumentException if {@code limit} is negative
         */
        public Builder limit(int limit) {
            if (limit < 0) {
                throw new IllegalArgumentException("limit must be non-negative");
            }
            this.limit = limit;
            return this;
        }

        /**
         * Skips the given number of rows before returning results.
         *
         * <p>Requires a {@link #limit(int)} to be set; use {@code offset} with
         * {@code limit} for pagination.</p>
         *
         * @param offset  a non-negative number of rows to skip
         * @return this builder
         * @throws IllegalArgumentException if {@code offset} is negative
         */
        public Builder offset(int offset) {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be non-negative");
            }
            this.offset = offset;
            return this;
        }

        /**
         * Builds the configured {@link ObjectStatement}.
         *
         * <p>An empty statement is valid and matches every row of a namespace.</p>
         *
         * @return a new {@link ObjectStatement}
         * @throws IllegalStateException if an {@code offset} is set without a {@code limit}
         */
        public ObjectStatement build() {
            if (offset != null && limit == null) {
                throw new IllegalStateException("offset requires a limit");
            }
            return new ObjectStatement(key, kv, conditions, assignments, orderBys, limit, offset);
        }

        private Builder addCondition(String column, Op op, QueryValue... params) {
            return addCondition(column, op.toString(), params);
        }

        private Builder addCondition(String column, String operator, QueryValue... params) {
            conditions.add(new Condition(column, operator, List.of(params)));
            return this;
        }

        private Builder addIn(String column, List<QueryValue> params) {
            if (params.isEmpty()) {
                throw new IllegalArgumentException("IN requires at least one value");
            }
            conditions.add(new Condition(column, "IN", params));
            return this;
        }

        private Builder addAssignment(String column, QueryValue value) {
            assignments.add(new Assignment(column, value));
            return this;
        }
    }
}
