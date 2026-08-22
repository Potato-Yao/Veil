package com.potato.database;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * In-memory access-statistics accumulator with batched, write-behind flushing.
 *
 * <p>Access events are recorded with {@link #record(ObjectId)} without any database
 * I/O. A daemon flusher drains the accumulated counters once per second and passes
 * them to the configured sink, which persists all namespaces in one transaction.
 * Recording threads also trigger an early flush when pending event or object limits
 * are reached, keeping memory bounded under heavy read traffic.</p>
 *
 * <p>If a flush fails, the drained counters are merged back into memory so the next
 * flush retries them; no successful read is made to fail because access-statistics
 * persistence is temporarily unavailable.</p>
 */
final class AccessStatsTracker {
    private static final System.Logger LOGGER = System.getLogger(AccessStatsTracker.class.getName());
    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(1);
    private static final int MAX_PENDING_EVENTS = 1_000;
    private static final int MAX_PENDING_OBJECTS = 10_000;
    private static final ScheduledExecutorService FLUSH_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "veil-access-stats-flusher");
        thread.setDaemon(true);
        return thread;
    });

    private final Consumer<Map<ObjectId, AccessStat>> flusher;
    private final ConcurrentHashMap<ObjectId, Counter> counters = new ConcurrentHashMap<>();
    private final LongAdder pendingEvents = new LongAdder();
    private final AtomicBoolean flushInProgress = new AtomicBoolean();
    private final AtomicBoolean flushRequested = new AtomicBoolean();
    private final ScheduledFuture<?> scheduledFlush;

    AccessStatsTracker(Consumer<Map<ObjectId, AccessStat>> flusher) {
        this.flusher = flusher;
        this.scheduledFlush = FLUSH_EXECUTOR.scheduleWithFixedDelay(
                this::flushSafely,
                FLUSH_INTERVAL.toMillis(),
                FLUSH_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Records one access against an object identity.
     *
     * @param id the full object identity ({@code namespace} + key columns)
     */
    void record(ObjectId id) {
        long now = System.currentTimeMillis();
        counters.compute(id, (key, existing) -> {
            Counter counter = existing == null ? new Counter() : existing;
            counter.count.incrementAndGet();
            counter.lastAccessMillis.accumulateAndGet(now, Math::max);
            return counter;
        });
        pendingEvents.increment();

        if (pendingEvents.sum() >= MAX_PENDING_EVENTS || counters.mappingCount() >= MAX_PENDING_OBJECTS) {
            requestFlush();
        }
    }

    /**
     * Drains all currently recorded access statistics and persists them synchronously.
     *
     * <p>On failure the drained batch is returned to the in-memory map before the
     * exception is propagated.</p>
     */
    synchronized void flush() {
        Map<ObjectId, AccessStat> batch = drain();
        if (batch.isEmpty()) {
            return;
        }

        long drainedEvents = batch.values().stream().mapToLong(AccessStat::count).sum();
        try {
            flusher.accept(batch);
        } catch (RuntimeException e) {
            restore(batch);
            throw e;
        }
        pendingEvents.add(-drainedEvents);
    }

    /**
     * Cancels the scheduled flush and performs one final best-effort flush.
     */
    void close() {
        scheduledFlush.cancel(false);
        flushSafely();
    }

    private void requestFlush() {
        if (flushRequested.compareAndSet(false, true)) {
            try {
                FLUSH_EXECUTOR.execute(() -> {
                    try {
                        flushSafely();
                    } finally {
                        flushRequested.set(false);
                    }
                });
            } catch (RuntimeException e) {
                flushRequested.set(false);
                throw e;
            }
        }
    }

    private void flushSafely() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            flush();
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to flush Veil access statistics; will retry on the next flush", e);
        } finally {
            flushInProgress.set(false);
        }
    }

    private Map<ObjectId, AccessStat> drain() {
        Map<ObjectId, AccessStat> batch = new HashMap<>();
        for (ObjectId id : counters.keySet()) {
            counters.computeIfPresent(id, (key, counter) -> {
                long count = counter.count.get();
                if (count > 0) {
                    batch.put(id, new AccessStat(count, counter.lastAccessMillis.get()));
                    return null;
                }
                return counter;
            });
        }
        return batch;
    }

    private void restore(Map<ObjectId, AccessStat> batch) {
        batch.forEach((id, stat) -> counters.merge(
                id,
                new Counter(stat.count(), stat.lastAccessMillis()),
                (existing, added) -> {
                    existing.add(added);
                    return existing;
                }));
    }

    /**
     * The immutable full identity of an object.
     *
     * @param namespace the namespace of the object
     * @param key       the primary key of the object
     * @param kv        the additional key column values
     */
    record ObjectId(String namespace, String key, Map<String, String> kv) {
        ObjectId {
            kv = Map.copyOf(kv);
        }
    }

    /**
     * A drained batch entry.
     *
     * @param count            the number of accesses recorded since the last successful flush
     * @param lastAccessMillis the most recent access time, in epoch milliseconds
     */
    record AccessStat(long count, long lastAccessMillis) {
    }

    private static final class Counter {
        private final AtomicLong count;
        private final AtomicLong lastAccessMillis;

        private Counter() {
            this(0, 0);
        }

        private Counter(long count, long lastAccessMillis) {
            this.count = new AtomicLong(count);
            this.lastAccessMillis = new AtomicLong(lastAccessMillis);
        }

        private void add(Counter other) {
            count.addAndGet(other.count.get());
            lastAccessMillis.accumulateAndGet(other.lastAccessMillis.get(), Math::max);
        }
    }
}
