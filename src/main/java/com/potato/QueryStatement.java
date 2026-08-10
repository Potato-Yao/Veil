package com.potato;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A database-agnostic query over object metadata.
 *
 * <p>Conditions are AND-combined and may reference the key column, additional key
 * columns, or any metadata column. Column names are validated by the executing
 * {@link DatabaseManager}, which owns the set of known columns.</p>
 *
 * <p>Instances are created with {@link #builder()}.</p>
 */
public class QueryStatement {
    private final List<Condition> conditions;
    private final List<OrderBy> orderBys;
    private final Integer limit;
    private final Integer offset;

    private QueryStatement(List<Condition> conditions, List<OrderBy> orderBys, Integer limit, Integer offset) {
        this.conditions = List.copyOf(conditions);
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
     * A single filter, normalized to a column, an SQL operator and the bound parameters.
     *
     * @param column   the column name
     * @param operator the SQL operator ({@code =}, {@code BETWEEN}, {@code IN}, ...)
     * @param params   the values bound to the statement, in order
     */
    public record Condition(String column, String operator, List<QueryValue> params) {
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
     * @return the conditions, AND-combined at execution time
     */
    public List<Condition> conditions() {
        return conditions;
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
     * Starts building a {@link QueryStatement}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing a {@link QueryStatement}.
     *
     * <p>All conditions are AND-combined. An {@code offset} requires a {@code limit}.</p>
     */
    public static class Builder {
        private final List<Condition> conditions = new ArrayList<>();
        private final List<OrderBy> orderBys = new ArrayList<>();
        private Integer limit;
        private Integer offset;

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
         * Builds the configured {@link QueryStatement}.
         *
         * @return a new {@link QueryStatement}
         * @throws IllegalStateException if an {@code offset} is set without a {@code limit}
         */
        public QueryStatement build() {
            if (offset != null && limit == null) {
                throw new IllegalStateException("offset requires a limit");
            }
            return new QueryStatement(conditions, orderBys, limit, offset);
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
    }
}
