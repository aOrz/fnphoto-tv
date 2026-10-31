package com.fnphoto.tv;

/** Bounds the raw-page work performed for one focus/explicit scan action. */
final class SparseScanBudget {
    private final int maxPages;
    private int remaining;
    private boolean paused;

    SparseScanBudget(int maxPages) {
        if (maxPages < 1) throw new IllegalArgumentException("maxPages");
        this.maxPages = maxPages;
    }

    boolean start(boolean explicit) {
        if (paused && !explicit) return false;
        remaining = maxPages;
        paused = false;
        return true;
    }

    /** True only while the same action may scan another zero-match page. */
    boolean acceptPage(boolean addedMatches, boolean hasMore) {
        if (remaining <= 0) throw new IllegalStateException("No scan budget");
        remaining--;
        paused = !addedMatches && hasMore && remaining == 0;
        return !addedMatches && hasMore && remaining > 0;
    }

    boolean isPaused() { return paused; }
    void reset() { remaining = 0; paused = false; }
}
