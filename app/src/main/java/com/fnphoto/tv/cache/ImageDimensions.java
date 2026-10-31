package com.fnphoto.tv.cache;

/** Fallback dimensions for custom targets whose view has not been measured yet. */
public final class ImageDimensions {
    private ImageDimensions() {}
    public static int safeWidth(int width) { return width > 0 ? width : 320; }
    public static int safeHeight(int height) { return height > 0 ? height : 180; }
}
