package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.List;

public class MediaListHolder {
    private static List<MediaItem> sMediaList;
    private static PageSource sPageSource;

    /** The list screen owns paging; detail screens request a page only at its loaded boundary. */
    public interface PageSource {
        boolean hasMore();
        void loadMore(PageCallback callback);
    }

    public interface PageCallback {
        void onLoaded(List<MediaItem> items, boolean hasMore);
        void onFailure();
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
