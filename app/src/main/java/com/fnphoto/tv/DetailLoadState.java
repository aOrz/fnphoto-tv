package com.fnphoto.tv;

/** Invalidates callbacks after navigation or destruction without retaining Android views. */
final class DetailLoadState {
    private long generation;
    private boolean closed;

    long next() { return ++generation; }
    long current() { return generation; }
    boolean isCurrent(long expected) { return !closed && expected == generation; }
    void close() { closed = true; generation++; }

    static boolean shouldPreload(MediaItem item) {
        return item != null && "photo".equals(item.getType())
                && item.getMediaUrl() != null && !item.getMediaUrl().isEmpty();
    }
}
