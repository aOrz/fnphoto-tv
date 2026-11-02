package com.fnphoto.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class AlbumDisplayTest {
    @Test public void onlyOrdinaryAndSharedAlbumsUseAlbumPolicy() {
        assertTrue(AlbumDisplay.isAlbum("album"));
        assertTrue(AlbumDisplay.isAlbum("shared_album"));
        for (String type : new String[] {null, "", "photo", "video", "date", "person_date",
                "person", "place", "folder", "geo_retry"}) {
            assertFalse(AlbumDisplay.isAlbum(type));
        }
    }

    @Test public void missingOrBlankAlbumNamesHaveTypeSpecificFallbacks() {
        for (String name : new String[] {null, "", " ", "\t\r\n", "\u3000", "\u00a0", " \u3000\t\u00a0"}) {
            assertEquals("未命名相册", AlbumDisplay.title("album", name));
            assertEquals("未命名共享相册", AlbumDisplay.title("shared_album", name));
        }
    }

    @Test public void meaningfulAlbumNamesArePreservedExactly() {
        for (String type : new String[] {"album", "shared_album"}) {
            assertEquals(" 家庭 · 2026 ", AlbumDisplay.title(type, " 家庭 · 2026 "));
            assertEquals("旅行📷", AlbumDisplay.title(type, "旅行📷"));
            assertEquals("\u3000家庭\u00a0", AlbumDisplay.title(type, "\u3000家庭\u00a0"));
        }
    }

    @Test public void nonAlbumTitlesRemainUnchanged() {
        for (String type : new String[] {null, "photo", "date", "folder", "person"}) {
            assertNull(AlbumDisplay.title(type, null));
            assertEquals("", AlbumDisplay.title(type, ""));
            assertEquals(" \t ", AlbumDisplay.title(type, " \t "));
            assertEquals("\u3000\u00a0", AlbumDisplay.title(type, "\u3000\u00a0"));
            assertEquals("Original", AlbumDisplay.title(type, "Original"));
        }
    }
}
