package com.fnphoto.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class MonthReturnPointTest {
    @Test public void restoreUsesOriginalQueryCoordinateNotMonthOffset() {
        MonthReturnPoint point = point("album", 9, 0, "738", 735);
        assertEquals(665, point.restoreStart(35));
        assertEquals(735, point.rawOffset);
        assertEquals("738", point.mediaId);
        assertEquals(9, point.albumId);
        // A month result starts at zero and never mutates the saved coordinate.
        MainPagingState month = new MainPagingState(35);
        assertEquals(0, month.begin());
        month.accept(java.util.Arrays.asList("738"), false);
        assertEquals(735, point.rawOffset);
    }

    @Test public void sourceAndLocalFiltersSurviveReturnPoint() {
        MonthReturnPoint p = new MonthReturnPoint("person", "2024-02-12", "start", "end",
                "video", "Person", 0, 83, "5", 90);
        assertEquals("person", p.mode);
        assertEquals("2024-02-12", p.date);
        assertEquals("start", p.rangeStart);
        assertEquals("end", p.rangeEnd);
        assertEquals("video", p.category);
        assertEquals(83, p.personId);
        assertEquals("Person", p.title);
    }

    @Test public void restoreBoundsAtBeginningAndDoesNotRetainMediaSnapshots() {
        assertEquals(0, point("date", 0, 0, "1", 1).restoreStart(35));
        assertEquals(0, point("favorites", 0, 0, null, -1).rawOffset);
        for (java.lang.reflect.Field f : MonthReturnPoint.class.getDeclaredFields()) {
            assertTrue(f.getType() == String.class || f.getType() == int.class);
            assertTrue(java.lang.reflect.Modifier.isFinal(f.getModifiers()));
        }
    }

    private MonthReturnPoint point(String mode, int album, int person, String id, int offset) {
        return new MonthReturnPoint(mode, "2024-01-01", null, null, null,
                "Original source", album, person, id, offset);
    }
}
