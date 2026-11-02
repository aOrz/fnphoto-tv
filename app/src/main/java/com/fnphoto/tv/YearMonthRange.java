package com.fnphoto.tv;

import java.util.Locale;

/** A NAS calendar month, without guessing a server timezone or an epoch unit. */
final class YearMonthRange {
    enum Match { MATCH, NO_MATCH, UNKNOWN }

    final int year;
    final int month;

    YearMonthRange(int year, int month) {
        if (year < 1 || year > 9999 || month < 1 || month > 12) {
            throw new IllegalArgumentException("Invalid calendar month");
        }
        this.year = year;
        this.month = month;
    }

    String startTime() {
        return String.format(Locale.US, "%04d:%02d:01 00:00:00", year, month);
    }

    String endTime() {
        return String.format(Locale.US, "%04d:%02d:%02d 23:59:59", year, month,
                daysInMonth(year, month));
    }

    String label() { return String.format(Locale.US, "%04d年%02d月", year, month); }

    /** The existing shared-album request contract only covers these years. */
    boolean isWithinSharedRange() { return year >= 2000 && year <= 2099; }

    /**
     * Classify only photoDateTime. dateTime may mean upload time and is not a fallback.
     * Accept the full second-precision text already used by the client/fixtures;
     * missing, partial, timezone-bearing and epoch values remain explicitly unknown.
     */
    Match matchCaptureDate(String photoDateTime) {
        if (photoDateTime == null || photoDateTime.length() != 19) return Match.UNKNOWN;
        char dateSeparator = photoDateTime.charAt(4);
        if ((dateSeparator != '-' && dateSeparator != ':')
                || photoDateTime.charAt(7) != dateSeparator
                || photoDateTime.charAt(10) != ' '
                || photoDateTime.charAt(13) != ':'
                || photoDateTime.charAt(16) != ':') return Match.UNKNOWN;

        int captureYear = decimal(photoDateTime, 0, 4);
        int captureMonth = decimal(photoDateTime, 5, 7);
        int captureDay = decimal(photoDateTime, 8, 10);
        int hour = decimal(photoDateTime, 11, 13);
        int minute = decimal(photoDateTime, 14, 16);
        int second = decimal(photoDateTime, 17, 19);
        if (captureYear < 1 || captureYear > 9999 || captureMonth < 1 || captureMonth > 12
                || captureDay < 1 || captureDay > daysInMonth(captureYear, captureMonth)
                || hour < 0 || hour > 23 || minute < 0 || minute > 59
                || second < 0 || second > 59) return Match.UNKNOWN;
        return captureYear == year && captureMonth == month ? Match.MATCH : Match.NO_MATCH;
    }

    private static int decimal(String value, int start, int end) {
        int result = 0;
        for (int i = start; i < end; i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') return -1;
            result = result * 10 + digit - '0';
        }
        return result;
    }

    private static int daysInMonth(int year, int month) {
        switch (month) {
            case 2:
                return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }
}
