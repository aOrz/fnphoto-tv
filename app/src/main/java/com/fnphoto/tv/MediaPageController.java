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
    void returnToList(int index) {
        if (!closed && source != null && index >= 0 && index < items.size()) source.onReturn(items.get(index));
    }
    int indexOfId(String id) {
        if (id == null) return -1;
        for (int i = 0; i < items.size(); i++) if (id.equals(items.get(i).getId())) return i;
        return -1;
    }
    boolean hasPrevious() { return !closed && source != null && source.isWindowed() && source.hasPrevious(); }
    boolean hasMore() {
        if (source != null && source.isWindowed()) return !closed && source.hasMore();
        return !closed && !exhausted && boundaryMessage == null
                && items.size() < MetadataLimits.MAX_ITEMS && source != null && source.hasMore();
    }
    String getBoundaryMessage() {
        if (boundaryMessage != null) return boundaryMessage;
        if (source != null && source.getBoundaryMessage() != null) return source.getBoundaryMessage();
        return items.size() >= MetadataLimits.MAX_ITEMS && source != null && !source.isWindowed() && source.hasMore()
                ? MetadataLimits.WINDOW_MESSAGE : null;
    }

    void ensurePrevious(int index, Callback callback) {
        if (closed || loading) return;
        if (index > 0) { callback.onReady(true); return; }
        if (!hasPrevious()) { callback.onReady(false); return; }
        ensureWindow(index, false, callback);
    }

    private void ensureWindow(int index, final boolean forward, Callback callback) {
        if (index < 0 || index >= items.size()) { callback.onFailure(); return; }
        final String anchor = items.get(index).getId();
        source.setAnchor(items.get(index));
        loading = true;
        pendingCallback = callback;
        MediaListHolder.PageCallback delivery = new MediaListHolder.PageCallback() {
            private boolean completed;
            private Callback take() {
                if (closed || completed) return null;
                completed = true;
                loading = false;
                Callback target = pendingCallback;
                pendingCallback = null;
                return target;
            }
            @Override public void onLoaded(List<MediaItem> incoming, boolean more) {
                Callback target = take();
                if (target == null) return;
                // Validate atomically: a malformed replacement must never evict the displayed media.
                if (incoming == null || incoming.size() > MetadataLimits.MAX_ITEMS) {
                    target.onFailure(); return;
                }
                Set<String> known = new HashSet<>();
                int anchorIndex = -1;
                for (int i = 0; i < incoming.size(); i++) {
                    MediaItem item = incoming.get(i);
                    if (item == null || item.getId() == null || !known.add(item.getId())) {
                        target.onFailure(); return;
                    }
                    if (anchor.equals(item.getId())) anchorIndex = i;
                }
                if (anchorIndex < 0) { target.onFailure(); return; }
                List<MediaItem> replacement = new ArrayList<>(incoming);
                items.clear();
                items.addAll(replacement);
                boolean available = forward ? anchorIndex + 1 < items.size() : anchorIndex > 0;
                if (!available && (forward ? more : source.hasPrevious())) {
                    target.onPaused("暂未找到更多匹配，请继续扫描");
                } else target.onReady(available);
            }
            @Override public void onFailure() {
                Callback target = take();
                if (target != null) target.onFailure();
            }
            @Override public void onPaused(String message) {
                Callback target = take();
                if (target != null) target.onPaused(message);
            }
        };
        if (forward) source.loadMore(delivery); else source.loadPrevious(delivery);
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
        if (source.isWindowed()) { ensureWindow(index, true, callback); return; }
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
