package com.potato.object;

import com.potato.database.DatabaseManager;

import java.util.Map;

/**
 * A reference to a stored object produced by a query.
 *
 * <p>Combines the object's key (primary key plus additional key values) with its
 * {@link ObjectMetadata}, so the result of a {@link DatabaseManager#query(String, ObjectStatement)}
 * is fully addressable and can be passed back to {@link ObjectManager#get(String, Map)} or
 * {@link ObjectManager#remove(String, Map)}.</p>
 *
 * @param key          the primary key of the object
 * @param additionKeys the values of the additional key columns, or an empty map if none
 * @param metadata     the object's metadata
 */
public record ObjectReference(String key, Map<String, String> additionKeys, ObjectMetadata metadata) {
}
