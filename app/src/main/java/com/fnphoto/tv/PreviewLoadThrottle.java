package com.fnphoto.tv;

/** Main-thread selection throttle: one pending dispatch always uses the newest focus. */
final class PreviewLoadThrottle {
    private final long intervalMillis;
    private boolean hasDispatched;
    private long lastDispatchMillis;
    private boolean scheduled;
    private int latestIndex = -1;

    PreviewLoadThrottle(long intervalMillis) {
        if (intervalMillis <= 0) throw new IllegalArgumentException("interval must be positive");
        this.intervalMillis = intervalMillis;
    }

    /** Delay for a new task, or -1 when the existing task will use this selection. */
    long request(int index, long nowMillis) {
        if (index < 0) return -1;
        latestIndex = index;
        if (scheduled) return -1;
        scheduled = true;
        return hasDispatched ? Math.max(0, intervalMillis - (nowMillis - lastDispatchMillis)) : 0;
    }

    /** Called by the scheduled task, after its owning screen/generation has been checked. */
    int take(long nowMillis) {
        if (!scheduled) return -1;
        scheduled = false;
        hasDispatched = true;
        lastDispatchMillis = nowMillis;
        return latestIndex;
    }

    void reset() {
        scheduled = false;
        hasDispatched = false;
        latestIndex = -1;
    }
}
