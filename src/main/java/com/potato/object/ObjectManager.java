package com.potato.object;

import com.potato.VeilConfiguration;
import com.potato.database.DatabaseManager;
import com.potato.storage.FileManager;
import com.potato.util.CountingInputStream;
import com.potato.util.StripedLock;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages objects (files) within a single namespace.
 *
 * <p>An {@code ObjectManager} is scoped to one namespace and stores files through a
 * {@link FileManager}, keeping metadata (file name, extension, size, MD5, timestamps,
 * storage location) in a {@link DatabaseManager} table named
 * {@code veil_metadata_<namespace>}.</p>
 *
 * <p>Objects are addressed by an immutable identity: the primary key together with the
 * values of the additional key columns defined when building the
 * {@link DatabaseManager} (for example a {@code user_id}). Two objects with the same
 * primary key but different additional key values are distinct objects. Instances are
 * created with {@link #build(String, DatabaseManager)}.</p>
 *
 * <p>Mutations of the same object are serialized through a bounded striped lock, so
 * concurrent {@link #put}, {@link #update}, {@link #remove} and {@link #get} calls
 * for one key never interleave; distinct keys run concurrently.</p>
 */
public class ObjectManager {
    private static final Set<String> namespaceList = ConcurrentHashMap.newKeySet();
    private static final StripedLock keyedLock = new StripedLock();
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
        validateLocation(namespace);
        if (!namespaceList.add(namespace)) {
            throw new IllegalArgumentException("The namespace \"" + namespace + "\" is already taken");
        }

        VeilConfiguration configuration = VeilConfiguration.getInstance();
        try {
            databaseManager.createTable(namespace);
        } catch (SQLException e) {
            namespaceList.remove(namespace);
            throw new RuntimeException(e);
        }

        return new ObjectManager(namespace, configuration.getMainStorageManager(), configuration.getCacheManager(), databaseManager);
    }

    /**
     * Stores a new object under the given identity.
     *
     * <p>If an object with the same identity (primary key and additional key values)
     * already exists, the store fails with an exception. Use
     * {@link #update(ObjectStatement, String, InputStream)} to replace an existing
     * object.</p>
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @param fileName  original file name; its extension is stored as metadata
     * @param source    stream containing the object data
     * @throws IllegalArgumentException if the statement does not fit an insert, does not
     *                                  carry the full identity, or contains an unknown
     *                                  key column
     */
    public void put(ObjectStatement statement, String fileName, InputStream source) {
        store(statement, fileName, source, false);
    }

    /**
     * Stores an object, replacing any existing object with the same identity.
     *
     * <p>The identity of an object is its primary key together with all additional key
     * column values and is immutable: {@code update} replaces the content and metadata
     * of the object with that identity. If no object with the given identity exists, a
     * new object is created instead. Access statistics are preserved across the
     * replacement.</p>
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @param fileName  original file name; its extension is stored as metadata
     * @param source    stream containing the object data
     * @throws IllegalArgumentException if the statement does not carry the full identity
     *                                  or contains an unknown key column
     */
    public void update(ObjectStatement statement, String fileName, InputStream source) {
        store(statement, fileName, source, true);
    }

    /**
     * Retrieves a stored object.
     *
     * <p>Returns the object's metadata together with an {@link InputStream} of its
     * contents. The caller is responsible for closing the returned stream.</p>
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @return the object's metadata and content stream
     * @throws IllegalArgumentException if the statement contains an unknown key column,
     *                                  or if no object with the given key exists
     */
    public ObjectData get(ObjectStatement statement) {
        validateKv(statement.kv());
        String location = buildLocation(statement.key(), statement.kv());
        keyedLock.lock(location);
        try {
            ObjectMetadata metadata = databaseManager.getMetadata(namespace, statement);
            if (metadata == null) {
                throw new IllegalArgumentException("Object \"" + statement.key() + "\" does not exist in namespace \"" + namespace + "\"");
            }
            databaseManager.updateAccess(namespace, statement);
            InputStream stream = mainStorageManager.read(location);
            return new ObjectData(metadata, stream);
        } finally {
            keyedLock.unlock(location);
        }
    }

    /**
     * Removes a stored object, deleting both its metadata row and its file.
     *
     * <p>The metadata row is deleted first; if deleting the file then fails, the
     * metadata row is restored so that the object remains addressable and consistent.</p>
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @return {@code true} once the object has been removed
     * @throws IllegalArgumentException if the statement contains an unknown key column,
     *                                  or if no object with the given key exists
     */
    public boolean remove(ObjectStatement statement) {
        validateKv(statement.kv());
        String location = buildLocation(statement.key(), statement.kv());
        keyedLock.lock(location);
        try {
            ObjectMetadata metadata = databaseManager.getMetadata(namespace, statement);
            if (metadata == null) {
                throw new IllegalArgumentException("Object \"" + statement.key() + "\" does not exist in namespace \"" + namespace + "\"");
            }
            databaseManager.delete(namespace, statement);
            try {
                mainStorageManager.delete(metadata.storageLocation());
            } catch (RuntimeException e) {
                try {
                    databaseManager.upsertMetadata(namespace, keyStatement(statement), metadata, false);
                } catch (RuntimeException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
                throw e;
            }
            return true;
        } finally {
            keyedLock.unlock(location);
        }
    }

    /**
     * Checks whether an object with the given key exists.
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @return {@code true} if the object exists, {@code false} otherwise
     * @throws IllegalArgumentException if the statement contains an unknown key column
     */
    public boolean checkExist(ObjectStatement statement) {
        validateKv(statement.kv());
        return databaseManager.getStorageLocation(namespace, statement) != null;
    }

    /**
     * Runs a query over the metadata of this namespace.
     *
     * @param statement the statement to run
     * @return the matching objects as {@link ObjectReference}s, ordered and limited as
     * specified by {@code statement}
     * @throws IllegalArgumentException if {@code statement} references an unknown column
     */
    public List<ObjectReference> query(ObjectStatement statement) {
        return databaseManager.query(namespace, statement);
    }

    /**
     * Counts the objects in this namespace matching the given statement's conditions.
     *
     * @param statement the statement whose conditions should be applied
     * @return the number of matching objects
     * @throws IllegalArgumentException if {@code statement} references an unknown column
     */
    public long count(ObjectStatement statement) {
        return databaseManager.count(namespace, statement);
    }

    /**
     * Partially updates the metadata of a stored object without touching its content.
     *
     * <p>The statement must carry a primary key, at least one {@code set(...)} assignment
     * and no conditions: the target object is identified by its primary key (and
     * additional keys). Only the assigned metadata columns are changed; content, access
     * statistics, and creation time are left untouched.</p>
     *
     * @param statement the statement carrying the primary key and the assignments to apply
     * @throws IllegalArgumentException if the statement contains an unknown key column,
     *                                  if the object does not exist, or if the statement
     *                                  has no key, no assignments or carries conditions
     */
    public void updateMetadata(ObjectStatement statement) {
        validateKv(statement.kv());
        String location = buildLocation(statement.key(), statement.kv());
        keyedLock.lock(location);
        try {
            if (databaseManager.getStorageLocation(namespace, statement) == null) {
                throw new IllegalArgumentException("Object \"" + statement.key() + "\" does not exist in namespace \"" + namespace + "\"");
            }
            databaseManager.executeUpdate(namespace, statement);
        } finally {
            keyedLock.unlock(location);
        }
    }

    // TODO: bulk deletion via bounded cursor-based batches: page identities and storage
    // locations ordered by the identity keyset, delete each file then its metadata row
    // (restoring on failure) per batch, until none remain. Reject limit/offset and
    // stream the page so memory stays bounded. (issues.md #3)

    /**
     * Common store routine shared by {@link #put(ObjectStatement, String, InputStream)} and
     * {@link #update(ObjectStatement, String, InputStream)}.
     *
     * <p>The operation is atomic: new content is staged in a temporary file, the
     * metadata row is committed, and only then is the staged file renamed into place.
     * If the metadata write fails, the temporary file is removed and any previous
     * object is left untouched. The storage location is derived entirely from the
     * object's identity, which is immutable, so a replacement always writes to the
     * same location.</p>
     *
     * @param statement the statement carrying the primary key and additional key values
     * @param fileName  original file name
     * @param source    stream containing the object data
     * @param overwrite whether to replace an existing object with the same identity
     */
    private void store(ObjectStatement statement, String fileName, InputStream source, boolean overwrite) {
        statement.validateFor(ObjectStatement.Operation.INSERT);
        String primaryKey = statement.key();
        Map<String, String> kv = statement.kv();
        validateKv(kv);
        String location = buildLocation(primaryKey, kv);

        keyedLock.lock(location);
        try {
            if (!overwrite && databaseManager.getStorageLocation(namespace, statement) != null) {
                throw new IllegalArgumentException("Object \"" + primaryKey + "\" already exists in namespace \"" + namespace + "\"");
            }

            String tempLocation = location + ".tmp-" + UUID.randomUUID();
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                CountingInputStream counting = new CountingInputStream(source);
                DigestInputStream digesting = new DigestInputStream(counting, md5);

                mainStorageManager.put(tempLocation, digesting);

                long size = counting.getByteCount();
                String md5Hex = HexFormat.of().formatHex(md5.digest());
                ObjectMetadata metadata = new ObjectMetadata(fileName, extractExtension(fileName), size, md5Hex,
                        Instant.now().toString(), null, "DISK", location, 0);
                databaseManager.upsertMetadata(namespace, statement, metadata, overwrite);

                mainStorageManager.rename(tempLocation, location);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            } catch (RuntimeException e) {
                mainStorageManager.delete(tempLocation);
                throw e;
            }
        } finally {
            keyedLock.unlock(location);
        }
    }

    /**
     * Builds a key-and-kv-only statement from the given statement.
     *
     * <p>Used to restore an object independently of any conditions, assignments or
     * ordering the original statement may carry.</p>
     *
     * @param statement the statement whose key and additional key values should be kept
     * @return a new statement carrying only the key and additional key values
     */
    private static ObjectStatement keyStatement(ObjectStatement statement) {
        return ObjectStatement.builder().key(statement.key()).kv(statement.kv()).build();
    }

    /**
     * Ensures {@code kv} contains exactly the additional key column values: every name
     * must be a defined additional key column, and every additional key column must be
     * present because it is part of the object's immutable identity.
     *
     * @param kv the additional key values to validate, or {@code null}
     * @throws IllegalArgumentException if a key is not a known additional key column, or
     *                                  a defined additional key column is missing
     */
    private void validateKv(Map<String, String> kv) {
        if (kv == null) {
            throw new IllegalArgumentException("Statement must carry the additional key column values");
        }
        for (String name : kv.keySet()) {
            if (!databaseManager.getAdditionalKeyColumnNames().contains(name)) {
                throw new IllegalArgumentException("Unknown key column: \"" + name + "\"");
            }
        }
        for (String column : databaseManager.getAdditionalKeyColumnNames()) {
            if (!kv.containsKey(column)) {
                throw new IllegalArgumentException("Statement must carry a value for key column \"" + column + "\"");
            }
        }
    }

    /**
     * Builds the storage location for an object as {@code namespace/<key>} where
     * {@code <key>} is the primary key followed by {@code _value} for each provided
     * additional key column, in the order the columns are defined in the database.
     *
     * @param primaryKey the primary key of the object
     * @param kv         the additional key values, or {@code null}
     * @return the storage location relative to the file manager root
     */
    private String buildLocation(String primaryKey, Map<String, String> kv) {
        if (primaryKey == null || primaryKey.isEmpty()) {
            throw new IllegalArgumentException("Statement must carry a key");
        }
        StringBuilder name = new StringBuilder(primaryKey);
        if (kv != null) {
            for (String column : databaseManager.getAdditionalKeyColumnNames()) {
                if (kv.containsKey(column)) {
                    name.append('_').append(kv.get(column));
                }
            }
        }
        String location = namespace + "/" + name;
        validateLocation(location);
        return location;
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

    /**
     * Validates that a location stays within the storage scope.
     *
     * <p>Rejects absolute paths, drive prefixes, backslashes, parent-directory
     * traversal, and segments starting with {@code ~} or {@code -} so that no file
     * operation can reach outside the configured root.</p>
     *
     * @param location the relative location of the object
     * @throws IllegalArgumentException if the location escapes the storage scope
     */
    private static void validateLocation(String location) {
        if (location == null || location.isEmpty()) {
            throw new IllegalArgumentException("Location must not be empty");
        }
        if (location.startsWith("/")) {
            throw new IllegalArgumentException("Location must be relative: \"" + location + "\"");
        }
        if (location.contains("\\")) {
            throw new IllegalArgumentException("Location must not contain backslashes: \"" + location + "\"");
        }
        for (String segment : location.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid path segment in location: \"" + location + "\"");
            }
            if (segment.startsWith("~") || segment.startsWith("-") || segment.matches("[A-Za-z]:.*")) {
                throw new IllegalArgumentException("Invalid path segment in location: \"" + location + "\"");
            }
        }
    }
}
