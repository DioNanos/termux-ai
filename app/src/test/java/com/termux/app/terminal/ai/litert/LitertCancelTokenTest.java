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
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        AtomicInteger stops = new AtomicInteger();
        token.onCancel(stops::incrementAndGet);
        token.cancel();
        token.cancel();
        assertTrue(token.isCancelled());
        assertTrue(token.detach(2000));
        assertEquals(1, stops.get());
    }

    @Test public void aStopAttachedAfterTheCancelRunsAtOnce() {
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        token.cancel();
        AtomicInteger stops = new AtomicInteger();
        token.onCancel(stops::incrementAndGet);
        assertTrue(token.detach(2000));
        assertEquals("a cancel that came first is not lost", 1, stops.get());
    }

    @Test public void aCancelAfterDetachOnlyMarksTheFinishedToken() {
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        AtomicInteger stops = new AtomicInteger();
        token.onCancel(stops::incrementAndGet);
        assertTrue(token.detach(1000));
        token.cancel();
        assertEquals("the inference was over: nothing to stop", 0, stops.get());
        assertTrue(token.isCancelled());
    }

    @Test public void aTokenIsIndependentOfEveryOtherToken() {
        LitertCancelToken first = new LitertCancelToken(new LitertStopper());
        LitertCancelToken second = new LitertCancelToken(new LitertStopper());
        first.cancel();
        assertTrue(first.isCancelled());
        assertFalse(second.isCancelled());
    }

    @Test public void concurrentAttachAndCancelRunTheStopExactlyOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            LitertCancelToken token = new LitertCancelToken(new LitertStopper());
            AtomicInteger stops = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            Thread a = new Thread(() -> { await(go); token.onCancel(stops::incrementAndGet); });
            Thread b = new Thread(() -> { await(go); token.cancel(); });
            a.start(); b.start();
            go.countDown();
            a.join(2000); b.join(2000);
            assertTrue(token.detach(2000));
            assertEquals("round " + round, 1, stops.get());
        }
    }

    // --- stop and close are coordinated ---------------------------------------------------------------------

    /** A conversation as the SDK has it: a stop on a closed one, or a close during a stop, is a native fault. */
    private static final class Conversation {
        final AtomicInteger violations = new AtomicInteger();
        volatile boolean closed;
        volatile boolean stopRunning;
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final CountDownLatch stopRelease = new CountDownLatch(1);

        void stop() {
            if (closed) violations.incrementAndGet();      // a stop must never start on a closed conversation
            stopRunning = true;
            stopEntered.countDown();
            try { stopRelease.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            stopRunning = false;
        }

        void close() {
            if (stopRunning) violations.incrementAndGet(); // a close must never overlap a running stop
            closed = true;
        }
    }

    @Test public void finishDoesNotCloseWhileAStopIsStillRunning() throws Exception {
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        Conversation conversation = new Conversation();
        token.onCancel(conversation::stop);
        Thread canceller = new Thread(token::cancel);
        canceller.start();
        assertTrue(conversation.stopEntered.await(5, TimeUnit.SECONDS));
        // The generation returned and is finishing while the stop is still in flight.
        AtomicInteger finished = new AtomicInteger();
        Thread generation = new Thread(() -> {
            try { token.finish(5000, conversation::close); finished.set(1); } catch (LitertFailure e) { finished.set(-1); }
        });
        generation.start();
        Thread.sleep(300);
        assertFalse("the conversation must still be open: the stop has not returned", conversation.closed);
        conversation.stopRelease.countDown();
        generation.join(5000);
        canceller.join(5000);
        assertEquals(1, finished.get());
        assertTrue(conversation.closed);
        assertEquals("no stop overlapped the close", 0, conversation.violations.get());
    }

    @Test public void aStopThatNeverReturnsGivesATypedErrorAndTheConversationStaysOpen() throws Exception {
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        Conversation conversation = new Conversation();
        token.onCancel(conversation::stop);
        Thread canceller = new Thread(token::cancel);
        canceller.start();
        assertTrue(conversation.stopEntered.await(5, TimeUnit.SECONDS));
        try {
            token.finish(200, conversation::close);
            org.junit.Assert.fail("a stuck stop must not be waited for forever");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.CANCEL_TIMEOUT, f.code);
            assertTrue(f.getMessage(), f.getMessage().contains("200 ms"));
        }
        assertFalse("closing under a running stop is the fault being avoided", conversation.closed);
        conversation.stopRelease.countDown();
        canceller.join(5000);
        assertEquals(0, conversation.violations.get());
    }

    @Test public void aStopCannotStartOnceTheGenerationHasFinished() throws Exception {
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        Conversation conversation = new Conversation();
        token.onCancel(conversation::stop);
        token.finish(1000, conversation::close);
        token.cancel();
        assertTrue(conversation.closed);
        assertFalse("no stop was started", conversation.stopEntered.await(100, TimeUnit.MILLISECONDS));
        assertEquals(0, conversation.violations.get());
    }

    @Test public void theStopDoesNotWaitForTheGenerationSoThereIsNoDeadlock() throws Exception {
        // The stop runs on the canceller's thread and takes no lock the generation needs while it waits.
        for (int round = 0; round < 100; round++) {
            LitertCancelToken token = new LitertCancelToken(new LitertStopper());
            Conversation conversation = new Conversation();
            conversation.stopRelease.countDown();               // a stop that returns promptly
            token.onCancel(conversation::stop);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger outcome = new AtomicInteger();
            Thread canceller = new Thread(() -> { await(go); token.cancel(); });
            Thread generation = new Thread(() -> {
                await(go);
                try { token.finish(5000, conversation::close); outcome.set(1); } catch (LitertFailure e) { outcome.set(-1); }
            });
            canceller.start(); generation.start();
            go.countDown();
            generation.join(5000); canceller.join(5000);
            assertFalse("round " + round + " hung", generation.isAlive() || canceller.isAlive());
            assertEquals("round " + round, 1, outcome.get());
            assertEquals("round " + round, 0, conversation.violations.get());
        }
    }

    // --- the stop never runs on the thread that cancels -----------------------------------------------------

    @Test public void cancelReturnsAtOnceEvenIfTheNativeStopBlocks() throws Exception {
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        token.onCancel(() -> { entered.countDown(); await(release); });
        Thread caller = new Thread(token::cancel);
        long start = System.nanoTime();
        caller.start();
        caller.join(2000);
        assertFalse("cancel() must not wait for the native stop (it runs on the service's only IPC thread)", caller.isAlive());
        assertTrue("well under the stop's own duration", (System.nanoTime() - start) / 1_000_000L < 1500);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertFalse("the stop is still in flight", token.detach(100));
        release.countDown();
        assertTrue(token.detach(2000));
    }

    @Test public void theStopRunsOnItsOwnThreadNotOnTheCaller() throws Exception {
        LitertCancelToken token = new LitertCancelToken(new LitertStopper());
        java.util.concurrent.atomic.AtomicReference<Thread> stopThread = new java.util.concurrent.atomic.AtomicReference<>();
        token.onCancel(() -> stopThread.set(Thread.currentThread()));
        token.cancel();
        assertTrue(token.detach(2000));
        assertTrue(stopThread.get() != null);
        assertTrue("not the calling thread", stopThread.get() != Thread.currentThread());
    }

    @Test public void atMostOneStopIsInFlightPerStopper() throws Exception {
        LitertStopper stopper = new LitertStopper();
        LitertCancelToken first = new LitertCancelToken(stopper);
        LitertCancelToken second = new LitertCancelToken(stopper);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger secondStops = new AtomicInteger();
        first.onCancel(() -> { entered.countDown(); await(release); });
        second.onCancel(secondStops::incrementAndGet);
        first.cancel();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(stopper.isBusy());
        second.cancel();
        assertTrue(second.isCancelled());
        assertTrue("a refused stop never started and holds no thread", second.detach(100));
        assertEquals(0, secondStops.get());
        release.countDown();
        assertTrue(first.detach(2000));
        assertFalse("free again after the stop returned", stopper.isBusy());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
