package com.potato.object;

import com.potato.database.DatabaseManager;

import java.util.Map;

/**
 * A reference to a stored object produced by a query.
 *
 * <p>Combines the object's key (primary key plus additional key values) with its
 * {@link ObjectMetadata}, so the result of a {@link DatabaseManager#query(String, ObjectStatement)}
 * is fully addressable and can be fed back into an {@link ObjectManager} method.</p>
 *
 * @param key      the primary key of the object
 * @param kv       the values of the additional key columns, or an empty map if none
 * @param metadata the object's metadata
 */
public record ObjectReference(String key, Map<String, String> kv, ObjectMetadata metadata) {
}
