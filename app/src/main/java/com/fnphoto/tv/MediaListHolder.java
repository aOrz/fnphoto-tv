package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.List;

public class MediaListHolder {
    private static List<MediaItem> sMediaList;
    private static PageSource sPageSource;

    /** The list screen owns paging; detail screens request a page only at its loaded boundary. */
    public interface PageSource {
        boolean hasMore();
        /** Sliding sources deliver replacement snapshots, retaining the requested anchor ID. */
        default boolean isWindowed() { return false; }
        /** Pin the currently displayed stable ID before a directional window request. */
        default void setAnchorId(String id) { }
        default void setAnchor(MediaItem item) { setAnchorId(item.getId()); }
        /** Return the exact displayed model even if its owner window has already moved. */
        default void onReturn(MediaItem item) { }
        default boolean hasPrevious() { return false; }
        default void loadPrevious(PageCallback callback) { callback.onFailure(); }
        void loadMore(PageCallback callback);
        /** Explicit whole-range restart; false means a resident window is not a full range. */
        default boolean supportsRestart() { return false; }
        /** Delivers a replacement starting at the actual range start, not the resident window. */
        default void restart(PageCallback callback) {
            callback.onPaused("当前来源不能可靠回到列表开头，请返回列表重新开始");
        }
        /** A window/protocol boundary must not be presented as the end of the library. */
        default String getBoundaryMessage() { return null; }
    }

    public interface PageCallback {
        void onLoaded(List<MediaItem> items, boolean hasMore);
        void onFailure();
        /** A finite scan found no match yet; the user may explicitly continue. */
        default void onPaused(String message) { onFailure(); }
    }

    public static void set(List<MediaItem> list) {
        set(list, null);
    }

    public static void set(List<MediaItem> list, PageSource pageSource) {
        sMediaList = list != null ? new ArrayList<>(list) : null;
        sPageSource = pageSource;
    }

    public static List<MediaItem> get() {
        return sMediaList;
    }

    public static PageSource getPageSource() {
        return sPageSource;
    }

    public static void clear() {
        sMediaList = null;
        sPageSource = null;
    }
}
