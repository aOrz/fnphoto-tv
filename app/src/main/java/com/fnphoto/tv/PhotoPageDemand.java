package com.fnphoto.tv;

/** One bounded paging episode owned by an actual focused photo or a fixed footer anchor. */
final class PhotoPageDemand {
    private final int maxPages;
    private int remainingPages;
    private int photoIndex = -1;
    private int footerAnchor = -1;

    PhotoPageDemand(int maxPages) {
        if (maxPages < 1) throw new IllegalArgumentException("maxPages");
        this.maxPages = maxPages;
        reset();
    }

    boolean selectPhoto(int index, boolean inFlight) {
        if (index < 0 || (footerAnchor < 0 && photoIndex == index)) return false;
        photoIndex = index;
        footerAnchor = -1;
        remainingPages = maxPages - (inFlight ? 1 : 0);
        return true;
    }

    boolean selectFooter(int displayedCount, boolean inFlight) {
        if (displayedCount < 0 || footerAnchor >= 0) return false;
        photoIndex = -1;
        footerAnchor = displayedCount;
        remainingPages = maxPages - (inFlight ? 1 : 0);
        return true;
    }

    boolean shouldLoad(int displayedCount, int distance) {
        return canStartRequest() && needsMore(displayedCount, distance);
    }

    boolean needsMore(int displayedCount, int distance) {
        if (footerAnchor >= 0) return displayedCount <= footerAnchor;
        return photoIndex >= 0 && photoIndex < displayedCount
                && photoIndex >= displayedCount - distance;
    }

    boolean canStartRequest() { return remainingPages > 0; }

    void onRequestStarted() {
        if (!canStartRequest()) throw new IllegalStateException("Paging episode exhausted");
        remainingPages--;
    }

    int footerHandoffIndex(int displayedCount) {
        return footerAnchor >= 0 && displayedCount > footerAnchor ? footerAnchor : -1;
    }

    /** Programmatic focus movement must not grant another automatic scan budget. */
    void handoffToPhoto(int index) {
        if (index < 0) throw new IllegalArgumentException("index");
        photoIndex = index;
        footerAnchor = -1;
    }

    void explicitContinue(int displayedCount) {
        remainingPages = maxPages;
        if (footerAnchor >= 0) footerAnchor = displayedCount;
    }

    boolean isPaused() { return !canStartRequest(); }
    int remainingPages() { return remainingPages; }

    void reset() {
        photoIndex = -1;
        footerAnchor = -1;
        remainingPages = maxPages;
    }
}
