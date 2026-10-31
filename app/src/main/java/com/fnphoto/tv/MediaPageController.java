package com.fnphoto.tv;

import com.fnphoto.tv.paging.MetadataLimits;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Main-thread-owned, Android-free boundary paging for photo navigation and slideshows. */
final class MediaPageController {
    interface Callback {
        void onReady(boolean nextAvailable);
        void onFailure();
        default void onPaused(String message) { onFailure(); }
    }

    private final List<MediaItem> items = new ArrayList<>();
    private final MediaListHolder.PageSource source;
    private boolean loading;
    private boolean closed;
    private boolean exhausted;
    private Callback pendingCallback;
    private String boundaryMessage;

    MediaPageController(List<MediaItem> initial, MediaListHolder.PageSource source) {
        if (initial != null) {
            items.addAll(initial.subList(0, Math.min(initial.size(), MetadataLimits.MAX_ITEMS)));
            if (initial.size() > MetadataLimits.MAX_ITEMS) boundaryMessage = MetadataLimits.WINDOW_MESSAGE;
        }
        this.source = source;
    }

    List<MediaItem> getItems() { return items; }
    boolean isLoading() { return loading; }
    boolean hasMore() {
        return !closed && !exhausted && boundaryMessage == null
                && items.size() < MetadataLimits.MAX_ITEMS && source != null && source.hasMore();
    }
    String getBoundaryMessage() {
        if (boundaryMessage != null) return boundaryMessage;
        if (source != null && source.getBoundaryMessage() != null) return source.getBoundaryMessage();
        return items.size() >= MetadataLimits.MAX_ITEMS && source != null && source.hasMore()
                ? MetadataLimits.WINDOW_MESSAGE : null;
    }

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
                        if (item == null || known.contains(item.getId())) continue;
                        if (items.size() >= MetadataLimits.MAX_ITEMS) {
                            boundaryMessage = MetadataLimits.WINDOW_MESSAGE;
                            break;
                        }
                        known.add(item.getId());
                        items.add(item);
                    }
                }
                exhausted = !hasMore || items.size() == previousSize;
                if (hasMore && items.size() == previousSize && boundaryMessage == null)
                    boundaryMessage = "服务器未返回新的内容，已停止；请返回列表重试，并非确认全部加载";
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

            @Override public void onPaused(String message) {
                if (closed || completed) return;
                completed = true;
                loading = false;
                Callback target = pendingCallback;
                pendingCallback = null;
                // Do not mark the source exhausted: a later user action gets a fresh budget.
                if (target != null) target.onPaused(message);
            }
        });
    }

    void close() {
        closed = true;
        loading = false;
        pendingCallback = null;
    }
}
