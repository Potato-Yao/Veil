package com.potato.object;

import com.potato.VeilConfiguration;
import com.potato.database.DatabaseManager;
import com.potato.database.KeyType;
import com.potato.storage.DiskFileManager;
import com.potato.util.CountingInputStream;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectManagerTest {
    @TempDir
    static Path tempDir;

    static ObjectManager objectManager;
    static DatabaseManager databaseManager;
    static SQLiteDataSource dataSource;

    @BeforeAll
    static void setUp() {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        databaseManager = DatabaseManager.builder()
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
    void updateReplacesExistingRowAndFile() throws Exception {
        byte[] first = "first version".getBytes();
        byte[] second = "second version, longer".getBytes();
        String primaryKey = "obj3";
        String fileName = "doc.txt";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream(first), Map.of("user_id", "u3"));
        objectManager.update(primaryKey, fileName, new ByteArrayInputStream(second), Map.of("user_id", "u3"));

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
    void updateDeletesOldFileWhenLocationChanges() throws Exception {
        byte[] first = "old file".getBytes();
        byte[] second = "new file".getBytes();
        String primaryKey = "obj4";
        String fileName = "doc.txt";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream(first), Map.of("user_id", "u4"));
        objectManager.update(primaryKey, fileName, new ByteArrayInputStream(second), Map.of("user_id", "u4b"));

        assertFalse(Files.exists(tempDir.resolve("objects/obj4_u4")));
        assertArrayEquals(second, Files.readAllBytes(tempDir.resolve("objects/obj4_u4b")));
    }

    @Test
    void getReturnsMetadataAndContent() throws Exception {
        byte[] data = "get me".getBytes();
        String primaryKey = "obj5";
        String fileName = "get.txt";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream(data), Map.of("user_id", "u5"));

        try (ObjectData object = objectManager.get(primaryKey, Map.of("user_id", "u5"))) {
            assertEquals(fileName, object.metadata().fileName());
            assertEquals("txt", object.metadata().fileExtension());
            assertEquals(data.length, object.metadata().fileSize());
            assertEquals("DISK", object.metadata().storageType());
            assertEquals("objects/obj5_u5", object.metadata().storageLocation());
            assertArrayEquals(data, object.stream().readAllBytes());
        }
    }

    @Test
    void getThrowsWhenObjectDoesNotExist() {
        assertThrows(IllegalArgumentException.class, () ->
                objectManager.get("missing", Map.of("user_id", "uX")));
    }

    @Test
    void removeDeletesFileAndMetadata() throws Exception {
        byte[] data = "bye".getBytes();
        String primaryKey = "obj6";
        String fileName = "bye.txt";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream(data), Map.of("user_id", "u6"));
        assertTrue(Files.exists(tempDir.resolve("objects/obj6_u6")));

        assertTrue(objectManager.remove(primaryKey, Map.of("user_id", "u6")));
        assertFalse(Files.exists(tempDir.resolve("objects/obj6_u6")));

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT 1 FROM veil_metadata_objects WHERE key = ?")) {
            statement.setString(1, primaryKey);
            var resultSet = statement.executeQuery();
            assertFalse(resultSet.next());
        }
    }

    @Test
    void removeThrowsWhenObjectDoesNotExist() {
        assertThrows(IllegalArgumentException.class, () ->
                objectManager.remove("missing", Map.of("user_id", "uX")));
    }

    @Test
    void checkExistReflectsPutAndRemove() throws Exception {
        String primaryKey = "obj7";

        assertFalse(objectManager.checkExist(primaryKey, Map.of("user_id", "u7")));
        objectManager.put(primaryKey, "check.txt", new ByteArrayInputStream("check".getBytes()), Map.of("user_id", "u7"));
        assertTrue(objectManager.checkExist(primaryKey, Map.of("user_id", "u7")));
        objectManager.remove(primaryKey, Map.of("user_id", "u7"));
        assertFalse(objectManager.checkExist(primaryKey, Map.of("user_id", "u7")));
    }

    @Test
    void getUpdatesAccessCountAndLastAccessedAt() throws Exception {
        String primaryKey = "obj8";

        objectManager.put(primaryKey, "track.txt", new ByteArrayInputStream("track me".getBytes()), Map.of("user_id", "u8"));
        try (ObjectData ignored = objectManager.get(primaryKey, Map.of("user_id", "u8"))) {
        }
        try (ObjectData ignored = objectManager.get(primaryKey, Map.of("user_id", "u8"))) {
        }

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT access_count, last_accessed_at FROM veil_metadata_objects WHERE key = ?")) {
            statement.setString(1, primaryKey);
            var resultSet = statement.executeQuery();
            assertTrue(resultSet.next());
            assertEquals(2, resultSet.getLong("access_count"));
            assertNotNull(resultSet.getString("last_accessed_at"));
        }
    }

    @Test
    void getReturnsMetadataWithAccessCount() throws Exception {
        String primaryKey = "obj10";

        objectManager.put(primaryKey, "count.txt", new ByteArrayInputStream("count".getBytes()), Map.of("user_id", "u10"));
        try (ObjectData object = objectManager.get(primaryKey, Map.of("user_id", "u10"))) {
            assertEquals(0, object.metadata().accessCount());
        }
        try (ObjectData object = objectManager.get(primaryKey, Map.of("user_id", "u10"))) {
            assertEquals(1, object.metadata().accessCount());
        }
    }

    @Test
    void updatePreservesAccessState() throws Exception {
        String primaryKey = "obj9";
        String fileName = "doc.txt";

        objectManager.put(primaryKey, fileName, new ByteArrayInputStream("original".getBytes()), Map.of("user_id", "u9"));
        try (ObjectData ignored = objectManager.get(primaryKey, Map.of("user_id", "u9"))) {
        }
        objectManager.update(primaryKey, fileName, new ByteArrayInputStream("new version".getBytes()), Map.of("user_id", "u9"));

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT access_count FROM veil_metadata_objects WHERE key = ?")) {
            statement.setString(1, primaryKey);
            var resultSet = statement.executeQuery();
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getLong("access_count"));
            assertFalse(resultSet.next());
        }
    }

    @Test
    void queryEndToEnd() throws Exception {
        ObjectManager queryManager = ObjectManager.build("query", databaseManager);
        queryManager.put("qA", "a.png", new ByteArrayInputStream("hello".getBytes()), Map.of("user_id", "uA"));
        queryManager.put("qB", "b.jpg", new ByteArrayInputStream("abcdefghijklmno".getBytes()), Map.of("user_id", "uB"));
        queryManager.put("qC", "c.png", new ByteArrayInputStream("12345678".getBytes()), Map.of("user_id", "uC"));

        List<ObjectReference> png = databaseManager.query("query",
                ObjectStatement.builder().where("file_extension", ObjectStatement.Op.EQ, "png").build());
        assertEquals(List.of("qA", "qC"), png.stream().map(ObjectReference::key).toList());

        List<ObjectReference> like = databaseManager.query("query",
                ObjectStatement.builder().where("file_extension", ObjectStatement.Op.LIKE, "p%").build());
        assertEquals(List.of("qA", "qC"), like.stream().map(ObjectReference::key).toList());

        List<ObjectReference> range = databaseManager.query("query",
                ObjectStatement.builder().between("file_size", 6L, 16L).build());
        assertEquals(List.of("qB", "qC"), range.stream().map(ObjectReference::key).toList());

        List<ObjectReference> ordered = databaseManager.query("query",
                ObjectStatement.builder().orderBy("file_size", ObjectStatement.Direction.DESC).build());
        assertEquals(List.of("qB", "qC", "qA"), ordered.stream().map(ObjectReference::key).toList());

        List<ObjectReference> page = databaseManager.query("query",
                ObjectStatement.builder().orderBy("file_size", ObjectStatement.Direction.ASC).limit(2).offset(1).build());
        assertEquals(List.of("qC", "qB"), page.stream().map(ObjectReference::key).toList());

        List<ObjectReference> byUser = databaseManager.query("query",
                ObjectStatement.builder().where("user_id", ObjectStatement.Op.EQ, "uB").build());
        assertEquals(List.of("qB"), byUser.stream().map(ObjectReference::key).toList());

        assertEquals(3, databaseManager.count("query", ObjectStatement.builder().build()));
        assertEquals(2, databaseManager.count("query",
                ObjectStatement.builder().between("file_size", 6L, 16L).build()));

        assertThrows(IllegalArgumentException.class, () -> databaseManager.query("query",
                ObjectStatement.builder().where("unknown_column", ObjectStatement.Op.EQ, "x").build()));
    }

    @Test
    void queryResultIsAddressable() throws Exception {
        ObjectManager queryManager = ObjectManager.build("query_addressable", databaseManager);
        queryManager.put("addr1", "doc.txt", new ByteArrayInputStream("addressable".getBytes()), Map.of("user_id", "uX"));

        ObjectReference reference = databaseManager.query("query_addressable", ObjectStatement.builder().build()).get(0);
        assertEquals("addr1", reference.key());
        assertEquals(Map.of("user_id", "uX"), reference.additionKeys());
        assertEquals("doc.txt", reference.metadata().fileName());

        try (ObjectData object = queryManager.get(reference.key(), reference.additionKeys())) {
            assertArrayEquals("addressable".getBytes(), object.stream().readAllBytes());
        }
    }

    @Test
    void queryStatementRejectsInvalidConfigurations() {
        assertThrows(IllegalStateException.class, () ->
                ObjectStatement.builder().offset(1).build());
        assertThrows(IllegalArgumentException.class, () ->
                ObjectStatement.builder().inLongs("file_size", List.<Long>of()).build());
        assertThrows(IllegalArgumentException.class, () ->
                ObjectStatement.builder().limit(-1).build());
    }

    @Test
    void queryWithIntValuesWorks() throws Exception {
        ObjectManager queryManager = ObjectManager.build("query_ints", databaseManager);
        queryManager.put("iA", "a.txt", new ByteArrayInputStream("12345".getBytes()), Map.of("user_id", "uI"));
        queryManager.put("iB", "b.txt", new ByteArrayInputStream("123456789".getBytes()), Map.of("user_id", "uI"));

        List<ObjectReference> results = databaseManager.query("query_ints",
                ObjectStatement.builder().where("file_size", ObjectStatement.Op.GT, 5).build());
        assertEquals(List.of("iB"), results.stream().map(ObjectReference::key).toList());
    }

    @Test
    void updateMetadataPartiallyUpdatesMetadata() throws Exception {
        ObjectManager metaManager = ObjectManager.build("meta", databaseManager);
        byte[] data = "meta payload".getBytes();
        String primaryKey = "meta1";
        metaManager.put(primaryKey, "photo.png", new ByteArrayInputStream(data), Map.of("user_id", "uM"));

        metaManager.updateMetadata(primaryKey, Map.of("user_id", "uM"),
                ObjectStatement.builder().set("file_name", "renamed.png").build());

        ObjectMetadata afterName = databaseManager.getMetadata("meta", primaryKey);
        assertEquals("renamed.png", afterName.fileName());
        assertEquals("png", afterName.fileExtension());
        assertEquals(data.length, afterName.fileSize());
        assertEquals("meta/meta1_uM", afterName.storageLocation());
        assertEquals(0, afterName.accessCount());

        metaManager.updateMetadata(primaryKey, Map.of("user_id", "uM"),
                ObjectStatement.builder().set("file_extension", "jpg").build());
        ObjectMetadata afterExtension = databaseManager.getMetadata("meta", primaryKey);
        assertEquals("renamed.png", afterExtension.fileName());
        assertEquals("jpg", afterExtension.fileExtension());

        metaManager.updateMetadata(primaryKey, Map.of("user_id", "uM"),
                ObjectStatement.builder().set("file_name", "final.jpg").build());
        ObjectMetadata afterBoth = databaseManager.getMetadata("meta", primaryKey);
        assertEquals("final.jpg", afterBoth.fileName());
        assertEquals("jpg", afterBoth.fileExtension());
    }

    @Test
    void updateMetadataThrowsWhenObjectDoesNotExist() {
        ObjectManager metaManager = ObjectManager.build("meta_missing", databaseManager);
        assertThrows(IllegalArgumentException.class, () ->
                metaManager.updateMetadata("nope", Map.of("user_id", "uX"),
                        ObjectStatement.builder().set("file_name", "x.txt").build()));
    }

    @Test
    void updateMetadataRejectsEmptyUpdateAndConditions() throws Exception {
        ObjectManager metaManager = ObjectManager.build("meta_invalid", databaseManager);
        metaManager.put("m1", "a.txt", new ByteArrayInputStream("a".getBytes()), Map.of("user_id", "u1"));
        assertThrows(IllegalArgumentException.class, () ->
                metaManager.updateMetadata("m1", Map.of("user_id", "u1"), ObjectStatement.builder().build()));
        assertThrows(IllegalArgumentException.class, () ->
                metaManager.updateMetadata("m1", Map.of("user_id", "u1"),
                        ObjectStatement.builder().set("file_name", "b.txt")
                                .where("file_extension", ObjectStatement.Op.EQ, "txt").build()));
    }

    @Test
    void executeUpdateBatchUpdatesMatchingRows() throws Exception {
        ObjectManager batchManager = ObjectManager.build("batch", databaseManager);
        batchManager.put("b1", "small.txt", new ByteArrayInputStream("123".getBytes()), Map.of("user_id", "uB"));
        batchManager.put("b2", "big.txt", new ByteArrayInputStream("12345678901234567890".getBytes()), Map.of("user_id", "uB"));

        long updated = databaseManager.executeUpdate("batch",
                ObjectStatement.builder().set("file_name", "renamed.txt")
                        .where("file_size", ObjectStatement.Op.GTE, 10).build());
        assertEquals(1, updated);

        assertEquals("small.txt", databaseManager.getMetadata("batch", "b1").fileName());
        assertEquals("renamed.txt", databaseManager.getMetadata("batch", "b2").fileName());
    }

    @Test
    void executeUpdateRejectsNonUpdatableColumn() {
        assertThrows(IllegalArgumentException.class, () ->
                databaseManager.executeUpdate("batch",
                        ObjectStatement.builder().set("key", "x").build()));
    }

    @Test
    void removeAllDeletesMatchingObjectsAndFiles() throws Exception {
        ObjectManager removeManager = ObjectManager.build("remove_all", databaseManager);
        removeManager.put("r1", "a.png", new ByteArrayInputStream("one".getBytes()), Map.of("user_id", "uR"));
        removeManager.put("r2", "b.txt", new ByteArrayInputStream("two".getBytes()), Map.of("user_id", "uR"));
        removeManager.put("r3", "c.png", new ByteArrayInputStream("three".getBytes()), Map.of("user_id", "uR"));

        long removed = removeManager.removeAll(ObjectStatement.builder()
                .where("file_extension", ObjectStatement.Op.EQ, "png").build());
        assertEquals(2, removed);

        assertFalse(Files.exists(tempDir.resolve("remove_all/r1_uR")));
        assertFalse(Files.exists(tempDir.resolve("remove_all/r3_uR")));
        assertTrue(Files.exists(tempDir.resolve("remove_all/r2_uR")));
        assertFalse(removeManager.checkExist("r1", Map.of("user_id", "uR")));
        assertTrue(removeManager.checkExist("r2", Map.of("user_id", "uR")));
        assertEquals(1, databaseManager.count("remove_all", ObjectStatement.builder().build()));
    }

    @Test
    void queryAndCountRejectAssignments() throws Exception {
        ObjectManager manager = ObjectManager.build("reject_query", databaseManager);
        manager.put("k1", "a.txt", new ByteArrayInputStream("a".getBytes()), Map.of("user_id", "u1"));

        ObjectStatement withAssignment = ObjectStatement.builder().set("file_name", "b.txt").build();
        assertThrows(IllegalArgumentException.class, () -> databaseManager.query("reject_query", withAssignment));
        assertThrows(IllegalArgumentException.class, () -> databaseManager.count("reject_query", withAssignment));
        assertThrows(IllegalArgumentException.class, () -> manager.query(withAssignment));
    }

    @Test
    void executeDeleteRejectsAssignments() throws Exception {
        ObjectStatement withAssignment = ObjectStatement.builder().set("file_name", "b.txt").build();
        assertThrows(IllegalArgumentException.class, () -> databaseManager.executeDelete("reject_delete", withAssignment));
    }

    @Test
    void executeUpdateRejectsEmptyAssignments() {
        assertThrows(IllegalArgumentException.class, () ->
                databaseManager.executeUpdate("batch", ObjectStatement.builder().build()));
        assertThrows(IllegalArgumentException.class, () ->
                databaseManager.executeUpdate("batch", "k1", ObjectStatement.builder().build()));
    }

    @Test
    void statementValidateForEnforcesOperationFit() {
        ObjectStatement withAssignment = ObjectStatement.builder().set("file_name", "x.txt").build();
        ObjectStatement withCondition = ObjectStatement.builder()
                .where("file_extension", ObjectStatement.Op.EQ, "png").build();
        ObjectStatement empty = ObjectStatement.builder().build();

        assertThrows(IllegalArgumentException.class, () -> withAssignment.validateFor(ObjectStatement.Operation.QUERY));
        assertThrows(IllegalArgumentException.class, () -> empty.validateFor(ObjectStatement.Operation.UPDATE));
        assertThrows(IllegalArgumentException.class, () -> empty.validateFor(ObjectStatement.Operation.UPDATE_BY_KEY));
        assertThrows(IllegalArgumentException.class, () -> withCondition.validateFor(ObjectStatement.Operation.UPDATE_BY_KEY));
        assertThrows(IllegalArgumentException.class, () -> withAssignment.validateFor(ObjectStatement.Operation.DELETE));
    }
}
