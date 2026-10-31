package com.fnphoto.tv.paging;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Main-thread pagination state shared by grid screens; has no Android dependencies. */
public final class GridPageState {
    public static final class Request {
        public final int offset;
        public final int limit;
        private Request(int offset, int limit) { this.offset = offset; this.limit = limit; }
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
    private final int windowLimit;
    private int windowRows;
    private boolean invalidPage;
    private boolean offsetLimit;
    private final Set<Integer> seen = new HashSet<>();
    private Request active;
    private int offset;
    private boolean more = true;
    private boolean failed;
    private boolean stalled;
    private boolean closed;

    public GridPageState(int pageSize) {
        this(pageSize, MetadataLimits.MAX_ITEMS);
    }

    public GridPageState(int pageSize, int windowLimit) {
        if (pageSize <= 0 || windowLimit <= 0) throw new IllegalArgumentException("Limits must be positive");
        this.pageSize = pageSize;
        this.windowLimit = windowLimit;
    }

    public void reset() {
        active = null;
        offset = 0;
        windowRows = 0;
        invalidPage = false;
        offsetLimit = false;
        more = true;
        failed = false;
        stalled = false;
        closed = false;
        seen.clear();
    }

    public void close() { reset(); closed = true; }

    public Request begin(boolean explicitRetry) {
        if (closed || active != null || !hasMore() || (failed && !explicitRetry)) return null;
        failed = false;
        invalidPage = false;
        int limit = Math.min(pageSize, Math.min(windowLimit - windowRows, Integer.MAX_VALUE - offset));
        if (limit <= 0) { offsetLimit = true; return null; }
        active = new Request(offset, limit);
        return active;
    }

    public boolean isCurrent(Request request) { return !closed && request != null && active == request; }

    /** null means stale response. Flags identify new IDs, while offset advances by raw rows. */
    public boolean[] accept(Request request, List<Integer> ids, Boolean hasNext) {
        if (!isCurrent(request)) return null;
        // Reject malformed/oversized pages atomically: never skip unseen raw rows.
        if (ids == null || ids.size() > request.limit || ids.contains(null)
                || (long) offset + ids.size() > Integer.MAX_VALUE) {
            reject(request);
            return null;
        }
        boolean[] additions = new boolean[ids.size()];
        int added = 0;
        for (int i = 0; i < ids.size(); i++) {
            additions[i] = seen.add(ids.get(i));
            if (additions[i]) added++;
        }
        offset += ids.size();
        windowRows += ids.size();
        stalled = (!ids.isEmpty() && added == 0) || (ids.isEmpty() && Boolean.TRUE.equals(hasNext));
        // A limit is a maximum, not a promise that every non-final page is full.
        // Missing metadata requires an empty-page probe, not early truncation.
        more = added > 0 && !Boolean.FALSE.equals(hasNext);
        offsetLimit = more && offset == Integer.MAX_VALUE;
        active = null;
        return additions;
    }

    public boolean reject(Request request) {
        if (!fail(request)) return false;
        invalidPage = true;
        return true;
    }

    public boolean fail(Request request) {
        if (!isCurrent(request)) return false;
        active = null;
        failed = true;
        return true;
    }

    public boolean isLoading() { return active != null; }
    /** Explicit list-screen action only. Detail/slideshow must not silently discard its window. */
    public boolean nextWindow() {
        if (!isWindowFull() || active != null || closed) return false;
        seen.clear();
        windowRows = 0;
        failed = false;
        invalidPage = false;
        stalled = false;
        return true;
    }

    public boolean hasMore() { return !closed && more && !isWindowFull() && !offsetLimit; }
    public boolean isWindowFull() { return !closed && more && !offsetLimit && windowRows >= windowLimit; }
    public boolean isInvalidPage() { return invalidPage; }
    public boolean isOffsetLimit() { return offsetLimit; }
    public int getWindowLimit() { return windowLimit; }
    public int getWindowRows() { return windowRows; }
    public int getSeenCount() { return seen.size(); }
    public int getOffset() { return offset; }
    public String boundaryMessage() {
        if (isWindowFull()) return "本批已达 " + windowLimit + " 条原始记录，请返回列表按确定查看下一批";
        if (offsetLimit) return "已达服务器分页位置上限，请缩小查询范围";
        if (stalled) return "服务器分页未前进，结果可能不完整；请返回重试或缩小范围";
        return null;
    }
    public boolean hasError() { return failed; }
    public boolean isStalled() { return stalled; }
}
