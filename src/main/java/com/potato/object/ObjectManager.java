package com.potato.object;

import com.potato.VeilConfiguration;
import com.potato.database.DatabaseManager;
import com.potato.storage.FileManager;
import com.potato.util.CountingInputStream;

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
import java.util.UUID;

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
        validateLocation(namespace);
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
     * exception. Use {@link #update(ObjectStatement, String, InputStream)} to replace
     * an existing object.</p>
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @param fileName  original file name; its extension is stored as metadata
     * @param source    stream containing the object data
     * @throws IllegalArgumentException if the statement does not fit an insert or
     *                                  contains an unknown key column
     */
    public void put(ObjectStatement statement, String fileName, InputStream source) {
        store(statement, fileName, source, false);
    }

    /**
     * Stores an object, replacing any existing object with the same primary key.
     *
     * <p>When the resolved storage location differs from the previous one, the old file
     * is deleted from storage before the new data is written. Access statistics are
     * preserved across the replacement.</p>
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @param fileName  original file name; its extension is stored as metadata
     * @param source    stream containing the object data
     * @throws IllegalArgumentException if the statement does not fit an insert or
     *                                  contains an unknown key column
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
        ObjectMetadata metadata = databaseManager.getMetadata(namespace, statement.key());
        if (metadata == null) {
            throw new IllegalArgumentException("Object \"" + statement.key() + "\" does not exist in namespace \"" + namespace + "\"");
        }
        databaseManager.updateAccess(namespace, statement.key());
        InputStream stream = mainStorageManager.read(buildLocation(statement.key(), statement.kv()));
        return new ObjectData(metadata, stream);
    }

    /**
     * Removes a stored object, deleting both its metadata row and its file.
     *
     * <p>The metadata row is deleted first; if deleting the file then fails, only a
     * stray file remains, never a metadata row pointing at a missing file.</p>
     *
     * @param statement the statement carrying the primary key (and additional key values)
     * @return {@code true} once the object has been removed
     * @throws IllegalArgumentException if the statement contains an unknown key column,
     *                                  or if no object with the given key exists
     */
    public boolean remove(ObjectStatement statement) {
        validateKv(statement.kv());
        String location = databaseManager.getStorageLocation(namespace, statement.key());
        if (location == null) {
            throw new IllegalArgumentException("Object \"" + statement.key() + "\" does not exist in namespace \"" + namespace + "\"");
        }
        databaseManager.delete(namespace, statement.key());
        mainStorageManager.delete(location);
        return true;
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
        return databaseManager.getStorageLocation(namespace, statement.key()) != null;
    }

    /**
     * Runs a query over the metadata of this namespace.
     *
     * @param statement  the statement to run
     * @return the matching objects as {@link ObjectReference}s, ordered and limited as
     *         specified by {@code statement}
     * @throws IllegalArgumentException if {@code statement} references an unknown column
     */
    public List<ObjectReference> query(ObjectStatement statement) {
        return databaseManager.query(namespace, statement);
    }

    /**
     * Counts the objects in this namespace matching the given statement's conditions.
     *
     * @param statement  the statement whose conditions should be applied
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
        statement.validateFor(ObjectStatement.Operation.UPDATE_BY_KEY);
        if (databaseManager.getStorageLocation(namespace, statement.key()) == null) {
            throw new IllegalArgumentException("Object \"" + statement.key() + "\" does not exist in namespace \"" + namespace + "\"");
        }
        databaseManager.executeUpdate(namespace, statement.key(), statement);
    }

    /**
     * Removes every object matching the given statement, deleting both the metadata
     * rows and their files.
     *
     * <p>The metadata rows are deleted first; if deleting a file then fails, only a
     * stray file remains, never a metadata row pointing at a missing file. Remaining
     * files are still cleaned up before the failure is reported.</p>
     *
     * <p>An empty statement removes every object in the namespace.</p>
     *
     * @param statement  the statement whose conditions should be applied
     * @return the number of removed objects
     * @throws IllegalArgumentException if {@code statement} references an unknown column
     */
    public long removeAll(ObjectStatement statement) {
        List<ObjectReference> references = databaseManager.query(namespace, statement);
        long removed = databaseManager.executeDelete(namespace, statement);
        RuntimeException failure = null;
        for (ObjectReference reference : references) {
            try {
                mainStorageManager.delete(reference.metadata().storageLocation());
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return removed;
    }

    /**
     * Common store routine shared by {@link #put(ObjectStatement, String, InputStream)} and
     * {@link #update(ObjectStatement, String, InputStream)}.
     *
     * <p>The operation is atomic: new content is staged in a temporary file, the
     * metadata row is committed, and only then is the staged file renamed into place.
     * If the metadata write fails, the temporary file is removed and any previous
     * object is left untouched. When {@code overwrite} is {@code true}, an object
     * previously stored at a different location is deleted only after the replacement
     * has been committed.</p>
     *
     * @param statement the statement carrying the primary key and additional key values
     * @param fileName  original file name
     * @param source    stream containing the object data
     * @param overwrite whether to replace an existing object with the same key
     */
    private void store(ObjectStatement statement, String fileName, InputStream source, boolean overwrite) {
        statement.validateFor(ObjectStatement.Operation.INSERT);
        String primaryKey = statement.key();
        Map<String, String> kv = statement.kv();
        validateKv(kv);
        String location = buildLocation(primaryKey, kv);

        String existingLocation = null;
        if (overwrite) {
            existingLocation = databaseManager.getStorageLocation(namespace, primaryKey);
        } else if (databaseManager.getStorageLocation(namespace, primaryKey) != null) {
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
            String extension = extractExtension(fileName);
            String createdAt = Instant.now().toString();
            if (overwrite) {
                databaseManager.upsert(namespace, statement, fileName, extension, size, md5Hex,
                        createdAt, "DISK", location);
            } else {
                databaseManager.insert(namespace, statement, fileName, extension, size, md5Hex,
                        createdAt, "DISK", location);
            }

            mainStorageManager.rename(tempLocation, location);
            if (existingLocation != null && !existingLocation.equals(location)) {
                mainStorageManager.delete(existingLocation);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            mainStorageManager.delete(tempLocation);
            throw e;
        }
    }

    /**
     * Ensures all names in {@code kv} are defined additional key columns.
     *
     * @param kv the additional key values to validate, or {@code null}
     * @throws IllegalArgumentException if a key is not a known additional key column
     */
    private void validateKv(Map<String, String> kv) {
        if (kv == null) {
            return;
        }
        for (String name : kv.keySet()) {
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
     * @param primaryKey the primary key of the object
     * @param kv         the additional key values, or {@code null}
     * @return the storage location relative to the file manager root
     */
    private String buildLocation(String primaryKey, Map<String, String> kv) {
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
