package com.potato.object;

import com.potato.VeilConfiguration;
import com.potato.database.DatabaseManager;
import com.potato.storage.FileManager;
import com.potato.util.CountingInputStream;
import com.potato.util.Namespaces;
import com.potato.util.StripedLock;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
 * created with {@link #build(String, DatabaseManager)} or configured with
 * {@link #builder()} (allowed file types, text mode).</p>
 *
 * <p>Mutations of the same object are serialized through a bounded striped lock, so
 * concurrent {@link #put}, {@link #update}, {@link #remove} and {@link #get} calls
 * for one key never interleave; distinct keys run concurrently.</p>
 */
public class ObjectManager {
    private static final Set<String> namespaceList = ConcurrentHashMap.newKeySet();
    private static final Map<String, ObjectManager> instances = new ConcurrentHashMap<>();
    private static final StripedLock keyedLock = new StripedLock();
    private final String namespace;
    private final FileManager mainStorageManager;
    private final FileManager cacheStorageManager;
    private final DatabaseManager databaseManager;
    private final Set<String> allowedExtensions;
    private final boolean textMode;

    private ObjectManager(String namespace, FileManager mainStorageManager, FileManager cacheStorageManager,
                          DatabaseManager databaseManager, Set<String> allowedExtensions, boolean textMode) {
        this.namespace = namespace;
        this.mainStorageManager = mainStorageManager;
        this.cacheStorageManager = cacheStorageManager;
        this.databaseManager = databaseManager;
        this.allowedExtensions = allowedExtensions;
        this.textMode = textMode;
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
        return builder().namespace(namespace).databaseManager(databaseManager).build();
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
     * contents. The caller is responsible for closing the returned stream. The access
     * is recorded in memory and persisted later in a batched flush; the returned
     * metadata therefore reflects the last flushed access statistics, not necessarily
     * this read.</p>
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
            databaseManager.recordAccess(namespace, statement);
            InputStream stream = mainStorageManager.read(metadata.storageLocation());
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
     * <p>The file is committed before the metadata: new content is staged in a temporary
     * file, renamed into its final location, and only then is the metadata row persisted,
     * so a committed row always describes a file that is actually present. If the rename
     * fails, nothing has changed and the previous object (if any) is left untouched. If
     * the metadata write fails after the rename, the newly written file is removed for a
     * new identity, leaving the identity exactly as it was before; for a replacement the
     * previous row and the new bytes transiently disagree, which in-process readers never
     * observe (the striped lock serializes them) and the next store or removal of the
     * identity reconciles. A crash at any point before the row commit leaves at worst an
     * unreferenced file, which is invisible to reads and re-adopted by the next store of
     * the same identity. The storage location is derived entirely from the object's
     * identity, which is immutable, so a replacement always writes to the same location
     * unless the file name's extension changes.</p>
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
        validateExtension(fileName);
        String location = buildLocation(primaryKey, kv);
        String fileLocation = buildFileLocation(location, fileName);
        validateLocation(fileLocation);

        keyedLock.lock(location);
        try {
            if (!overwrite && databaseManager.getStorageLocation(namespace, statement) != null) {
                throw new IllegalArgumentException("Object \"" + primaryKey + "\" already exists in namespace \"" + namespace + "\"");
            }
            String oldLocation = overwrite ? databaseManager.getStorageLocation(namespace, statement) : null;

            String tempLocation = fileLocation + ".tmp-" + UUID.randomUUID();
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                CountingInputStream counting = new CountingInputStream(source);
                DigestInputStream digesting = new DigestInputStream(counting, md5);

                mainStorageManager.put(tempLocation, digesting);

                long size = counting.getByteCount();
                String md5Hex = HexFormat.of().formatHex(md5.digest());

                // Put the file in place first. If the rename fails, nothing has changed:
                // the previous metadata row (if any) and its file are still consistent.
                mainStorageManager.rename(tempLocation, fileLocation);

                // Commit the metadata only after the file exists at its final location,
                // so a committed row always describes a file that is actually present.
                try {
                    ObjectMetadata metadata = new ObjectMetadata(fileName, extractExtension(fileName), size, md5Hex,
                            Instant.now().toString(), null, "DISK", fileLocation, 0);
                    databaseManager.upsertMetadata(namespace, statement, metadata, overwrite);
                } catch (RuntimeException e) {
                    if (!overwrite) {
                        // No row references this file yet; remove the orphan so the
                        // identity is left exactly as it was before this store attempt.
                        try {
                            mainStorageManager.delete(fileLocation);
                        } catch (RuntimeException cleanupFailure) {
                            e.addSuppressed(cleanupFailure);
                        }
                    }
                    // overwrite: the previous row still points at fileLocation, which now
                    // holds the new bytes. In-process readers never observe this state
                    // (the striped lock serializes reads and writes) and the next store
                    // or removal of this identity reconciles it.
                    throw e;
                }

                if (oldLocation != null && !oldLocation.equals(fileLocation)) {
                    mainStorageManager.delete(oldLocation);
                }
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
     * Appends the file name's extension to a storage location.
     *
     * @param location  the extension-less storage location
     * @param fileName  the original file name
     * @return the location with the extension appended, or {@code location} unchanged
     * if the file name has no extension
     */
    private static String buildFileLocation(String location, String fileName) {
        String extension = extractExtension(fileName);
        return extension.isEmpty() ? location : location + "." + extension;
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
     * The file name's extension is appended at store time, so an object stored as
     * {@code photo.png} lands at {@code namespace/<key>_<value>.png}.</p>
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
    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? "" : fileName.substring(dot + 1);
    }

    /**
     * Checks the file name's extension against the configured allowlist.
     *
     * <p>In text mode, or when no extensions are configured, every extension is
     * accepted. Otherwise the extension is compared case-insensitively against the
     * allowed extensions.</p>
     *
     * @param fileName the file name to check
     * @throws IllegalArgumentException if the extension is not allowed
     */
    private void validateExtension(String fileName) {
        if (textMode || allowedExtensions.isEmpty()) {
            return;
        }
        String extension = extractExtension(fileName).toLowerCase(Locale.ROOT);
        if (!allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException("Extension \"" + extension
                    + "\" is not allowed in namespace \"" + namespace + "\"");
        }
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
     * Returns the {@link ObjectManager} built for the given namespace, or
     * {@code null} if no manager has been built for it yet.
     *
     * @param namespace the namespace to look up
     * @return the manager bound to the namespace, or {@code null}
     */
    public static ObjectManager getInstance(String namespace) {
        return instances.get(namespace);
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

    /**
     * Starts building an {@link ObjectManager}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for configuring and constructing an {@link ObjectManager}.
     *
     * <p>The {@link #namespace(String)} and {@link #databaseManager(DatabaseManager)}
     * are required. By default every file type is accepted; restrict accepted types
     * with {@link #allowExtension(String...)}. Text mode ({@link #textMode(boolean)})
     * disables the extension check entirely.</p>
     */
    public static class Builder {
        private String namespace;
        private DatabaseManager databaseManager;
        private final Set<String> allowedExtensions = new HashSet<>();
        private boolean textMode;

        /**
         * Sets the namespace to manage.
         *
         * @param namespace  the namespace to manage; must be unique
         * @return this builder
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the database manager used for metadata persistence.
         *
         * @param databaseManager  the database manager to use
         * @return this builder
         */
        public Builder databaseManager(DatabaseManager databaseManager) {
            this.databaseManager = databaseManager;
            return this;
        }

        /**
         * Allows the given file extensions.
         *
         * <p>Extensions are compared case-insensitively without the leading dot, so
         * entries like {@code "png"} or {@code ".PNG"} both accept {@code photo.png}.
         * When no extensions are configured, every extension is accepted.</p>
         *
         * @param extensions  the extensions to allow
         * @return this builder
         */
        public Builder allowExtension(String... extensions) {
            return allowExtension(List.of(extensions));
        }

        /**
         * Allows the given file extensions.
         *
         * <p>Extensions are compared case-insensitively without the leading dot, so
         * entries like {@code "png"} or {@code ".PNG"} both accept {@code photo.png}.
         * When no extensions are configured, every extension is accepted.</p>
         *
         * @param extensions  the extensions to allow
         * @return this builder
         */
        public Builder allowExtension(Collection<String> extensions) {
            for (String extension : extensions) {
                if (extension == null) {
                    continue;
                }
                allowedExtensions.add(extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
            }
            return this;
        }

        /**
         * Puts the manager into text mode.
         *
         * <p>In text mode the manager manages text and skips the extension check, even
         * if allowed extensions have been configured.</p>
         *
         * @param textMode  whether to manage text
         * @return this builder
         */
        public Builder textMode(boolean textMode) {
            this.textMode = textMode;
            return this;
        }

        /**
         * Builds the configured {@link ObjectManager}.
         *
         * <p>Registers the namespace and creates its metadata table in the database.
         * Each namespace may be built only once per JVM.</p>
         *
         * @return a new {@link ObjectManager} bound to the namespace
         * @throws IllegalStateException if no namespace or database manager is set
         * @throws IllegalArgumentException if the namespace is already taken
         */
        public ObjectManager build() {
            if (namespace == null) {
                throw new IllegalStateException("ObjectManager requires a namespace");
            }
            if (databaseManager == null) {
                throw new IllegalStateException("ObjectManager requires a database manager");
            }

            Namespaces.requireValid(namespace);
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

            Set<String> extensions = Set.copyOf(allowedExtensions);
            ObjectManager manager = new ObjectManager(namespace, configuration.getMainStorageManager(),
                    configuration.getCacheManager(), databaseManager, extensions, textMode);
            instances.put(namespace, manager);
            return manager;
        }
    }
}
