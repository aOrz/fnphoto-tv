package com.fnphoto.tv;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MainRequestScopeTest {
    @Test public void navigationCancelsAndRejectsOldResponse() {
        MainRequestScope scope = new MainRequestScope();
        Object call = new Object(); long old = scope.generation();
        AtomicInteger canceled = new AtomicInteger();
        scope.track(call, canceled::incrementAndGet); scope.next();
        assertEquals(1, canceled.get()); assertFalse(scope.complete(call, old));
    }
    @Test public void currentResponseCompletesExactlyOnce() {
        MainRequestScope scope = new MainRequestScope(); Object call = new Object();
        scope.track(call, () -> fail("Completed call must not be canceled"));
        assertTrue(scope.complete(call, scope.generation()));
        assertFalse(scope.complete(call, scope.generation())); scope.next();
    }
    @Test public void cancelCallbackCannotPaintNewScreen() {
        MainRequestScope scope = new MainRequestScope(); Object call = new Object();
        long generation = scope.generation();
        scope.track(call, () -> assertFalse(scope.complete(call, generation)));
        scope.next();
    }
    @Test public void destroyedScopeRejectsLateResponsesAndNewRequests() {
        MainRequestScope scope = new MainRequestScope(); Object call = new Object();
        AtomicInteger canceled = new AtomicInteger(); long generation = scope.generation();
        scope.track(call, canceled::incrementAndGet); scope.close();
        assertFalse(scope.isCurrent(generation));
        scope.track(new Object(), canceled::incrementAndGet);
        assertEquals(2, canceled.get()); assertFalse(scope.isCurrent(scope.generation()));
    }
    @Test public void newNavigationRequestUnaffectedByOldResponse() {
        MainRequestScope scope = new MainRequestScope(); Object old = new Object(), fresh = new Object();
        long first = scope.generation(); scope.track(old, () -> {}); scope.next();
        scope.track(fresh, () -> {});
        assertFalse(scope.complete(old, first)); assertTrue(scope.complete(fresh, scope.generation()));
    }
}
