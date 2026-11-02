package com.fnphoto.tv;

/** Shared display policy for ordinary and recipient-shared album cards. */
final class AlbumDisplay {
    private AlbumDisplay() {}

    static boolean isAlbum(String type) {
        return "album".equals(type) || "shared_album".equals(type);
    }

    static String title(String type, String raw) {
        if (!isAlbum(type) || hasText(raw)) return raw;
        return "shared_album".equals(type) ? "未命名共享相册" : "未命名相册";
    }

    private static boolean hasText(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true;
        }
        return false;
    }
}
