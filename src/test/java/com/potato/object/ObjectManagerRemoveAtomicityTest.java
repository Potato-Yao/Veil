package com.potato.object;

import com.potato.VeilConfiguration;
import com.potato.database.DatabaseManager;
import com.potato.database.KeyType;
import com.potato.storage.DiskFileManager;
import com.potato.storage.FileManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ObjectManager#remove(ObjectStatement)} restores metadata when
 * the file deletion fails after the database row has been removed.
 */
class ObjectManagerRemoveAtomicityTest {
    @TempDir
    static Path tempDir;

    static ObjectManager objectManager;
    static DatabaseManager databaseManager;
    static FailingFileManager fileManager;

    @BeforeAll
    static void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        databaseManager = DatabaseManager.builder()
                .dataSource(dataSource)
                .keyColumn("user_id", KeyType.TEXT)
                .build();
        fileManager = new FailingFileManager(new DiskFileManager(tempDir));
        VeilConfiguration.init(dataSource, fileManager, null);
        objectManager = ObjectManager.build("remove_atomic", databaseManager);
    }

    private static ObjectStatement key(String primaryKey, String userId) {
        return ObjectStatement.builder().key(primaryKey).kv("user_id", userId).build();
    }

    @Test
    void removeRestoresMetadataWhenFileDeleteFails() throws Exception {
        byte[] data = "bye".getBytes();
        String primaryKey = "obj";
        objectManager.put(key(primaryKey, "u1"), "bye.txt", new ByteArrayInputStream(data));
        try (ObjectData ignored = objectManager.get(key(primaryKey, "u1"))) {
        }
        ObjectMetadata before = databaseManager.getMetadata("remove_atomic", key(primaryKey, "u1"));

        fileManager.failOn(before.storageLocation());
        assertThrows(RuntimeException.class, () -> objectManager.remove(key(primaryKey, "u1")));

        ObjectMetadata after = databaseManager.getMetadata("remove_atomic", key(primaryKey, "u1"));
        assertNotNull(after);
        assertEquals(before.fileName(), after.fileName());
        assertEquals(before.storageLocation(), after.storageLocation());
        assertEquals(before.lastAccessedAt(), after.lastAccessedAt());
        assertEquals(before.accessCount(), after.accessCount());
        assertTrue(Files.exists(tempDir.resolve(after.storageLocation())));
    }

    /**
     * {@link FileManager} whose {@link #delete(String)} can be made to fail on demand,
     * delegating everything else to a wrapped {@link DiskFileManager}.
     */
    private static class FailingFileManager extends FileManager {
        private final FileManager delegate;
        private String failOnLocation;

        FailingFileManager(FileManager delegate) {
            this.delegate = delegate;
        }

        void failOn(String location) {
            this.failOnLocation = location;
        }

        @Override
        public FileManager build() {
            return delegate.build();
        }

        @Override
        public void put(String location, InputStream inputStream) {
            delegate.put(location, inputStream);
        }

        @Override
        public OutputStream get(String location) {
            return delegate.get(location);
        }

        @Override
        public InputStream read(String location) {
            return delegate.read(location);
        }

        @Override
        public void delete(String location) {
            if (location.equals(failOnLocation)) {
                throw new UncheckedIOException(new IOException("injected delete failure"));
            }
            delegate.delete(location);
        }

        @Override
        public void rename(String from, String to) {
            delegate.rename(from, to);
        }
    }
}
