package com.fnphoto.tv.api;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TokenRefreshGateTest {
    @Test public void sameTokenFailureBacksOffButDifferentTokenDoesNot() {
        long[] now = {0};
        TokenRefreshGate gate = new TokenRefreshGate(() -> now[0]);
        AtomicInteger calls = new AtomicInteger();
        assertFalse(gate.run("old", () -> { calls.incrementAndGet(); return false; }));
        assertFalse(gate.run("old", () -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
        assertTrue(gate.run("new", () -> { calls.incrementAndGet(); return true; }));
        assertEquals(2, calls.get());
        now[0] = 2000;
        assertTrue(gate.run("new", () -> { calls.incrementAndGet(); return true; }));
        assertEquals(3, calls.get());
    }

    @Test public void concurrentRequestsShareOneRefresh() throws Exception {
        TokenRefreshGate gate = new TokenRefreshGate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        Thread first = new Thread(() -> {
            if (gate.run("token", () -> {
                calls.incrementAndGet();
                started.countDown();
                try { return release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { return false; }
            })) successes.incrementAndGet();
        });
        Thread second = new Thread(() -> {
            if (gate.run("token", () -> { calls.incrementAndGet(); return true; })) successes.incrementAndGet();
        });
        first.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        second.start();
        release.countDown();
        first.join(3000);
        second.join(3000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(1, calls.get());
        assertEquals(2, successes.get());
    }

    @Test public void exceptionDoesNotLeaveGateLocked() {
        long[] now = {0};
        TokenRefreshGate gate = new TokenRefreshGate(() -> now[0]);
        try { gate.run("token", () -> { throw new IllegalStateException("test"); }); fail(); }
        catch (IllegalStateException expected) { }
        now[0] = 2000;
        assertTrue(gate.run("token", () -> true));
    }
}
