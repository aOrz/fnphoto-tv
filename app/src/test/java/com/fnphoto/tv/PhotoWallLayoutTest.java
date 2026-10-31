package com.fnphoto.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class PhotoWallLayoutTest {
    @Test public void commonTvResolutionsAndDisplayScalesFitEveryCardAndFocus() {
        // Physical width and densityDpi, including display-size settings on the same panel.
        int[][] displays = {
                {1280, 160}, {1280, 213}, {1280, 240},
                {1920, 240}, {1920, 320}, {1920, 400},
                {3840, 320}, {3840, 480}, {3840, 640}
        };
        for (int[] display : displays) {
            float density = display[1] / 160f;
            int padding = Math.round(72 * density);
            int gap = Math.round(12 * density);
            PhotoWallLayout layout = PhotoWallLayout.calculate(display[0], padding, padding, gap, density);
            assertWholeRowFits(layout, display[0], padding, padding, gap);
            assertFocusFits(layout, display[0], padding, gap, 48 * density);
        }
    }

    @Test public void largerDisplaySizeReducesColumnsInsteadOfRequiringHorizontalScrolling() {
        PhotoWallLayout normal = PhotoWallLayout.calculate(1920, 108, 108, 18, 1.5f);
        PhotoWallLayout enlarged = PhotoWallLayout.calculate(1920, 180, 180, 30, 2.5f);
        assertEquals(6, normal.columns);
        assertEquals(4, enlarged.columns);
        assertTrue(enlarged.columns < normal.columns);
        assertWholeRowFits(enlarged, 1920, 180, 180, 30);
    }

    @Test public void fractionalDensityAndUnevenInsetsDoNotAccumulateRoundingOverflow() {
        float[] densities = {0.75f, 1f, 1.33125f, 1.5f, 2f, 2.625f, 3f, 4f};
        for (float density : densities) {
            int left = Math.round(72 * density);
            int right = left + 3;
            int gap = Math.round(12 * density) + 1;
            for (int width = left + right + 1; width <= 4099; width += 37) {
                PhotoWallLayout layout = PhotoWallLayout.calculate(width, left, right, gap, density);
                assertWholeRowFits(layout, width, left, right, gap);
                assertFocusFits(layout, width, left, gap, 48 * density);
            }
        }
    }

    @Test public void aNarrowViewportGetsOnePositiveCardRatherThanAMinimumWidthOverflow() {
        PhotoWallLayout layout = PhotoWallLayout.calculate(200, 72, 72, 12, 1f);
        assertEquals(1, layout.columns);
        assertTrue(layout.cardWidthPx < 144);
        assertWholeRowFits(layout, 200, 72, 72, 12);
        assertFocusFits(layout, 200, 72, 12, 48);
    }

    @Test public void unmeasuredOrFullyPaddedViewsHaveSafePlaceholderDimensions() {
        for (int width : new int[] {-1, 0, 100, 144}) {
            PhotoWallLayout layout = PhotoWallLayout.calculate(width, 72, 72, 12, 1f);
            assertEquals(1, layout.columns);
            assertEquals(1, layout.cardWidthPx);
            assertEquals(1, layout.mediaHeightPx);
            assertEquals(1, layout.dateHeightPx);
        }
        // No fit claim is possible until the container has positive content width.
    }

    @Test public void cardCapBoundsFocusZoomOnUnusuallyWideDisplays() {
        PhotoWallLayout layout = PhotoWallLayout.calculate(8192, 72, 72, 12, 1f);
        assertEquals(6, layout.columns);
        assertEquals(320, layout.cardWidthPx);
        assertWholeRowFits(layout, 8192, 72, 72, 12);
        assertFocusFits(layout, 8192, 72, 12, 48);
    }

    @Test public void mediaAndDateCardsKeepTheirAspectRatioWithPixelRounding() {
        PhotoWallLayout layout = PhotoWallLayout.calculate(1365, 96, 97, 16, 1.33125f);
        assertTrue(Math.abs(layout.mediaHeightPx - layout.cardWidthPx * 9d / 16d) <= 0.5);
        assertTrue(Math.abs(layout.dateHeightPx - layout.cardWidthPx * 3d / 4d) <= 0.5);
        assertTrue(layout.dateHeightPx > layout.mediaHeightPx);
    }

    @Test public void folderCardsReserveSpaceForNamesAndRespectTheFourColumnLimit() {
        PhotoWallLayout normal = PhotoWallLayout.calculate(1280, 56, 56, 0, 1f, 240, 4);
        PhotoWallLayout scaled = PhotoWallLayout.calculate(1920, 112, 112, 0, 2f, 240, 4);
        assertEquals(4, normal.columns);
        assertEquals(3, scaled.columns);
        assertTrue(normal.cardWidthPx >= 240);
        assertTrue(scaled.cardWidthPx >= 480);
        assertWholeRowFits(normal, 1280, 56, 56, 0);
        assertWholeRowFits(scaled, 1920, 112, 112, 0);
    }

    @Test public void invalidDensityOrColumnPolicyIsRejected() {
        for (float density : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PhotoWallLayout.calculate(1920, 72, 72, 12, density));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PhotoWallLayout.calculate(1920, 72, 72, 12, 1, 0, 6));
        assertThrows(IllegalArgumentException.class,
                () -> PhotoWallLayout.calculate(1920, 72, 72, 12, 1, 144, 0));
    }

    private static void assertWholeRowFits(PhotoWallLayout layout, int width,
            int left, int right, int gap) {
        assertTrue(layout.columns >= 1 && layout.columns <= 6);
        assertTrue(layout.cardWidthPx > 0);
        assertTrue(layout.mediaHeightPx > 0);
        assertTrue(layout.dateHeightPx > 0);
        long previousRight = left - (long) gap;
        for (int column = 0; column < layout.columns; column++) {
            long cardLeft = left + column * (long) (layout.cardWidthPx + gap);
            long cardRight = cardLeft + layout.cardWidthPx;
            assertTrue("card starts before left inset", cardLeft >= left);
            assertTrue("card overlaps previous cell", cardLeft >= previousRight + gap);
            assertTrue("card exceeds right inset at width " + width, cardRight <= width - right);
            previousRight = cardRight;
        }
    }

    private static void assertFocusFits(PhotoWallLayout layout, int width,
            int left, int gap, float safeInset) {
        // Leanback's existing MEDIUM zoom is 1.14x, centered on the card.
        double extra = layout.cardWidthPx * 0.14 / 2;
        for (int column = 0; column < layout.columns; column++) {
            double cardLeft = left + column * (double) (layout.cardWidthPx + gap);
            assertTrue("focused card crosses safe left edge", cardLeft - extra >= safeInset);
            assertTrue("focused card crosses safe right edge",
                    cardLeft + layout.cardWidthPx + extra <= width - safeInset);
        }
    }
}
