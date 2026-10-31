package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Main-thread-owned, Android-free boundary paging for photo navigation and slideshows. */
final class MediaPageController {
    interface Callback {
        void onReady(boolean nextAvailable);
        void onFailure();
    }

    private final List<MediaItem> items = new ArrayList<>();
    private final MediaListHolder.PageSource source;
    private boolean loading;
    private boolean closed;
    private boolean exhausted;
    private Callback pendingCallback;

    MediaPageController(List<MediaItem> initial, MediaListHolder.PageSource source) {
        if (initial != null) items.addAll(initial);
        this.source = source;
    }

    List<MediaItem> getItems() { return items; }
    boolean isLoading() { return loading; }
    boolean hasMore() { return !closed && !exhausted && source != null && source.hasMore(); }

    void ensureNext(final int index, final Callback callback) {
        if (closed || loading) return;
        if (index + 1 < items.size()) {
            callback.onReady(true);
            return;
        }
        if (!hasMore()) {
            callback.onReady(false);
            return;
        }
        loading = true;
        pendingCallback = callback;
        source.loadMore(new MediaListHolder.PageCallback() {
            private boolean completed;

            @Override public void onLoaded(List<MediaItem> incoming, boolean hasMore) {
                if (closed || completed) return;
                completed = true;
                loading = false;
                Set<String> known = new HashSet<>();
                for (MediaItem item : items) known.add(item.getId());
                int previousSize = items.size();
                if (incoming != null) {
                    for (MediaItem item : incoming) {
                        if (item != null && known.add(item.getId())) items.add(item);
                    }
                }
                exhausted = !hasMore || items.size() == previousSize;
                Callback target = pendingCallback;
                pendingCallback = null;
                if (target != null) target.onReady(index + 1 < items.size());
            }

            @Override public void onFailure() {
                if (closed || completed) return;
                completed = true;
                loading = false;
                Callback target = pendingCallback;
                pendingCallback = null;
                if (target != null) target.onFailure();
            }
        });
    }

    void close() {
        closed = true;
        loading = false;
        pendingCallback = null;
    }
}
