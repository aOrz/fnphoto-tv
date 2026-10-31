package com.fnphoto.tv;

/** Local selection only: do not invent an unsupported NAS date-filter parameter. */
final class PhotoSelection {
    private PhotoSelection() {}

    static boolean includes(boolean favoritesOnly, int isCollect) {
        return !favoritesOnly || isCollect == 1;
    }

    static boolean matchesCategory(String selectedCategory, String category) {
        return selectedCategory == null || selectedCategory.equals(category);
    }

    static String cacheKey(boolean favoritesOnly, String date) {
        return (favoritesOnly ? "favorites:" : "gallery:") + date;
    }
}
