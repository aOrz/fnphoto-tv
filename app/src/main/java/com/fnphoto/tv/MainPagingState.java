package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final Set<String> ids = new HashSet<>();
    private int offset;
    private boolean loading;
    private boolean hasMore = true;
    private boolean failed;
    private boolean stalled;

    MainPagingState(int pageSize) {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        this.pageSize = pageSize;
    }

    int begin() {
        if (loading || !hasMore) return -1;
        loading = true;
        failed = false;
        return offset;
    }

    List<Integer> accept(List<String> pageIds, Boolean serverHasNext) {
        if (!loading) throw new IllegalStateException("No page in flight");
        List<Integer> added = new ArrayList<>();
        for (int i = 0; i < pageIds.size(); i++) {
            String id = pageIds.get(i);
            if (id != null && ids.add(id)) added.add(i);
        }
        offset += pageIds.size();
        loading = false;
        failed = false;
        stalled = !pageIds.isEmpty() && added.isEmpty();
        hasMore = !pageIds.isEmpty() && !stalled
                && (serverHasNext != null ? serverHasNext : pageIds.size() >= pageSize);
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
}
