package com.fnphoto.tv;

/** One query-local coordinate; never retains a media list or a chain of bookmarks. */
final class MonthReturnPoint {
    final String mode, date, rangeStart, rangeEnd, category, title, mediaId;
    final int albumId, personId, rawOffset;

    MonthReturnPoint(String mode, String date, String rangeStart, String rangeEnd,
            String category, String title, int albumId, int personId, String mediaId, int rawOffset) {
        this.mode = mode;
        this.date = date;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.category = category;
        this.title = title;
        this.albumId = albumId;
        this.personId = personId;
        this.mediaId = mediaId;
        this.rawOffset = Math.max(0, rawOffset);
    }

    int restoreStart(int pageSize) { return Math.max(0, rawOffset - 2 * pageSize); }
}
