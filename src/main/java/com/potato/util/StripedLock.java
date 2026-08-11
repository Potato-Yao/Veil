package com.potato.util;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded, striped set of {@link ReentrantLock}s.
 *
 * <p>Keys are hashed onto a fixed number of stripes so that no per-key lock object
 * is ever created, keeping memory bounded regardless of how many distinct keys are
 * used. Keys that collide on the same stripe are serialized, while keys on
 * different stripes run concurrently. Locks must never be nested and must always be
 * released, typically via {@link #withLock(String, Runnable)} or a {@code try}
 * / {@code finally} around {@link #lock(String)} and {@link #unlock(String)}.</p>
 */
public class StripedLock {
    private static final int DEFAULT_STRIPES = 64;

    private final ReentrantLock[] locks;

    /**
     * Creates a striped lock with {@value #DEFAULT_STRIPES} stripes.
     */
    public StripedLock() {
        this(DEFAULT_STRIPES);
    }

    /**
     * Creates a striped lock with a power-of-two number of stripes.
     *
     * @param stripes the number of stripes, rounded up to the next power of two
     */
    public StripedLock(int stripes) {
        int size = 1;
        while (size < stripes) {
            size <<= 1;
        }
        locks = new ReentrantLock[size];
        for (int i = 0; i < size; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Acquires the lock guarding the given key, blocking until it is available.
     *
     * @param key the key to lock
     */
    public void lock(String key) {
        lockFor(key).lock();
    }

    /**
     * Releases the lock guarding the given key.
     *
     * @param key the key to unlock
     */
    public void unlock(String key) {
        lockFor(key).unlock();
    }

    /**
     * Runs {@code action} while holding the lock guarding the given key.
     *
     * @param key    the key to lock
     * @param action the action to run under the lock
     */
    public void withLock(String key, Runnable action) {
        lock(key);
        try {
            action.run();
        } finally {
            unlock(key);
        }
    }

    private ReentrantLock lockFor(String key) {
        return locks[key.hashCode() & (locks.length - 1)];
    }
}
