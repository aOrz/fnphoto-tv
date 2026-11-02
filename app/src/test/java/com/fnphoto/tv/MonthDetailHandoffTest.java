package com.fnphoto.tv;

import org.junit.Test;
import static org.junit.Assert.*;

/** Callback-episode tests only; actual Activity ordering still requires device coverage. */
public class MonthDetailHandoffTest {
    @Test public void ordinaryWallBackgroundStopsWork() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        handoff.wallResumed();
        assertTrue(handoff.shouldPauseWallStop());
        assertFalse(handoff.hasPendingReturn());
    }

    @Test public void openingDetailOwnsTheWallStopEvenIfItArrivesAfterBack() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        handoff.wallResumed();
        MonthDetailHandoff.DetailSession detail = handoff.openDetail();
        assertFalse(handoff.shouldPauseWallStop());
        assertFalse(handoff.hasPendingReturn());
        // Reproduced API19 sequence: return callback, then old wall STOP, then wall RESUME.
        detail.returnToWall();
        assertFalse(handoff.shouldPauseWallStop());
        assertTrue(handoff.hasPendingReturn());
        handoff.wallResumed();
        assertTrue(handoff.shouldPauseWallStop());
        assertFalse(handoff.hasPendingReturn());
        assertFalse(detail.shouldPauseStop(true));
    }

    @Test public void backCoveredBeforeWallResumesStillStopsAtDetailStop() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        MonthDetailHandoff.DetailSession detail = handoff.openDetail();
        detail.returnToWall();
        // Settings becomes foreground before the original wall resumes. Its older stop
        // remains detail-owned, but the detail's own stop must cancel the pending work.
        assertFalse(handoff.shouldPauseWallStop());
        assertTrue(detail.shouldPauseStop(false));
        assertTrue(handoff.hasPendingReturn());
    }

    @Test public void backgroundingWallAfterReturnDoesNotKeepDetailExemption() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        MonthDetailHandoff.DetailSession detail = handoff.openDetail();
        detail.returnToWall();
        handoff.wallResumed();
        // Main resumed, then another Activity covers it before the old detail stops.
        assertTrue(handoff.shouldPauseWallStop());
        assertTrue(detail.shouldPauseStop(false));
    }

    @Test public void coveringDetailWithoutReturningAlwaysStopsItsWork() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        MonthDetailHandoff.DetailSession detail = handoff.openDetail();
        assertFalse(handoff.shouldPauseWallStop());
        assertTrue(detail.shouldPauseStop(false));
        assertTrue(detail.shouldPauseStop(true));
    }

    @Test public void oldDetailStopCannotCancelANewerDetailSession() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        MonthDetailHandoff.DetailSession old = handoff.openDetail();
        old.returnToWall();
        handoff.wallResumed();
        MonthDetailHandoff.DetailSession next = handoff.openDetail();
        assertFalse(old.isCurrent());
        assertTrue(next.isCurrent());
        assertFalse(old.shouldPauseStop(false));
        assertFalse(handoff.hasPendingReturn());
        assertFalse(handoff.shouldPauseWallStop());
        assertTrue(next.shouldPauseStop(false));
    }

    @Test public void lateReturnFromOldDetailCannotMarkANewSessionReturning() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        MonthDetailHandoff.DetailSession old = handoff.openDetail();
        handoff.wallResumed();
        MonthDetailHandoff.DetailSession next = handoff.openDetail();
        old.returnToWall();
        assertFalse(handoff.hasPendingReturn());
        assertTrue(next.shouldPauseStop(true));
    }

    @Test public void queryResetInvalidatesOldDetailOwnershipAndReturnIntent() {
        MonthDetailHandoff handoff = new MonthDetailHandoff();
        MonthDetailHandoff.DetailSession old = handoff.openDetail();
        old.returnToWall();
        handoff.reset();
        assertTrue(handoff.shouldPauseWallStop());
        assertFalse(handoff.hasPendingReturn());
        assertFalse(old.isCurrent());
        assertFalse(old.shouldPauseStop(false));
        old.returnToWall();
        assertFalse(handoff.hasPendingReturn());
    }
}
