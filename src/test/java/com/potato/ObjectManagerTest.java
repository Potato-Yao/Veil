package com.potato;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectManagerTest {
    @TempDir
    static Path tempDir;

    static ObjectManager objectManager;
    static SQLiteDataSource dataSource;

    @BeforeAll
    static void setUp() {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        DatabaseManager databaseManager = DatabaseManager.builder()
                .dataSource(dataSource)
                .keyColumn("user_id", KeyType.TEXT)
                .build();
        VeilConfiguration.init(dataSource, new DiskFileManager(tempDir), null);
        objectManager = ObjectManager.build("objects", databaseManager);
    }

    @Test
    void countingInputStreamCountsBytes() throws IOException {
        byte[] data = "hello world".getBytes();
        try (CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(data))) {
            byte[] buffer = new byte[3];
            int read;
            while ((read = counting.read(buffer)) != -1) {
            }
            assertEquals(data.length, counting.getByteCount());
        }
    }

    @Test
    void putStoresDataAndPersistsMetadata() throws Exception {
        byte[] data = "Veil test payload".getBytes();
        String primaryKey = "obj1";
        String fileName = "photo.png";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream(data), Map.of("user_id", "u7"));

        Path stored = tempDir.resolve("objects/obj1_u7");
        assertTrue(Files.exists(stored));
        assertArrayEquals(data, Files.readAllBytes(stored));

        String md5Hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT file_name, file_extension, file_size, md5, storage_type, storage_location"
                             + " FROM veil_metadata_objects WHERE key = ?")) {
            statement.setString(1, primaryKey);
            var resultSet = statement.executeQuery();
            assertTrue(resultSet.next());
            assertEquals(fileName, resultSet.getString("file_name"));
            assertEquals("png", resultSet.getString("file_extension"));
            assertEquals(data.length, resultSet.getLong("file_size"));
            assertEquals(md5Hex, resultSet.getString("md5"));
            assertEquals("DISK", resultSet.getString("storage_type"));
            assertEquals("objects/obj1_u7", resultSet.getString("storage_location"));
            assertFalse(resultSet.next());
        }
    }

    @Test
    void putThrowsOnUnknownKey() {
        assertThrows(IllegalArgumentException.class, () ->
                objectManager.put("obj2", "a.txt", new ByteArrayInputStream("x".getBytes()),
                        Map.of("unknown_col", "v")));
    }

    @Test
    void overwritePutReplacesExistingRowAndFile() throws Exception {
        byte[] first = "first version".getBytes();
        byte[] second = "second version, longer".getBytes();
        String primaryKey = "obj3";
        String fileName = "doc.txt";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream(first), Map.of("user_id", "u3"));
        objectManager.overwritePut(primaryKey, fileName, new ByteArrayInputStream(second), Map.of("user_id", "u3"));

        Path stored = tempDir.resolve("objects/obj3_u3");
        assertArrayEquals(second, Files.readAllBytes(stored));
        assertEquals(second.length, Files.size(stored));

        String md5Hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(second));

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT file_size, md5 FROM veil_metadata_objects WHERE key = ?")) {
            statement.setString(1, primaryKey);
            var resultSet = statement.executeQuery();
            assertTrue(resultSet.next());
            assertEquals(second.length, resultSet.getLong("file_size"));
            assertEquals(md5Hex, resultSet.getString("md5"));
            assertFalse(resultSet.next());
        }
    }

    @Test
    void overwritePutDeletesOldFileWhenLocationChanges() throws Exception {
        byte[] first = "old file".getBytes();
        byte[] second = "new file".getBytes();
        String primaryKey = "obj4";
        String fileName = "doc.txt";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream(first), Map.of("user_id", "u4"));
        objectManager.overwritePut(primaryKey, fileName, new ByteArrayInputStream(second), Map.of("user_id", "u4b"));

        assertFalse(Files.exists(tempDir.resolve("objects/obj4_u4")));
        assertArrayEquals(second, Files.readAllBytes(tempDir.resolve("objects/obj4_u4b")));
    }
}
