package com.potato;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Manages objects (files) within a single namespace.
 *
 * <p>An {@code ObjectManager} is scoped to one namespace and stores files through a
 * {@link FileManager}, keeping metadata (file name, extension, size, MD5, timestamps,
 * storage location) in a {@link DatabaseManager} table named
 * {@code veil_metadata_<namespace>}.</p>
 *
 * <p>Objects are addressed by a primary key, optionally combined with additional key
 * columns defined when building the {@link DatabaseManager} (for example a
 * {@code user_id}). Instances are created with {@link #build(String, DatabaseManager)}.</p>
 */
public class ObjectManager {
    private static final ArrayList<String> namespaceList = new ArrayList<>();
    private final String namespace;
    private final FileManager mainStorageManager;
    private final FileManager cacheStorageManager;
    private final DatabaseManager databaseManager;

    private ObjectManager(String namespace, FileManager mainStorageManager, FileManager cacheStorageManager, DatabaseManager databaseManager) {
        this.namespace = namespace;
        this.mainStorageManager = mainStorageManager;
        this.cacheStorageManager = cacheStorageManager;
        this.databaseManager = databaseManager;
    }

    /**
     * Creates an {@link ObjectManager} for the given namespace.
     *
     * <p>Registers the namespace and creates its metadata table in the database. Each
     * namespace may be built only once per JVM.</p>
     *
     * @param namespace       the namespace to manage; must be unique
     * @param databaseManager the database manager used for metadata persistence
     * @return a new {@link ObjectManager} bound to the namespace
     * @throws IllegalArgumentException if the namespace is already taken
     */
    public static ObjectManager build(String namespace, DatabaseManager databaseManager) {
        if (checkDuplicateNamespace(namespace)) {
            throw new IllegalArgumentException("The namespace \"" + namespace + "\" is already taken");
        }

        VeilConfiguration configuration = VeilConfiguration.getInstance();
        try {
            databaseManager.createTable(namespace);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        namespaceList.add(namespace);

        return new ObjectManager(namespace, configuration.getMainStorageManager(), configuration.getCacheManager(), databaseManager);
    }

    /**
     * Stores a new object under the given primary key.
     *
     * <p>If an object with the same primary key already exists, the store fails with an
     * exception. Use {@link #overwritePut(String, String, InputStream, Map)} to replace
     * an existing object.</p>
     *
     * @param primaryKey  unique key identifying the object
     * @param fileName    original file name; its extension is stored as metadata
     * @param source      stream containing the object data
     * @param additionKey values for the additional key columns, or {@code null}
     * @throws IllegalArgumentException if {@code additionKey} contains an unknown column
     */
    public void put(String primaryKey, String fileName, InputStream source, Map<String, String> additionKey) {
        store(primaryKey, fileName, source, additionKey, false);
    }

    /**
     * Stores an object, replacing any existing object with the same primary key.
     *
     * <p>When the resolved storage location differs from the previous one, the old file
     * is deleted from storage before the new data is written.</p>
     *
     * @param primaryKey  unique key identifying the object
     * @param fileName    original file name; its extension is stored as metadata
     * @param source      stream containing the object data
     * @param additionKey values for the additional key columns, or {@code null}
     * @throws IllegalArgumentException if {@code additionKey} contains an unknown column
     */
    public void overwritePut(String primaryKey, String fileName, InputStream source, Map<String, String> additionKey) {
        store(primaryKey, fileName, source, additionKey, true);
    }

    /**
     * Retrieves a stored object.
     *
     * <p>Returns the object's metadata together with an {@link InputStream} of its
     * contents. The caller is responsible for closing the returned stream.</p>
     *
     * @param primaryKey  unique key identifying the object
     * @param additionKey values for the additional key columns, or {@code null}
     * @return the object's metadata and content stream
     * @throws IllegalArgumentException if {@code additionKey} contains an unknown column,
     *                                  or if no object with the given key exists
     */
    public ObjectData get(String primaryKey, Map<String, String> additionKey) {
        validateAdditionKeys(additionKey);
        ObjectMetadata metadata = databaseManager.getMetadata(namespace, primaryKey);
        if (metadata == null) {
            throw new IllegalArgumentException("Object \"" + primaryKey + "\" does not exist in namespace \"" + namespace + "\"");
        }
        databaseManager.updateAccess(namespace, primaryKey);
        InputStream stream = mainStorageManager.read(buildLocation(primaryKey, additionKey));
        return new ObjectData(metadata, stream);
    }

    /**
     * Removes a stored object, deleting both its file and its metadata row.
     *
     * @param primaryKey  unique key identifying the object
     * @param additionKey values for the additional key columns, or {@code null}
     * @return {@code true} once the object has been removed
     * @throws IllegalArgumentException if {@code additionKey} contains an unknown column,
     *                                  or if no object with the given key exists
     */
    public boolean remove(String primaryKey, Map<String, String> additionKey) {
        validateAdditionKeys(additionKey);
        String location = databaseManager.getStorageLocation(namespace, primaryKey);
        if (location == null) {
            throw new IllegalArgumentException("Object \"" + primaryKey + "\" does not exist in namespace \"" + namespace + "\"");
        }
        mainStorageManager.delete(location);
        databaseManager.delete(namespace, primaryKey);
        return true;
    }

    /**
     * Checks whether an object with the given key exists.
     *
     * @param primaryKey  unique key identifying the object
     * @param additionKey values for the additional key columns, or {@code null}
     * @return {@code true} if the object exists, {@code false} otherwise
     * @throws IllegalArgumentException if {@code additionKey} contains an unknown column
     */
    public boolean checkExist(String primaryKey, Map<String, String> additionKey) {
        validateAdditionKeys(additionKey);
        return databaseManager.getStorageLocation(namespace, primaryKey) != null;
    }

    /**
     * Runs a query over the metadata of this namespace.
     *
     * @param statement  the query to run
     * @return the matching objects as {@link ObjectReference}s, ordered and limited as
     *         specified by {@code statement}
     * @throws IllegalArgumentException if {@code statement} references an unknown column
     */
    public List<ObjectReference> query(QueryStatement statement) {
        return databaseManager.query(namespace, statement);
    }

    /**
     * Counts the objects in this namespace matching the given query's conditions.
     *
     * @param statement  the query whose conditions should be applied
     * @return the number of matching objects
     * @throws IllegalArgumentException if {@code statement} references an unknown column
     */
    public long count(QueryStatement statement) {
        return databaseManager.count(namespace, statement);
    }

    /**
     * Common store routine shared by {@link #put(String, String, InputStream, Map)} and
     * {@link #overwritePut(String, String, InputStream, Map)}.
     *
     * <p>Validates the additional keys, resolves the storage location, writes the file
     * while computing its size and MD5 digest, then persists the metadata. When
     * {@code overwrite} is {@code true}, an object previously stored at a different
     * location is deleted first and the metadata row is upserted; otherwise a new row is
     * inserted.</p>
     *
     * @param primaryKey  unique key identifying the object
     * @param fileName    original file name
     * @param source      stream containing the object data
     * @param additionKey values for the additional key columns, or {@code null}
     * @param overwrite   whether to replace an existing object with the same key
     */
    private void store(String primaryKey, String fileName, InputStream source, Map<String, String> additionKey, boolean overwrite) {
        validateAdditionKeys(additionKey);
        String location = buildLocation(primaryKey, additionKey);

        if (overwrite) {
            String existing = databaseManager.getStorageLocation(namespace, primaryKey);
            if (existing != null && !existing.equals(location)) {
                mainStorageManager.delete(existing);
            }
        }

        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            CountingInputStream counting = new CountingInputStream(source);
            DigestInputStream digesting = new DigestInputStream(counting, md5);

            mainStorageManager.put(location, digesting);

            long size = counting.getByteCount();
            String md5Hex = HexFormat.of().formatHex(md5.digest());
            String extension = extractExtension(fileName);
            String createdAt = Instant.now().toString();
            if (overwrite) {
                databaseManager.upsert(namespace, primaryKey, additionKey, fileName, extension, size, md5Hex,
                        createdAt, "DISK", location);
            } else {
                databaseManager.insert(namespace, primaryKey, additionKey, fileName, extension, size, md5Hex,
                        createdAt, "DISK", location);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Ensures all names in {@code additionKey} are defined additional key columns.
     *
     * @param additionKey the additional key values to validate, or {@code null}
     * @throws IllegalArgumentException if a key is not a known additional key column
     */
    private void validateAdditionKeys(Map<String, String> additionKey) {
        if (additionKey == null) {
            return;
        }
        for (String name : additionKey.keySet()) {
            if (!databaseManager.getAdditionalKeyColumnNames().contains(name)) {
                throw new IllegalArgumentException("Unknown key column: \"" + name + "\"");
            }
        }
    }

    /**
     * Builds the storage location for an object as {@code namespace/<key>} where
     * {@code <key>} is the primary key followed by {@code _value} for each provided
     * additional key column, in the order the columns are defined in the database.
     *
     * @param primaryKey  the primary key of the object
     * @param additionKey the additional key values, or {@code null}
     * @return the storage location relative to the file manager root
     */
    private String buildLocation(String primaryKey, Map<String, String> additionKey) {
        StringBuilder name = new StringBuilder(primaryKey);
        if (additionKey != null) {
            for (String column : databaseManager.getAdditionalKeyColumnNames()) {
                if (additionKey.containsKey(column)) {
                    name.append('_').append(additionKey.get(column));
                }
            }
        }
        return namespace + "/" + name;
    }

    /**
     * Extracts the extension from a file name, without the leading dot.
     *
     * @param fileName the file name to inspect
     * @return the extension, or an empty string if the name has no dot
     */
    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? "" : fileName.substring(dot + 1);
    }

    /**
     * Checks whether a namespace has already been registered.
     *
     * @param namespace the namespace to check
     * @return {@code true} if the namespace is already taken, {@code false} otherwise
     */
    public static boolean checkDuplicateNamespace(String namespace) {
        return namespaceList.contains(namespace);
    }
}
