package com.fnphoto.tv;

/** Pixel geometry shared by the TV walls. The viewport is the measured container, not the display. */
public final class PhotoWallLayout {
    public final int columns;
    public final int cardWidthPx;
    public final int mediaHeightPx;
    public final int dateHeightPx;

    private PhotoWallLayout(int columns, int cardWidthPx) {
        this.columns = columns;
        this.cardWidthPx = cardWidthPx;
        mediaHeightPx = Math.max(1, Math.round(cardWidthPx * 9f / 16f));
        dateHeightPx = Math.max(1, Math.round(cardWidthPx * 3f / 4f));
    }

    public static PhotoWallLayout calculate(int viewportWidthPx, int paddingLeftPx,
            int paddingRightPx, int gapPx, float density) {
        return calculate(viewportWidthPx, paddingLeftPx, paddingRightPx, gapPx, density, 144, 6);
    }

    public static PhotoWallLayout calculate(int viewportWidthPx, int paddingLeftPx,
            int paddingRightPx, int gapPx, float density, int minCardWidthDp, int maxColumns) {
        if (!(density > 0) || Float.isInfinite(density) || minCardWidthDp < 1 || maxColumns < 1) {
            throw new IllegalArgumentException("Invalid wall dimensions");
        }
        int available = Math.max(1, viewportWidthPx - Math.max(0, paddingLeftPx)
                - Math.max(0, paddingRightPx));
        int gap = Math.max(0, gapPx);
        int minimum = Math.max(1, Math.round(minCardWidthDp * density));
        int columns = Math.max(1, Math.min(maxColumns, (available + gap) / (minimum + gap)));
        // A bounded width also bounds the existing Leanback 1.14x focus zoom and bitmap demand.
        int width = Math.min(Math.max(1, (available - (columns - 1) * gap) / columns),
                Math.max(1, Math.round(320 * density)));
        return new PhotoWallLayout(columns, width);
    }
}
