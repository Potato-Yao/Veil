package com.potato.object;

import com.potato.VeilConfiguration;
import com.potato.database.DatabaseManager;
import com.potato.database.KeyType;
import com.potato.storage.DiskFileManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectManagerConcurrencyTest {
    @TempDir
    static Path tempDir;

    static ObjectManager objectManager;
    static DatabaseManager databaseManager;

    @BeforeAll
    static void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("concurrency.db"));
        dataSource.setBusyTimeout(3000);
        dataSource.setJournalMode("WAL");
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
    void buildAllowsExactlyOneWinningThreadPerNamespace() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ObjectManager>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return ObjectManager.build("race", databaseManager);
                    }))
                    .toList();
            start.countDown();

            int successes = 0;
            int conflicts = 0;
            for (Future<ObjectManager> future : futures) {
                try {
                    future.get();
                    successes++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertTrue(e.getCause() instanceof IllegalArgumentException);
                    conflicts++;
                }
            }
            assertEquals(1, successes, "exactly one thread must win the namespace");
            assertEquals(threads - 1, conflicts);
            assertTrue(ObjectManager.checkDuplicateNamespace("race"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyUpdatesLeaveBytesAndMetadataConsistent() throws Exception {
        String primaryKey = "contended";
        int writers = 8;

        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var futures = IntStream.range(0, writers)
                    .mapToObj(i -> (Future<?>) executor.submit(() -> {
                        try {
                            start.await();
                            byte[] payload = ("payload-" + i).getBytes(StandardCharsets.UTF_8);
                            for (int j = 0; j < 20; j++) {
                                objectManager.update(key(primaryKey, "u1"), "data.bin",
                                        new ByteArrayInputStream(payload));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        ObjectMetadata metadata = databaseManager.getMetadata("objects", key(primaryKey, "u1"));
        Path stored = tempDir.resolve(metadata.storageLocation());
        assertTrue(Files.exists(stored));
        byte[] bytes = Files.readAllBytes(stored);

        long expectedSize = bytes.length;
        assertEquals(expectedSize, metadata.fileSize(), "metadata size must match file bytes");
        assertEquals(HexFormat.of().formatHex(md5(bytes)), metadata.md5(), "metadata md5 must match file bytes");
    }

    @Test
    void concurrentGetsFlushAggregatedAccessStatistics() throws Exception {
        String primaryKey = "access-stats";
        int readers = 6;
        int readsPerThread = 100;

        objectManager.put(key(primaryKey, "u1"), "data.bin",
                new ByteArrayInputStream("stats".getBytes(StandardCharsets.UTF_8)));

        ExecutorService executor = Executors.newFixedThreadPool(readers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var futures = IntStream.range(0, readers)
                    .mapToObj(i -> (Future<?>) executor.submit(() -> {
                        try {
                            start.await();
                            for (int j = 0; j < readsPerThread; j++) {
                                try (ObjectData ignored = objectManager.get(key(primaryKey, "u1"))) {
                                    // Reading the stream is enough for this test; the
                                    // access delta must be aggregated in memory.
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        databaseManager.flushAccessStats();

        ObjectMetadata metadata = databaseManager.getMetadata("objects", key(primaryKey, "u1"));
        assertNotNull(metadata);
        assertEquals((long) readers * readsPerThread, metadata.accessCount(),
                "one batched flush must persist every recorded access");
        assertNotNull(metadata.lastAccessedAt());
    }

    @Test
    void concurrentGetNeverObservesMismatchedMetadataAndBytes() throws Exception {
        String primaryKey = "reader";
        objectManager.put(key(primaryKey, "u1"), "data.bin",
                new ByteArrayInputStream("initial".getBytes(StandardCharsets.UTF_8)));

        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 3; i++) {
                byte[] payload = ("writer-" + i).getBytes(StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 30; j++) {
                            objectManager.update(key(primaryKey, "u1"), "data.bin",
                                    new ByteArrayInputStream(payload));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            for (int i = 0; i < 3; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 100; j++) {
                            try (ObjectData object = objectManager.get(key(primaryKey, "u1"))) {
                                byte[] read = object.stream().readAllBytes();
                                assertEquals(object.metadata().fileSize(), read.length,
                                        "metadata size must match read bytes");
                                assertEquals(HexFormat.of().formatHex(md5(read)), object.metadata().md5(),
                                        "metadata md5 must match read bytes");
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPutAndRemoveLeaveConsistentFinalState() throws Exception {
        String primaryKey = "toggle";

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new CopyOnWriteArrayList<>();
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        try {
                            objectManager.put(key(primaryKey, "u1"), "data.bin",
                                    new ByteArrayInputStream("put".getBytes(StandardCharsets.UTF_8)));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        try {
                            objectManager.remove(key(primaryKey, "u1"));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        ObjectMetadata metadata = databaseManager.getMetadata("objects", key(primaryKey, "u1"));
        boolean inDb = metadata != null;
        boolean exists = inDb && Files.exists(tempDir.resolve(metadata.storageLocation()));
        assertEquals(exists, inDb, "file and metadata must agree after concurrent put/remove");
        if (exists) {
            assertFalse(Files.readAllBytes(tempDir.resolve(metadata.storageLocation())).length == 0);
        }
    }

    @Test
    void parallelWritesToDistinctKeysDoNotHitDatabaseIsLocked() throws Exception {
        int writers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var futures = IntStream.range(0, writers)
                    .mapToObj(i -> (Future<?>) executor.submit(() -> {
                        try {
                            start.await();
                            for (int j = 0; j < 30; j++) {
                                objectManager.update(key("key-" + i + "-" + j, "u" + i), "data.bin",
                                        new ByteArrayInputStream(("data-" + i).getBytes(StandardCharsets.UTF_8)));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] md5(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
