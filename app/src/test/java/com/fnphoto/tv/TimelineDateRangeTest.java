package com.fnphoto.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class TimelineDateRangeTest {
    @Test public void unsortedDatesProduceTheEntireRange() {
        TimelineDateRange range = new TimelineDateRange();
        assertTrue(range.isEmpty());
        assertNull(range.firstDate());
        assertTrue(range.add(2026, 10, 31));
        assertTrue(range.add(1998, 1, 2));
        assertTrue(range.add(2030, 12, 30));
        assertEquals("1998:01:02", range.firstDate());
        assertEquals("2030:12:30", range.lastDate());
    }

    @Test public void invalidDatesDoNotSilentlyChangeOrTruncateTheRange() {
        TimelineDateRange range = new TimelineDateRange();
        assertFalse(range.add(0, 1, 1));
        assertFalse(range.add(10000, 1, 1));
        assertFalse(range.add(2026, 0, 1));
        assertFalse(range.add(2026, 13, 1));
        assertFalse(range.add(2026, 4, 31));
        assertFalse(range.add(2026, 1, 0));
        assertFalse(range.add(2100, 2, 29));
        assertTrue(range.isEmpty());
        assertTrue(range.add(2000, 2, 29));
        assertTrue(range.add(2024, 2, 29));
        assertEquals("2000:02:29", range.firstDate());
        assertEquals("2024:02:29", range.lastDate());
    }

    @Test public void largeTimelineDoesNotDiscardOldDatesForTheDisplayWindowLimit() {
        TimelineDateRange range = new TimelineDateRange();
        for (int i = 0; i < 100000; i++) assertTrue(range.add(2026, 10, 31));
        assertTrue(range.add(1, 1, 1));
        assertTrue(range.add(9999, 12, 31));
        assertEquals("0001:01:01", range.firstDate());
        assertEquals("9999:12:31", range.lastDate());
    }
}
