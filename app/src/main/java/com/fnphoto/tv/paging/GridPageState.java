package com.fnphoto.tv.paging;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Main-thread pagination state shared by grid screens; has no Android dependencies. */
public final class GridPageState {
    public static final class Request {
        public final int offset;
        private Request(int offset) { this.offset = offset; }
    }

    /** Tracks what a detail screen has received, independently of the server's final-page flag. */
    public static final class Delivery {
        private int deliveredCount;
        public Delivery(int initialCount) { deliveredCount = initialCount; }
        public boolean hasBuffered(int availableCount) { return availableCount > deliveredCount; }
        public boolean hasMore(int availableCount, boolean serverHasMore) {
            return hasBuffered(availableCount) || serverHasMore;
        }
        public void delivered(int count) { deliveredCount = count; }
    }

    private final int pageSize;
    private final Set<Integer> seen = new HashSet<>();
    private Request active;
    private int offset;
    private boolean more = true;
    private boolean failed;
    private boolean stalled;
    private boolean closed;

    public GridPageState(int pageSize) {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        this.pageSize = pageSize;
    }

    public void reset() {
        active = null;
        offset = 0;
        more = true;
        failed = false;
        stalled = false;
        closed = false;
        seen.clear();
    }

    public void close() { reset(); closed = true; }

    public Request begin(boolean explicitRetry) {
        if (closed || active != null || !more || (failed && !explicitRetry)) return null;
        failed = false;
        active = new Request(offset);
        return active;
    }

    public boolean isCurrent(Request request) { return !closed && request != null && active == request; }

    /** null means stale response. Flags identify new IDs, while offset advances by raw rows. */
    public boolean[] accept(Request request, List<Integer> ids, Boolean hasNext) {
        if (!isCurrent(request)) return null;
        boolean[] additions = new boolean[ids.size()];
        int added = 0;
        for (int i = 0; i < ids.size(); i++) {
            additions[i] = seen.add(ids.get(i));
            if (additions[i]) added++;
        }
        offset += ids.size();
        stalled = !ids.isEmpty() && added == 0;
        more = added > 0 && (hasNext != null ? hasNext : ids.size() >= pageSize);
        active = null;
        return additions;
    }

    public boolean fail(Request request) {
        if (!isCurrent(request)) return false;
        active = null;
        failed = true;
        return true;
    }

    public boolean isLoading() { return active != null; }
    public boolean hasMore() { return !closed && more; }
    public boolean hasError() { return failed; }
    public boolean isStalled() { return stalled; }
}
