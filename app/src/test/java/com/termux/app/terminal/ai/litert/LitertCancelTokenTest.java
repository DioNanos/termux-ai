package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LitertCancelTokenTest {
    @Test public void cancelRunsTheAttachedStopOnceEvenIfCalledTwice() {
        LitertCancelToken token = new LitertCancelToken();
        AtomicInteger stops = new AtomicInteger();
        token.onCancel(stops::incrementAndGet);
        token.cancel();
        token.cancel();
        assertTrue(token.isCancelled());
        assertEquals(1, stops.get());
    }

    @Test public void aStopAttachedAfterTheCancelRunsAtOnce() {
        LitertCancelToken token = new LitertCancelToken();
        token.cancel();
        AtomicInteger stops = new AtomicInteger();
        token.onCancel(stops::incrementAndGet);
        assertEquals("a cancel that came first is not lost", 1, stops.get());
    }

    @Test public void aCancelAfterDetachOnlyMarksTheFinishedToken() {
        LitertCancelToken token = new LitertCancelToken();
        AtomicInteger stops = new AtomicInteger();
        token.onCancel(stops::incrementAndGet);
        token.detach();
        token.cancel();
        assertEquals("the inference was over: nothing to stop", 0, stops.get());
        assertTrue(token.isCancelled());
    }

    @Test public void aTokenIsIndependentOfEveryOtherToken() {
        LitertCancelToken first = new LitertCancelToken();
        LitertCancelToken second = new LitertCancelToken();
        first.cancel();
        assertTrue(first.isCancelled());
        assertFalse(second.isCancelled());
    }

    @Test public void concurrentAttachAndCancelRunTheStopExactlyOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            LitertCancelToken token = new LitertCancelToken();
            AtomicInteger stops = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            Thread a = new Thread(() -> { await(go); token.onCancel(stops::incrementAndGet); });
            Thread b = new Thread(() -> { await(go); token.cancel(); });
            a.start(); b.start();
            go.countDown();
            a.join(2000); b.join(2000);
            assertEquals("round " + round, 1, stops.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
