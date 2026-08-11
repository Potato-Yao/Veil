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

import javax.sql.DataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
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

    private static ObjectStatement key(String primaryKey, String userId) {
        return ObjectStatement.builder().key(primaryKey).kv("user_id", userId).build();
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

        objectManager.put(key(primaryKey, "u7"), fileName, new ByteArrayInputStream(data));

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
                objectManager.put(ObjectStatement.builder().key("obj2").kv("unknown_col", "v").build(),
                        "a.txt", new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    void updateReplacesExistingRowAndFile() throws Exception {
        byte[] first = "first version".getBytes();
        byte[] second = "second version, longer".getBytes();
        String primaryKey = "obj3";
        String fileName = "doc.txt";

        objectManager.put(key(primaryKey, "u3"), fileName, new ByteArrayInputStream(first));
        objectManager.update(key(primaryKey, "u3"), fileName, new ByteArrayInputStream(second));

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
    void updateWithDifferentIdentityCreatesSeparateObject() throws Exception {
        byte[] first = "old file".getBytes();
        byte[] second = "new file".getBytes();
        String primaryKey = "obj4";
        String fileName = "doc.txt";

        objectManager.put(key(primaryKey, "u4"), fileName, new ByteArrayInputStream(first));
        objectManager.update(key(primaryKey, "u4b"), fileName, new ByteArrayInputStream(second));

        assertArrayEquals(first, Files.readAllBytes(tempDir.resolve("objects/obj4_u4")));
        assertArrayEquals(second, Files.readAllBytes(tempDir.resolve("objects/obj4_u4b")));
        assertTrue(objectManager.checkExist(key(primaryKey, "u4")));
        assertTrue(objectManager.checkExist(key(primaryKey, "u4b")));
    }

    @Test
    void getReturnsMetadataAndContent() throws Exception {
        byte[] data = "get me".getBytes();
        String primaryKey = "obj5";
        String fileName = "get.txt";

        objectManager.put(key(primaryKey, "u5"), fileName, new ByteArrayInputStream(data));

        try (ObjectData object = objectManager.get(key(primaryKey, "u5"))) {
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
                objectManager.get(key("missing", "uX")));
    }

    @Test
    void removeDeletesFileAndMetadata() throws Exception {
        byte[] data = "bye".getBytes();
        String primaryKey = "obj6";
        String fileName = "bye.txt";

        objectManager.put(key(primaryKey, "u6"), fileName, new ByteArrayInputStream(data));
        assertTrue(Files.exists(tempDir.resolve("objects/obj6_u6")));

        assertTrue(objectManager.remove(key(primaryKey, "u6")));
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
                objectManager.remove(key("missing", "uX")));
    }

    @Test
    void checkExistReflectsPutAndRemove() throws Exception {
        String primaryKey = "obj7";

        assertFalse(objectManager.checkExist(key(primaryKey, "u7")));
        objectManager.put(key(primaryKey, "u7"), "check.txt", new ByteArrayInputStream("check".getBytes()));
        assertTrue(objectManager.checkExist(key(primaryKey, "u7")));
        objectManager.remove(key(primaryKey, "u7"));
        assertFalse(objectManager.checkExist(key(primaryKey, "u7")));
    }

    @Test
    void getUpdatesAccessCountAndLastAccessedAt() throws Exception {
        String primaryKey = "obj8";

        objectManager.put(key(primaryKey, "u8"), "track.txt", new ByteArrayInputStream("track me".getBytes()));
        try (ObjectData ignored = objectManager.get(key(primaryKey, "u8"))) {
        }
        try (ObjectData ignored = objectManager.get(key(primaryKey, "u8"))) {
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

        objectManager.put(key(primaryKey, "u10"), "count.txt", new ByteArrayInputStream("count".getBytes()));
        try (ObjectData object = objectManager.get(key(primaryKey, "u10"))) {
            assertEquals(0, object.metadata().accessCount());
        }
        try (ObjectData object = objectManager.get(key(primaryKey, "u10"))) {
            assertEquals(1, object.metadata().accessCount());
        }
    }

    @Test
    void updatePreservesAccessState() throws Exception {
        String primaryKey = "obj9";
        String fileName = "doc.txt";

        objectManager.put(key(primaryKey, "u9"), fileName, new ByteArrayInputStream("original".getBytes()));
        try (ObjectData ignored = objectManager.get(key(primaryKey, "u9"))) {
        }
        objectManager.update(key(primaryKey, "u9"), fileName, new ByteArrayInputStream("new version".getBytes()));

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
        queryManager.put(key("qA", "uA"), "a.png", new ByteArrayInputStream("hello".getBytes()));
        queryManager.put(key("qB", "uB"), "b.jpg", new ByteArrayInputStream("abcdefghijklmno".getBytes()));
        queryManager.put(key("qC", "uC"), "c.png", new ByteArrayInputStream("12345678".getBytes()));

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
        queryManager.put(key("addr1", "uX"), "doc.txt", new ByteArrayInputStream("addressable".getBytes()));

        ObjectReference reference = databaseManager.query("query_addressable", ObjectStatement.builder().build()).get(0);
        assertEquals("addr1", reference.key());
        assertEquals(Map.of("user_id", "uX"), reference.kv());
        assertEquals("doc.txt", reference.metadata().fileName());

        try (ObjectData object = queryManager.get(ObjectStatement.builder().key(reference.key()).kv(reference.kv()).build())) {
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
        queryManager.put(key("iA", "uI"), "a.txt", new ByteArrayInputStream("12345".getBytes()));
        queryManager.put(key("iB", "uI"), "b.txt", new ByteArrayInputStream("123456789".getBytes()));

        List<ObjectReference> results = databaseManager.query("query_ints",
                ObjectStatement.builder().where("file_size", ObjectStatement.Op.GT, 5).build());
        assertEquals(List.of("iB"), results.stream().map(ObjectReference::key).toList());
    }

    @Test
    void updateMetadataPartiallyUpdatesMetadata() throws Exception {
        ObjectManager metaManager = ObjectManager.build("meta", databaseManager);
        byte[] data = "meta payload".getBytes();
        String primaryKey = "meta1";
        metaManager.put(key(primaryKey, "uM"), "photo.png", new ByteArrayInputStream(data));

        metaManager.updateMetadata(ObjectStatement.builder().key(primaryKey).kv("user_id", "uM")
                .set("file_name", "renamed.png").build());

        ObjectMetadata afterName = databaseManager.getMetadata("meta", key(primaryKey, "uM"));
        assertEquals("renamed.png", afterName.fileName());
        assertEquals("png", afterName.fileExtension());
        assertEquals(data.length, afterName.fileSize());
        assertEquals("meta/meta1_uM", afterName.storageLocation());
        assertEquals(0, afterName.accessCount());

        metaManager.updateMetadata(ObjectStatement.builder().key(primaryKey).kv("user_id", "uM")
                .set("file_extension", "jpg").build());
        ObjectMetadata afterExtension = databaseManager.getMetadata("meta", key(primaryKey, "uM"));
        assertEquals("renamed.png", afterExtension.fileName());
        assertEquals("jpg", afterExtension.fileExtension());

        metaManager.updateMetadata(ObjectStatement.builder().key(primaryKey).kv("user_id", "uM")
                .set("file_name", "final.jpg").build());
        ObjectMetadata afterBoth = databaseManager.getMetadata("meta", key(primaryKey, "uM"));
        assertEquals("final.jpg", afterBoth.fileName());
        assertEquals("jpg", afterBoth.fileExtension());
    }

    @Test
    void updateMetadataThrowsWhenObjectDoesNotExist() {
        ObjectManager metaManager = ObjectManager.build("meta_missing", databaseManager);
        assertThrows(IllegalArgumentException.class, () ->
                metaManager.updateMetadata(ObjectStatement.builder().key("nope").kv("user_id", "uX")
                        .set("file_name", "x.txt").build()));
    }

    @Test
    void updateMetadataRejectsEmptyUpdateAndConditions() throws Exception {
        ObjectManager metaManager = ObjectManager.build("meta_invalid", databaseManager);
        metaManager.put(key("m1", "u1"), "a.txt", new ByteArrayInputStream("a".getBytes()));
        assertThrows(IllegalArgumentException.class, () ->
                metaManager.updateMetadata(ObjectStatement.builder().key("m1").kv("user_id", "u1").build()));
        assertThrows(IllegalArgumentException.class, () ->
                metaManager.updateMetadata(ObjectStatement.builder().key("m1").kv("user_id", "u1")
                        .set("file_name", "b.txt")
                        .where("file_extension", ObjectStatement.Op.EQ, "txt").build()));
    }

    @Test
    void executeUpdateBatchUpdatesMatchingRows() throws Exception {
        ObjectManager batchManager = ObjectManager.build("batch", databaseManager);
        batchManager.put(key("b1", "uB"), "small.txt", new ByteArrayInputStream("123".getBytes()));
        batchManager.put(key("b2", "uB"), "big.txt", new ByteArrayInputStream("12345678901234567890".getBytes()));

        long updated = databaseManager.executeUpdate("batch",
                ObjectStatement.builder().set("file_name", "renamed.txt")
                        .where("file_size", ObjectStatement.Op.GTE, 10).build());
        assertEquals(1, updated);

        assertEquals("small.txt", databaseManager.getMetadata("batch", key("b1", "uB")).fileName());
        assertEquals("renamed.txt", databaseManager.getMetadata("batch", key("b2", "uB")).fileName());
    }

    @Test
    void executeUpdateRejectsNonUpdatableColumn() {
        assertThrows(IllegalArgumentException.class, () ->
                databaseManager.executeUpdate("batch",
                        ObjectStatement.builder().set("key", "x").build()));
    }

    @Test
    void queryAndCountRejectAssignments() throws Exception {
        ObjectManager manager = ObjectManager.build("reject_query", databaseManager);
        manager.put(key("k1", "u1"), "a.txt", new ByteArrayInputStream("a".getBytes()));

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
                databaseManager.executeUpdate("batch", ObjectStatement.builder().key("k1").build()));
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

    @Test
    void rejectsTraversalInPrimaryKey() {
        List<String> evilKeys = List.of(
                "../escape", "a/../../x", "/etc/passwd", "~/home", "-flag",
                "C:evil", "a\\..\\x", "a//b", "a/", "..");
        for (String evil : evilKeys) {
            ObjectStatement statement = ObjectStatement.builder().key(evil).build();
            assertThrows(IllegalArgumentException.class, () ->
                    objectManager.put(statement, "a.txt", new ByteArrayInputStream("x".getBytes())), evil);
            assertThrows(IllegalArgumentException.class, () -> objectManager.get(statement), evil);
        }
    }

    @Test
    void rejectsTraversalInAdditionalKeyValue() {
        ObjectStatement statement = ObjectStatement.builder().key("ok").kv("user_id", "../../x").build();
        assertThrows(IllegalArgumentException.class, () ->
                objectManager.put(statement, "a.txt", new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    void rejectsTraversalInNamespace() {
        List<String> evilNamespaces = List.of("../escape", "/abs", "~/home", "-flag");
        for (String evil : evilNamespaces) {
            assertThrows(IllegalArgumentException.class, () ->
                    ObjectManager.build(evil, databaseManager), evil);
        }
    }

    @Test
    void sameKeyWithDifferentAdditionalKeyValuesCoexist() throws Exception {
        ObjectManager manager = ObjectManager.build("identity", databaseManager);
        manager.put(key("shared", "u1"), "a.png", new ByteArrayInputStream("one".getBytes()));
        manager.put(key("shared", "u2"), "a.png", new ByteArrayInputStream("two".getBytes()));

        assertTrue(manager.checkExist(key("shared", "u1")));
        assertTrue(manager.checkExist(key("shared", "u2")));
        assertEquals("identity/shared_u1",
                databaseManager.getStorageLocation("identity", key("shared", "u1")));
        assertEquals("identity/shared_u2",
                databaseManager.getStorageLocation("identity", key("shared", "u2")));
        assertTrue(Files.exists(tempDir.resolve("identity/shared_u1")));
        assertTrue(Files.exists(tempDir.resolve("identity/shared_u2")));
        assertEquals(2, databaseManager.count("identity", ObjectStatement.builder().build()));
    }

    @Test
    void getRemoveAndCheckExistRequireMatchingAdditionalKeyValues() throws Exception {
        ObjectManager manager = ObjectManager.build("identity_mismatch", databaseManager);
        manager.put(key("obj", "u1"), "a.txt", new ByteArrayInputStream("data".getBytes()));

        assertFalse(manager.checkExist(key("obj", "other")));
        assertThrows(IllegalArgumentException.class, () -> manager.get(key("obj", "other")));
        assertThrows(IllegalArgumentException.class, () -> manager.remove(key("obj", "other")));

        assertTrue(manager.checkExist(key("obj", "u1")));
        manager.remove(key("obj", "u1"));
        assertFalse(manager.checkExist(key("obj", "u1")));
        assertTrue(tempFilesUnder("identity_mismatch").isEmpty());
    }

    @Test
    void keyedOperationsRequireAllAdditionalKeyValues() throws Exception {
        ObjectManager manager = ObjectManager.build("identity_missing", databaseManager);
        ObjectStatement missingKv = ObjectStatement.builder().key("obj").build();
        assertThrows(IllegalArgumentException.class, () ->
                manager.put(missingKv, "a.txt", new ByteArrayInputStream("x".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> manager.get(missingKv));
        assertThrows(IllegalArgumentException.class, () -> manager.remove(missingKv));
        assertThrows(IllegalArgumentException.class, () -> manager.checkExist(missingKv));
    }

    @Test
    void longAdditionalKeyColumnEndToEnd() throws Exception {
        DatabaseManager longDb = DatabaseManager.builder()
                .dataSource(dataSource)
                .keyColumn("tenant_id", KeyType.LONG)
                .build();
        ObjectManager manager = ObjectManager.build("long_key", longDb);

        ObjectStatement tenant = ObjectStatement.builder().key("doc1").kv("tenant_id", "7").build();
        manager.put(tenant, "a.txt", new ByteArrayInputStream("data".getBytes()));

        assertTrue(manager.checkExist(tenant));
        try (ObjectData object = manager.get(tenant)) {
            assertArrayEquals("data".getBytes(), object.stream().readAllBytes());
        }
        assertEquals("long_key/doc1_7",
                longDb.getStorageLocation("long_key", tenant));

        ObjectStatement otherTenant = ObjectStatement.builder().key("doc1").kv("tenant_id", "8").build();
        assertFalse(manager.checkExist(otherTenant));

        assertThrows(IllegalArgumentException.class, () ->
                manager.put(ObjectStatement.builder().key("doc1").kv("tenant_id", "not-a-long").build(),
                        "b.txt", new ByteArrayInputStream("x".getBytes())));

        manager.remove(tenant);
        assertFalse(manager.checkExist(tenant));
    }

    @Test
    void putAndGetLeaveNoFilesForRejectedKeys() throws Exception {
        ObjectManager manager = ObjectManager.build("rejected_keys", databaseManager);
        ObjectStatement statement = ObjectStatement.builder().key("a/../../x").build();
        assertThrows(IllegalArgumentException.class, () ->
                manager.put(statement, "a.txt", new ByteArrayInputStream("x".getBytes())));

        assertEquals(List.of(), tempFilesUnder("rejected_keys"));
    }

    private static FailingDatabaseManager failingManager() {
        HashMap<String, KeyType> keyColumns = new HashMap<>();
        keyColumns.put("user_id", KeyType.TEXT);
        return new FailingDatabaseManager(dataSource, keyColumns);
    }

    private static List<Path> tempFilesUnder(String namespace) throws IOException {
        Path dir = tempDir.resolve(namespace);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void putRemovesTempFileWhenInsertFails() throws Exception {
        FailingDatabaseManager failing = failingManager();
        failing.failOn("insert");
        ObjectManager manager = ObjectManager.build("put_insert_fail", failing);

        assertThrows(RuntimeException.class, () ->
                manager.put(key("obj", "u1"), "a.txt", new ByteArrayInputStream("data".getBytes())));

        assertFalse(manager.checkExist(key("obj", "u1")));
        assertTrue(tempFilesUnder("put_insert_fail").isEmpty());
    }

    @Test
    void putOnExistingKeyThrowsBeforeTouchingFile() throws Exception {
        FailingDatabaseManager failing = failingManager();
        ObjectManager manager = ObjectManager.build("put_dup", failing);
        byte[] original = "original".getBytes();
        manager.put(key("obj", "u1"), "a.txt", new ByteArrayInputStream(original));

        assertThrows(IllegalArgumentException.class, () ->
                manager.put(key("obj", "u1"), "b.txt", new ByteArrayInputStream("clobber".getBytes())));

        assertArrayEquals(original, Files.readAllBytes(tempDir.resolve("put_dup/obj_u1")));
        assertEquals(1, tempFilesUnder("put_dup").size());
    }

    @Test
    void updatePreservesFileAndMetadataWhenUpsertFailsSameLocation() throws Exception {
        FailingDatabaseManager failing = failingManager();
        ObjectManager manager = ObjectManager.build("update_fail", failing);
        byte[] original = "original".getBytes();
        manager.put(key("obj", "u1"), "a.txt", new ByteArrayInputStream(original));
        failing.failOn("upsert");

        assertThrows(RuntimeException.class, () ->
                manager.update(key("obj", "u1"), "b.txt", new ByteArrayInputStream("new version".getBytes())));

        assertArrayEquals(original, Files.readAllBytes(tempDir.resolve("update_fail/obj_u1")));
        assertEquals(original.length, databaseManager.getMetadata("update_fail", key("obj", "u1")).fileSize());
        assertEquals(1, tempFilesUnder("update_fail").size());
    }

    @Test
    void updatePreservesOldFileWhenUpsertFailsAndLocationChanges() throws Exception {
        FailingDatabaseManager failing = failingManager();
        ObjectManager manager = ObjectManager.build("update_reloc", failing);
        byte[] original = "old file".getBytes();
        manager.put(key("obj", "uA"), "a.txt", new ByteArrayInputStream(original));
        failing.failOn("upsert");

        assertThrows(RuntimeException.class, () ->
                manager.update(key("obj", "uB"), "b.txt", new ByteArrayInputStream("new file".getBytes())));

        assertArrayEquals(original, Files.readAllBytes(tempDir.resolve("update_reloc/obj_uA")));
        assertEquals("update_reloc/obj_uA",
                databaseManager.getStorageLocation("update_reloc", key("obj", "uA")));
        assertFalse(Files.exists(tempDir.resolve("update_reloc/obj_uB")));
    }

    @Test
    void removeKeepsFileAndMetadataWhenDeleteFails() throws Exception {
        FailingDatabaseManager failing = failingManager();
        ObjectManager manager = ObjectManager.build("remove_fail", failing);
        byte[] data = "bye".getBytes();
        manager.put(key("obj", "u1"), "bye.txt", new ByteArrayInputStream(data));
        failing.failOn("delete");

        assertThrows(RuntimeException.class, () -> manager.remove(key("obj", "u1")));

        assertArrayEquals(data, Files.readAllBytes(tempDir.resolve("remove_fail/obj_u1")));
        assertNotNull(databaseManager.getMetadata("remove_fail", key("obj", "u1")));
    }

    /**
     * {@link DatabaseManager} whose write operations can be made to fail on demand,
     * used to verify that {@link ObjectManager} leaves no partial state behind.
     */
    private static class FailingDatabaseManager extends DatabaseManager {
        private String failOn;

        FailingDatabaseManager(DataSource dataSource, HashMap<String, KeyType> keyColumns) {
            super(dataSource, keyColumns);
        }

        void failOn(String operation) {
            this.failOn = operation;
        }

        private void failIf(String operation) {
            if (operation.equals(failOn)) {
                throw new RuntimeException("injected " + operation + " failure");
            }
        }

        @Override
        public void upsertMetadata(String namespace, ObjectStatement statement, ObjectMetadata metadata, boolean overwrite) {
            failIf(overwrite ? "upsert" : "insert");
            super.upsertMetadata(namespace, statement, metadata, overwrite);
        }

        @Override
        public boolean delete(String namespace, ObjectStatement statement) {
            failIf("delete");
            return super.delete(namespace, statement);
        }
    }
}
