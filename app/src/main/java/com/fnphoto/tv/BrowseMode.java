package com.fnphoto.tv;

/** Stable persisted IDs, never menu positions or translated labels. */
enum BrowseMode {
    GALLERY("gallery", "图库"), FOLDERS("folders", "文件夹"),
    FAVORITES("favorites", "收藏"), RECENT("recent", "最近添加"),
    ALBUMS("albums", "相册"), PEOPLE("people", "人物"), PLACES("places", "地点"),
    PHOTOS("photo", "照片"), VIDEOS("video", "视频");

    final String id;
    final String title;
    BrowseMode(String id, String title) { this.id = id; this.title = title; }

    String menuAction() { return this == PHOTOS || this == VIDEOS ? "media_types" : id; }

    static BrowseMode fromId(String id) {
        for (BrowseMode mode : values()) if (mode.id.equals(id)) return mode;
        return null; // shared/tags/smart/search/settings are deliberately not destinations.
    }
}
