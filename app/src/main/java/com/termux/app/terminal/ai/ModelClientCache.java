package com.termux.app.terminal.ai;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One client per key, built once. The factory runs at most once per key even when several threads ask at the same
 * time, so a client the SDK builds is never created and then dropped without being closed.
 */
public final class ModelClientCache<K, V> {
    private final ConcurrentHashMap<K, V> clients = new ConcurrentHashMap<>();
    private final Function<K, V> factory;
    private final Consumer<V> closer;

    public ModelClientCache(Function<K, V> factory, Consumer<V> closer) {
        this.factory = factory;
        this.closer = closer;
    }

    public V get(K key) {
        // computeIfAbsent runs the factory at most once per key: concurrent callers wait for the same client.
        // A factory that throws leaves nothing cached.
        return clients.computeIfAbsent(key, factory);
    }

    /**
     * Drops {@code failed} and closes it, but only while it is still the cached client: a newer client that another
     * thread has already built for the key is left alone.
     */
    public void evict(K key, V failed) {
        if (clients.remove(key, failed)) {
            try {
                closer.accept(failed);
            } catch (RuntimeException ignored) {
                // A client that cannot close is already unusable; the next get builds a new one.
            }
        }
    }

    public int size() { return clients.size(); }
}
