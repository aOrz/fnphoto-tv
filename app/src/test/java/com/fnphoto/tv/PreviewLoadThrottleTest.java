package com.fnphoto.tv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class PreviewLoadThrottleTest {
    private static final class Driver {
        final PreviewLoadThrottle throttle = new PreviewLoadThrottle(300);
        final MainRequestScope screen = new MainRequestScope();
        final List<String> dispatched = new ArrayList<>();
        long due = Long.MAX_VALUE;
        long taskGeneration;

        void select(int index, long now) {
            advance(now);
            long delay = throttle.request(index, now);
            if (delay >= 0) {
                due = now + delay;
                taskGeneration = screen.generation();
            }
        }

        void advance(long now) {
            if (due <= now) {
                long runAt = due;
                due = Long.MAX_VALUE;
                if (screen.isCurrent(taskGeneration)) dispatched.add(throttle.take(runAt) + "@" + runAt);
            }
        }
    }

    @Test public void continuousScrollingLoadsLatestFocusEveryIntervalAndAfterStopping() {
        Driver driver = new Driver();
        for (int index = 0; index < 20; index++) driver.select(index, index * 100L);
        driver.advance(2200);
        assertEquals(Arrays.asList("0@0", "2@300", "5@600", "8@900", "11@1200",
                "14@1500", "17@1800", "19@2100"), driver.dispatched);
    }

    @Test public void selectionUpdatesDoNotMoveThePendingDeadline() {
        PreviewLoadThrottle throttle = new PreviewLoadThrottle(300);
        assertEquals(0, throttle.request(0, 0));
        assertEquals(0, throttle.take(0));
        assertEquals(200, throttle.request(1, 100));
        assertEquals(-1, throttle.request(2, 299));
        assertEquals(2, throttle.take(300));
        assertEquals(-1, throttle.take(300));
    }

    @Test public void restoredPositionReplacesInitialSelectionBeforeLeadingTaskRuns() {
        PreviewLoadThrottle throttle = new PreviewLoadThrottle(300);
        assertEquals(0, throttle.request(0, 0));
        assertEquals(-1, throttle.request(37, 0));
        assertEquals(37, throttle.take(0));
    }

    @Test public void idleSelectionIsImmediateAndLateDispatchDoesNotCauseCatchUpBursts() {
        PreviewLoadThrottle throttle = new PreviewLoadThrottle(300);
        assertEquals(0, throttle.request(0, 0));
        throttle.take(0);
        assertEquals(0, throttle.request(1, 1000));
        assertEquals(1, throttle.take(1500));
        assertEquals(299, throttle.request(2, 1501));
    }

    @Test public void screenResetRejectsOldTaskBeforeItCanConsumeNewSelection() {
        PreviewLoadThrottle throttle = new PreviewLoadThrottle(300);
        MainRequestScope screen = new MainRequestScope();
        long oldGeneration = screen.generation();
        throttle.request(3, 0);
        screen.next();
        throttle.reset();
        assertEquals(-1, throttle.take(0));
        assertEquals(0, throttle.request(28, 1));
        if (screen.isCurrent(oldGeneration)) throttle.take(1);
        assertFalse(screen.isCurrent(oldGeneration));
        assertEquals(28, throttle.take(1));
    }

    @Test public void invalidSelectionDoesNotReplacePendingFocus() {
        PreviewLoadThrottle throttle = new PreviewLoadThrottle(300);
        assertEquals(-1, throttle.request(-1, 0));
        assertEquals(-1, throttle.take(0));
        assertEquals(0, throttle.request(4, 0));
        assertEquals(-1, throttle.request(-1, 1));
        assertEquals(4, throttle.take(1));
    }
}
