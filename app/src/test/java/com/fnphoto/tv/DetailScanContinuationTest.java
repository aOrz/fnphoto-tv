package com.fnphoto.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class DetailScanContinuationTest {
    @Test public void forwardSparseGapBeyondManyResidentWindowsNeverRestartsAtZero() {
        DetailScanContinuation scan = new DetailScanContinuation();
        int cursor = 0;
        int resets = 0;
        for (int action = 0; cursor < 10000 && action < 100; action++) {
            // Each explicit action scans five 35-row pages and pauses. Raw 0's
            // resident page is gone after 560 rows, but that gap was searched.
            if (scan.shouldRelocate("A", 0, false, cursor > 560)) { cursor = 0; resets++; }
            cursor += 175;
        }
        assertTrue(cursor >= 10000);
        assertEquals(0, resets);
    }

    @Test public void reverseSparseGapPreservesFrontierAcrossPausesAndRetry() {
        DetailScanContinuation scan = new DetailScanContinuation();
        assertFalse(scan.shouldRelocate("B", 10000, true, false));
        for (int cursor = 10000; cursor > 0; cursor -= 175) {
            assertFalse(scan.shouldRelocate("B", 10000, true, cursor < 9440));
            // A failed request retries at exactly the same frontier.
            assertFalse(scan.shouldRelocate("B", 10000, true, cursor < 9440));
        }
    }

    @Test public void changedDirectionAnchorOrRawCoordinateRequiresKnownRebase() {
        DetailScanContinuation scan = new DetailScanContinuation();
        assertFalse(scan.shouldRelocate("A", 0, false, false));
        assertFalse(scan.shouldRelocate("A", 0, false, true));
        assertTrue(scan.shouldRelocate("A", 0, true, true));
        assertFalse(scan.shouldRelocate("A", 0, true, true));
        assertTrue(scan.shouldRelocate("B", 10000, true, true));
        assertTrue(scan.shouldRelocate("B", 10001, true, true));
        assertFalse(scan.shouldRelocate("B", 10001, true, true));
    }

    @Test public void firstUnknownGapRebasesOnceAndNeverGuessesLegacyOffsets() {
        DetailScanContinuation scan = new DetailScanContinuation();
        assertTrue(scan.shouldRelocate("A", 100, true, true));
        assertFalse(scan.shouldRelocate("A", 100, true, true));
        assertFalse(scan.shouldRelocate("legacy", -1, true, true));
    }
}
