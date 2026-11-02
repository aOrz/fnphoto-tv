package com.fnphoto.tv;

import org.junit.Test;

import static com.fnphoto.tv.YearMonthRange.Match.MATCH;
import static com.fnphoto.tv.YearMonthRange.Match.NO_MATCH;
import static com.fnphoto.tv.YearMonthRange.Match.UNKNOWN;
import static org.junit.Assert.*;

public class YearMonthRangeTest {
    @Test public void requestBoundsUseNasTextForTheWholeMonth() {
        YearMonthRange range = new YearMonthRange(2026, 10);
        assertEquals(2026, range.year);
        assertEquals(10, range.month);
        assertEquals("2026:10:01 00:00:00", range.startTime());
        assertEquals("2026:10:31 23:59:59", range.endTime());
        assertEquals("2026年10月", range.label());
    }

    @Test public void monthEndsRespectGregorianLeapCenturyRules() {
        assertEquals("1900:02:28 23:59:59", new YearMonthRange(1900, 2).endTime());
        assertEquals("2000:02:29 23:59:59", new YearMonthRange(2000, 2).endTime());
        assertEquals("2024:02:29 23:59:59", new YearMonthRange(2024, 2).endTime());
        assertEquals("2026:02:28 23:59:59", new YearMonthRange(2026, 2).endTime());
        assertEquals("2100:02:28 23:59:59", new YearMonthRange(2100, 2).endTime());
    }

    @Test public void thirtyDayMonthsDoNotIncludeTheFollowingMonth() {
        for (int month : new int[] {4, 6, 9, 11}) {
            YearMonthRange range = new YearMonthRange(2026, month);
            assertTrue(range.endTime().endsWith(":30 23:59:59"));
        }
    }

    @Test public void fullSupportedYearRangeIsZeroPaddedWithoutCalendarCutover() {
        assertEquals("0001:01:01 00:00:00", new YearMonthRange(1, 1).startTime());
        assertEquals("0001年01月", new YearMonthRange(1, 1).label());
        assertEquals("9999:12:31 23:59:59", new YearMonthRange(9999, 12).endTime());
        assertEquals(MATCH, new YearMonthRange(1582, 10).matchCaptureDate("1582-10-10 12:00:00"));
    }

    @Test public void invalidMonthConstructionIsRejected() {
        for (int[] input : new int[][] {{0, 1}, {-1, 1}, {10000, 1}, {2026, 0}, {2026, 13}}) {
            try {
                new YearMonthRange(input[0], input[1]);
                fail("Accepted invalid month");
            } catch (IllegalArgumentException expected) {
                // No lenient calendar normalization to a different month.
            }
        }
    }

    @Test public void sharedRangePreservesExistingContractBoundaries() {
        assertFalse(new YearMonthRange(1999, 12).isWithinSharedRange());
        assertTrue(new YearMonthRange(2000, 1).isWithinSharedRange());
        assertTrue(new YearMonthRange(2099, 12).isWithinSharedRange());
        assertFalse(new YearMonthRange(2100, 1).isWithinSharedRange());
    }

    @Test public void bothExistingDateSeparatorsClassifyFullCaptureDates() {
        YearMonthRange range = new YearMonthRange(2024, 2);
        assertEquals(MATCH, range.matchCaptureDate("2024-02-01 00:00:00"));
        assertEquals(MATCH, range.matchCaptureDate("2024:02:29 23:59:59"));
        assertEquals(NO_MATCH, range.matchCaptureDate("2024-01-31 23:59:59"));
        assertEquals(NO_MATCH, range.matchCaptureDate("2024:03:01 00:00:00"));
        assertEquals(NO_MATCH, range.matchCaptureDate("2020-02-29 12:00:00"));
    }

    @Test public void invalidDatesAreUnknownEvenOutsideTheSelectedMonth() {
        YearMonthRange range = new YearMonthRange(2026, 10);
        for (String date : new String[] {"2026-02-29 12:00:00", "2100-02-29 12:00:00",
                "2026-04-31 12:00:00", "2026-00-01 12:00:00", "2026-13-01 12:00:00",
                "2026-10-00 12:00:00", "2026-10-32 12:00:00", "0000-10-01 12:00:00"}) {
            assertEquals(date, UNKNOWN, range.matchCaptureDate(date));
        }
    }

    @Test public void invalidTimesCannotMatchByDatePrefix() {
        YearMonthRange range = new YearMonthRange(2026, 10);
        for (String date : new String[] {"2026-10-01 24:00:00", "2026-10-01 23:60:00",
                "2026-10-01 23:59:60", "2026-10-01 -1:00:00", "2026-10-01 12:0x:00"}) {
            assertEquals(date, UNKNOWN, range.matchCaptureDate(date));
        }
    }

    @Test public void missingAndPartialDatesRemainUnknown() {
        YearMonthRange range = new YearMonthRange(2026, 10);
        for (String date : new String[] {null, "", "2026-10", "2026-10-01", "2026:10:01",
                "2026-10-01 12:00", "2026-10-01 12:00:00 "}) {
            assertEquals(date, UNKNOWN, range.matchCaptureDate(date));
        }
    }

    @Test public void epochTimezoneAndFractionalValuesAreNotGuessed() {
        YearMonthRange range = new YearMonthRange(2026, 10);
        for (String date : new String[] {"1790812800", "1790812800000",
                "2026-10-01T00:00:00", "2026-10-01 00:00:00Z",
                "2026-10-01T00:00:00+08:00", "2026-10-01 00:00:00.000"}) {
            assertEquals(date, UNKNOWN, range.matchCaptureDate(date));
        }
    }

    @Test public void unsupportedSeparatorsAndNonAsciiDigitsRemainUnknown() {
        YearMonthRange range = new YearMonthRange(2026, 10);
        for (String date : new String[] {"2026/10/01 12:00:00", "2026-10:01 12:00:00",
                "2026-10-01 12-00-00", "2026-10-01\t12:00:00", "２０２６-10-01 12:00:00"}) {
            assertEquals(date, UNKNOWN, range.matchCaptureDate(date));
        }
    }
}
