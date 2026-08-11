package com.potato.database;

import com.potato.VeilConfiguration;
import com.potato.object.ObjectManager;
import com.potato.object.ObjectMetadata;
import com.potato.object.ObjectStatement;
import com.potato.storage.DiskFileManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresDatabaseManagerTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("veil_test")
            .withUsername("veil")
            .withPassword("veil");

    @TempDir
    static Path tempDir;

    static final String NAMESPACE = "pg_objects";

    static PGSimpleDataSource dataSource;
    static DatabaseManager databaseManager;
    static ObjectManager objectManager;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        databaseManager = DatabaseManager.builder()
                .dataSource(dataSource)
                .databaseType(DatabaseType.POSTGRES)
                .keyColumn("user_id", KeyType.TEXT)
                .build();
        databaseManager.createTable(NAMESPACE);

        VeilConfiguration.init(dataSource, new DiskFileManager(tempDir), null);
        objectManager = ObjectManager.build(NAMESPACE, databaseManager);
    }

    @Test
    void insertPersistsRowAndLocation() throws Exception {
        String key = "obj1";

        databaseManager.insert(NAMESPACE,
                ObjectStatement.builder().key(key).kv("user_id", "u1").build(),
                new ObjectMetadata("photo.png", "png", 5, "abc123", "2024-01-01T00:00:00Z",
                        null, "DISK", NAMESPACE + "/obj1_u1", 0));

        assertEquals(NAMESPACE + "/obj1_u1",
                databaseManager.getStorageLocation(NAMESPACE, ObjectStatement.builder().key(key).build()));

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT file_name, file_extension, file_size, md5, storage_type, storage_location"
                             + " FROM veil_metadata_" + NAMESPACE + " WHERE key = ?")) {
            statement.setString(1, key);
            var resultSet = statement.executeQuery();
            assertTrue(resultSet.next());
            assertEquals("photo.png", resultSet.getString("file_name"));
            assertEquals("png", resultSet.getString("file_extension"));
            assertEquals(5, resultSet.getLong("file_size"));
            assertEquals("abc123", resultSet.getString("md5"));
            assertEquals("DISK", resultSet.getString("storage_type"));
            assertEquals(NAMESPACE + "/obj1_u1", resultSet.getString("storage_location"));
            assertFalse(resultSet.next());
        }
    }

    @Test
    void upsertInsertsThenReplacesRow() throws Exception {
        String key = "obj2";

        databaseManager.insert(NAMESPACE,
                ObjectStatement.builder().key(key).kv("user_id", "u2").build(),
                new ObjectMetadata("a.txt", "txt", 1, "aaa", "2024-01-01T00:00:00Z",
                        null, "DISK", NAMESPACE + "/obj2_a", 0));
        databaseManager.upsert(NAMESPACE,
                ObjectStatement.builder().key(key).kv("user_id", "u2").build(),
                new ObjectMetadata("b.txt", "txt", 2, "bbb", "2024-01-02T00:00:00Z",
                        null, "DISK", NAMESPACE + "/obj2_b", 0));

        assertEquals(NAMESPACE + "/obj2_b",
                databaseManager.getStorageLocation(NAMESPACE, ObjectStatement.builder().key(key).build()));

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT file_name, file_size, md5 FROM veil_metadata_" + NAMESPACE + " WHERE key = ?")) {
            statement.setString(1, key);
            var resultSet = statement.executeQuery();
            assertTrue(resultSet.next());
            assertEquals("b.txt", resultSet.getString("file_name"));
            assertEquals(2, resultSet.getLong("file_size"));
            assertEquals("bbb", resultSet.getString("md5"));
            assertFalse(resultSet.next());
        }
    }

    @Test
    void insertThrowsOnUnknownKey() {
        assertThrows(IllegalArgumentException.class, () ->
                databaseManager.insert(NAMESPACE,
                        ObjectStatement.builder().key("obj3").kv("unknown_col", "v").build(),
                        new ObjectMetadata("a.txt", "txt", 1, "x", "2024-01-01T00:00:00Z",
                                null, "DISK", "loc", 0)));
    }

    @Test
    void getStorageLocationReturnsNullForMissingKey() {
        assertNull(databaseManager.getStorageLocation(NAMESPACE, ObjectStatement.builder().key("does-not-exist").build()));
    }

    @Test
    void objectManagerStoresFileAndMetadataEndToEnd() throws Exception {
        byte[] data = "hello pg".getBytes();
        String key = "user123";

        objectManager.update(ObjectStatement.builder().key(key).kv("user_id", "u1").build(),
                "avatar.png", new ByteArrayInputStream(data));

        Path stored = tempDir.resolve(NAMESPACE + "/user123_u1");
        assertTrue(Files.exists(stored));
        assertArrayEquals(data, Files.readAllBytes(stored));
        assertEquals(NAMESPACE + "/user123_u1",
                databaseManager.getStorageLocation(NAMESPACE, ObjectStatement.builder().key(key).build()));
    }
}
