package com.fnphoto.tv;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PhotoSelectionTest {
    @Test public void favoritesExcludeUncollectedRowsButGalleryKeepsThem() {
        assertFalse(PhotoSelection.includes(true, 0));
        assertTrue(PhotoSelection.includes(true, 1));
        assertFalse(PhotoSelection.includes(true, -1));
        assertTrue(PhotoSelection.includes(false, 0));
        assertTrue(PhotoSelection.includes(false, 1));
    }

    @Test public void mediaTypeFilterDoesNotMixPhotosAndVideos() {
        assertTrue(PhotoSelection.matchesCategory("video", "video"));
        assertFalse(PhotoSelection.matchesCategory("video", "photo"));
        assertFalse(PhotoSelection.matchesCategory("photo", "video"));
        assertFalse(PhotoSelection.matchesCategory("photo", null));
        assertTrue(PhotoSelection.matchesCategory(null, "photo"));
        assertTrue(PhotoSelection.matchesCategory(null, "video"));
    }

    @Test public void galleryPreviewCannotLeakIntoFavoritesCache() {
        assertNotEquals(PhotoSelection.cacheKey(false, "2026-10-31"),
                PhotoSelection.cacheKey(true, "2026-10-31"));
        assertEquals(PhotoSelection.cacheKey(true, "2026-10-31"),
                PhotoSelection.cacheKey(true, "2026-10-31"));
    }

    @Test public void filteredPagesStillAdvanceByRawServerRows() {
        MainPagingState paging = new MainPagingState(2);
        assertEquals(0, paging.begin());
        int displayed = 0;
        int[] flags = {0, 0};
        for (int index : paging.accept(Arrays.asList("1", "2"), true)) {
            if (PhotoSelection.includes(true, flags[index])) displayed++;
        }
        assertEquals(0, displayed);
        assertTrue(paging.hasMore());
        assertEquals(2, paging.begin());
        for (int index : paging.accept(Arrays.asList("3"), false)) {
            if (PhotoSelection.includes(true, 1)) displayed++;
        }
        assertEquals(1, displayed);
        assertEquals(3, paging.getOffset());
        assertFalse(paging.hasMore());
    }
}
