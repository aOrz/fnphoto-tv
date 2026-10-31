package com.fnphoto.tv;

import java.util.Locale;

/** Linear numeric scan; formatting occurs only for the two final API bounds. */
final class TimelineDateRange {
    private int first = Integer.MAX_VALUE;
    private int last = Integer.MIN_VALUE;

    boolean add(int year, int month, int day) {
        if (year < 1 || year > 9999 || month < 1 || month > 12) return false;
        int days;
        switch (month) {
            case 2:
                days = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28;
                break;
            case 4: case 6: case 9: case 11: days = 30; break;
            default: days = 31;
        }
        if (day < 1 || day > days) return false;
        int date = year * 10000 + month * 100 + day;
        first = Math.min(first, date);
        last = Math.max(last, date);
        return true;
    }

    boolean isEmpty() { return first > last; }
    String firstDate() { return isEmpty() ? null : format(first); }
    String lastDate() { return isEmpty() ? null : format(last); }

    private static String format(int date) {
        return String.format(Locale.US, "%04d:%02d:%02d", date / 10000, (date / 100) % 100, date % 100);
    }
}
