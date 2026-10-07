package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ModelClientCacheTest {
    /** A client the factory hands out: it counts how many exist and how many were closed. */
    private static final class Client {
        final String key;
        volatile boolean closed;
        Client(String key) { this.key = key; }
    }

    private final AtomicInteger created = new AtomicInteger();
    private final List<Client> closed = Collections.synchronizedList(new ArrayList<>());

    private ModelClientCache<String, Client> cache(CountDownLatch inFactory, CountDownLatch release) {
        return new ModelClientCache<>(key -> {
            created.incrementAndGet();
            if (inFactory != null) inFactory.countDown();
            if (release != null) {
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return new Client(key);
        }, client -> { client.closed = true; closed.add(client); });
    }

    @Test public void concurrentRequestsForOneKeyBuildOneClient() throws Exception {
        int threads = 8;
        CountDownLatch inFactory = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelClientCache<String, Client> cache = cache(inFactory, release);
        Client[] seen = new Client[threads];
        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int slot = i;
            pool[i] = new Thread(() -> seen[slot] = cache.get("stable/full"));
            pool[i].start();
        }
        // The first factory call is held open while every other thread has reached the cache.
        assertTrue(inFactory.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        release.countDown();
        for (Thread t : pool) t.join(5000);
        assertEquals("one client built for one key", 1, created.get());
        for (int i = 1; i < threads; i++) assertSame(seen[0], seen[i]);
        assertTrue("nothing was built and dropped", closed.isEmpty());
        assertEquals(1, cache.size());
    }

    @Test public void differentKeysStayDistinct() {
        ModelClientCache<String, Client> cache = cache(null, null);
        Client a = cache.get("stable/full");
        Client b = cache.get("preview/fast");
        assertNotSame(a, b);
        assertSame(a, cache.get("stable/full"));
        assertEquals(2, created.get());
    }

    @Test public void evictClosesTheFailedClientAndTheNextGetBuildsANewOne() {
        ModelClientCache<String, Client> cache = cache(null, null);
        Client first = cache.get("k");
        cache.evict("k", first);
        assertTrue(first.closed);
        assertEquals(1, closed.size());
        Client second = cache.get("k");
        assertNotSame(first, second);
        assertEquals(2, created.get());
    }

    @Test public void evictLeavesANewerClientAlone() {
        ModelClientCache<String, Client> cache = cache(null, null);
        Client old = cache.get("k");
        cache.evict("k", old);
        Client newer = cache.get("k");
        // A late failure of the old client must not remove or close the new one.
        cache.evict("k", old);
        assertTrue("the old one was closed once", old.closed);
        assertEquals(1, closed.size());
        assertSame(newer, cache.get("k"));
        assertTrue(!newer.closed);
    }

    @Test public void aClientThatCannotCloseDoesNotBreakEviction() {
        ModelClientCache<String, Client> cache = new ModelClientCache<>(Client::new, c -> { throw new IllegalStateException("close failed"); });
        Client c = cache.get("k");
        cache.evict("k", c);
        assertEquals(0, cache.size());
    }

    @Test public void aFactoryThatThrowsLeavesNothingCached() {
        AtomicInteger attempts = new AtomicInteger();
        ModelClientCache<String, Client> cache = new ModelClientCache<>(key -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("no service");
            return new Client(key);
        }, c -> { });
        try {
            cache.get("k");
            org.junit.Assert.fail("expected the factory's failure");
        } catch (IllegalStateException expected) {
            // fall through: the failure is not cached
        }
        assertEquals(0, cache.size());
        assertEquals("k", cache.get("k").key);
    }
}
