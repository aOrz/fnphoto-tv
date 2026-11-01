package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** UI-thread paging state. Offset counts server rows, not deduplicated display rows. */
final class MainPagingState {
    /** Each detail screen has a cursor distinct from the owner's network offset. */
    static final class DeliveryCursor {
        private int deliveredCount;
        DeliveryCursor(int count) { deliveredCount = count; }
        boolean hasPending(int ownerCount) { return ownerCount > deliveredCount; }
        void delivered(int count) { deliveredCount = count; }
    }

    private final int pageSize;
    // Raw rows in sparse scans can vastly outnumber retained matches. Keep only
    // recent-page IDs; the owning bounded window handles display deduplication.
    private final Map<String, Boolean> ids;
    private boolean invalidPage;
    private boolean offsetLimited;
    private int offset;
    private boolean loading;
    private boolean hasMore = true;
    private boolean failed;
    private boolean stalled;

    MainPagingState(int pageSize) { this(pageSize, 0); }

    MainPagingState(int pageSize, int initialOffset) {
        if (initialOffset < 0) throw new IllegalArgumentException("initialOffset");
        offset = initialOffset;
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        this.pageSize = pageSize;
        ids = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 2L * pageSize;
            }
        };
    }

    int begin() {
        if (loading || !hasMore) return -1;
        if (offset > Integer.MAX_VALUE - pageSize) {
            offsetLimited = true;
            hasMore = false;
            return -1;
        }
        loading = true;
        failed = false;
        invalidPage = false;
        return offset;
    }

    List<Integer> accept(List<String> pageIds, Boolean serverHasNext) {
        if (!loading) throw new IllegalStateException("No page in flight");
        List<Integer> added = new ArrayList<>();
        // Do not accept an ignored limit or wrap the signed API offset. Retry
        // remains at the same offset; no raw rows are silently skipped.
        if (pageIds == null || pageIds.size() > pageSize || pageIds.contains(null)
                || offset > Integer.MAX_VALUE - pageIds.size()) {
            loading = false;
            failed = true;
            invalidPage = true;
            return added;
        }
        for (int i = 0; i < pageIds.size(); i++) {
            String id = pageIds.get(i);
            if (id != null && ids.put(id, Boolean.TRUE) == null) added.add(i);
        }
        offset += pageIds.size();
        loading = false;
        failed = false;
        stalled = (!pageIds.isEmpty() && added.isEmpty())
                || (pageIds.isEmpty() && Boolean.TRUE.equals(serverHasNext));
        hasMore = !pageIds.isEmpty() && !stalled
                && !Boolean.FALSE.equals(serverHasNext);
        return added;
    }

    void fail() { loading = false; failed = true; }
    void cancel() { loading = false; }
    int getPageSize() { return pageSize; }
    int getOffset() { return offset; }
    boolean isLoading() { return loading; }
    boolean hasMore() { return hasMore; }
    boolean hasFailed() { return failed; }
    boolean isStalled() { return stalled; }
    boolean hasInvalidPage() { return invalidPage; }
    boolean isOffsetLimited() { return offsetLimited; }
    int retainedIdCount() { return ids.size(); }
}
