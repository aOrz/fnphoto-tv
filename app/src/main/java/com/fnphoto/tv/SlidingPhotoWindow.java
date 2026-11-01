package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Bounded resident metadata for an offset-paged photo wall. This does not own
 * requests or infer server exhaustion. Empty filtered pages still advance raw
 * offsets; discarded pages can be fetched again without retaining bookmarks.
 * All methods are UI-thread owned. Returned lists never share backing storage.
 */
final class SlidingPhotoWindow {
    static final int DEFAULT_MAX_RAW_ROWS = 560;
    static final int DEFAULT_MAX_PAGES = 16;
    static final long DEFAULT_MAX_ESTIMATED_BYTES = 8L * 1024 * 1024;

    static final class Change {
        final int removedItems;
        final int removedPages;
        final boolean trimmed;
        Change(int items, int pages) {
            removedItems = items;
            removedPages = pages;
            trimmed = pages > 0;
        }
    }

    private static final class Page {
        final int start, end;
        final List<MediaItem> items;
        final long estimatedBytes;
        Page(int start, int end, List<MediaItem> items) {
            this.start = start;
            this.end = end;
            this.items = new ArrayList<>(items);
            this.estimatedBytes = estimateItems(items) + 64;
        }
    }

    private final LinkedList<Page> pages = new LinkedList<>();
    private final int maxRawRows, maxPages;
    private final long maxEstimatedBytes;
    private long retainedBytes;
    private long previousBytes;
    private int emptyOffset;
    private boolean more = true;
    private int previousStart = -1, previousEnd, previousCursor;
    private final List<MediaItem> previousItems = new ArrayList<>();

    static final class Request {
        final int offset, limit;
        Request(int offset, int limit) { this.offset = offset; this.limit = limit; }
    }

    Change append(int offset, int rawCount, List<MediaItem> items, boolean hasMore) {
        if (previousStart >= 0) throw new IllegalStateException("backward range pending");
        Change change = addForward(offset, rawCount, items);
        more = hasMore;
        return change;
    }

    Request beginPrevious(int pageSize) {
        if (pageSize < 1 || pageSize > maxRawRows) throw new IllegalArgumentException("page size");
        if (previousStart >= 0) return previousRequest();
        if (!hasPrevious()) return null;
        previousEnd = firstRawOffset();
        previousStart = Math.max(0, previousEnd - pageSize);
        previousCursor = previousStart;
        previousItems.clear();
        previousBytes = 0;
        return previousRequest();
    }

    Request previousRequest() {
        return previousStart < 0 ? null : new Request(previousCursor, previousEnd - previousCursor);
    }

    /** On failure do not call accept: previousRequest remains the exact retry. */
    boolean acceptPrevious(int offset, int rawCount, List<MediaItem> items) {
        int end = checkedEnd(offset, rawCount, items);
        if (previousStart < 0 || offset != previousCursor || rawCount == 0 || end > previousEnd)
            throw new IllegalArgumentException("invalid backward response");
        long incomingBytes = estimateItems(items);
        if (previousBytes + incomingBytes + 64 > maxEstimatedBytes)
            throw new IllegalArgumentException("backward metadata budget");
        previousItems.addAll(items);
        previousBytes += incomingBytes;
        previousCursor = end;
        if (end < previousEnd) return false;
        Change change = addBackwardRange(previousStart, previousEnd, previousItems);
        // Evicted right-side content remains fetchable even after seeing server EOF.
        if (change.trimmed) more = true;
        cancelPrevious();
        return true;
    }

    void cancelPrevious() {
        previousStart = -1;
        previousItems.clear();
        previousBytes = 0;
    }
    boolean hasMore() { return more; }
    int firstOffset() { return firstRawOffset(); }
    int nextOffset() { return endRawOffset(); }
    List<MediaItem> snapshot() { return getItems(); }
    int pendingItemCount() { return previousItems.size(); }


    SlidingPhotoWindow() { this(DEFAULT_MAX_RAW_ROWS, DEFAULT_MAX_PAGES); }
    SlidingPhotoWindow(int maxRawRows, int maxPages) {
        this(maxRawRows, maxPages, DEFAULT_MAX_ESTIMATED_BYTES);
    }
    SlidingPhotoWindow(int maxRawRows, int maxPages, long maxEstimatedBytes) {
        if (maxRawRows < 1 || maxPages < 1) throw new IllegalArgumentException("window budget");
        if (maxEstimatedBytes < 64) throw new IllegalArgumentException("metadata budget");
        this.maxEstimatedBytes = maxEstimatedBytes;
        this.maxRawRows = maxRawRows;
        this.maxPages = maxPages;
    }

    /** Contiguous raw server page. The caller advances by rawCount, not matches. */
    Change addForward(int start, int rawCount, List<MediaItem> items) {
        int end = checkedEnd(start, rawCount, items);
        if (start != endRawOffset()) throw new IllegalArgumentException("noncontiguous forward page");
        if (rawCount == 0) return new Change(0, 0);
        Page page = new Page(start, end, items);
        pages.addLast(page);
        retainedBytes += page.estimatedBytes;
        return trim(true);
    }

    /**
     * Prepend a fully fetched raw interval. For short responses, the caller
     * accumulates the bounded interval until end == firstRawOffset(), preserving
     * the same in-flight cursor on failure. Never splice an unfilled raw gap.
     */
    Change addBackwardRange(int start, int end, List<MediaItem> items) {
        if (end < start) throw new IllegalArgumentException("reversed interval");
        checkedEnd(start, end - start, items);
        if (end != firstRawOffset()) throw new IllegalArgumentException("noncontiguous backward range");
        if (end == start) return new Change(0, 0);
        Page page = new Page(start, end, items);
        pages.addFirst(page);
        retainedBytes += page.estimatedBytes;
        return trim(false);
    }

    private int checkedEnd(int start, int count, List<MediaItem> items) {
        if (start < 0 || count < 0 || count > maxRawRows
                || (long) start + count > Integer.MAX_VALUE || items == null || items.size() > count)
            throw new IllegalArgumentException("invalid raw page");
        for (MediaItem item : items) {
            if (item == null || item.getId() == null)
                throw new IllegalArgumentException("invalid media item");
        }
        if (estimateItems(items) + 64 > maxEstimatedBytes)
            throw new IllegalArgumentException("page metadata budget");
        return start + count;
    }

    private Change trim(boolean fromHead) {
        int removedItems = 0, removedPages = 0;
        while (pages.size() > maxPages || retainedRawRows() > maxRawRows
                || retainedBytes > maxEstimatedBytes) {
            Page removed = fromHead ? pages.removeFirst() : pages.removeLast();
            retainedBytes -= removed.estimatedBytes;
            removedItems += removed.items.size();
            removedPages++;
        }
        return new Change(removedItems, removedPages);
    }

    /** Conservative accounting, not an Android heap measurement; shared strings count twice. */
    private static long estimateItems(List<MediaItem> items) {
        long bytes = 0;
        for (MediaItem item : items) {
            bytes += 192 + stringBytes(item.getId()) + stringBytes(item.getTitle())
                    + stringBytes(item.getType()) + stringBytes(item.getThumbnailUrl())
                    + stringBytes(item.getMediaUrl()) + stringBytes(item.getDateStr());
            if (item.getPreviewThumbUrls() != null) {
                bytes += 32;
                for (String url : item.getPreviewThumbUrls()) bytes += 8 + stringBytes(url);
            }
        }
        return bytes;
    }
    private static long stringBytes(String value) { return value == null ? 0 : 48L + 2L * value.length(); }
    long retainedEstimatedBytes() { return retainedBytes; }
    long pendingEstimatedBytes() { return previousBytes; }

    List<MediaItem> getItems() {
        List<MediaItem> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Page page : pages) {
            for (MediaItem item : page.items) if (ids.add(item.getId())) result.add(item);
        }
        return result;
    }

    static boolean hasRawNeighbor(List<MediaItem> rows, int raw, boolean previous) {
        if (raw < 0) return false;
        for (MediaItem item : rows) {
            int candidate = item.getBrowseRawOffset();
            if (candidate >= 0 && (previous ? candidate < raw : candidate > raw)) return true;
        }
        return false;
    }

    /** Merge only one evicted displayed card in raw order, even after changing direction. */
    static List<MediaItem> withAnchor(List<MediaItem> rows, MediaItem anchor) {
        List<MediaItem> result = new ArrayList<>(rows);
        if (anchor == null) return result;
        for (MediaItem item : result) if (item.getId().equals(anchor.getId())) return result;
        if (anchor.getBrowseRawOffset() < 0) return result;
        int position = 0;
        while (position < result.size()
                && result.get(position).getBrowseRawOffset() < anchor.getBrowseRawOffset()) position++;
        result.add(position, anchor);
        return result;
    }

    int indexOf(String id) {
        List<MediaItem> items = getItems();
        for (int i = 0; i < items.size(); i++) if (items.get(i).getId().equals(id)) return i;
        return -1;
    }
    int firstRawOffset() { return pages.isEmpty() ? emptyOffset : pages.getFirst().start; }
    int endRawOffset() { return pages.isEmpty() ? emptyOffset : pages.getLast().end; }
    int retainedRawRows() { return endRawOffset() - firstRawOffset(); }
    int pageCount() { return pages.size(); }
    boolean hasPrevious() { return firstRawOffset() > 0; }
    void clear() { reset(0); }
    void reset(int offset) {
        if (offset < 0) throw new IllegalArgumentException("offset");
        pages.clear();
        retainedBytes = 0;
        cancelPrevious();
        more = true;
        emptyOffset = offset;
    }
}
