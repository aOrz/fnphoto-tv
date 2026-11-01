package com.fnphoto.tv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** JVM state tests, not Android focus/lifecycle instrumentation. */
public class MediaWindowControllerTest {
    private static MediaItem photo(int id) {
        return new MediaItem("" + id, "synthetic", "photo", "thumb", "photo");
    }
    private static List<MediaItem> range(int start, int end) {
        List<MediaItem> rows = new ArrayList<>();
        for (int i = start; i < end; i++) rows.add(photo(i));
        return rows;
    }
    private static class Source implements MediaListHolder.PageSource {
        boolean more = true, previous = true;
        int requests;
        String anchor;
        MediaItem returned;
        MediaListHolder.PageCallback pending;
        @Override public void setAnchorId(String id) { anchor = id; }
        @Override public void onReturn(MediaItem item) { returned = item; }
        @Override public boolean isWindowed() { return true; }
        @Override public boolean hasMore() { return more; }
        @Override public boolean hasPrevious() { return previous; }
        @Override public void loadMore(MediaListHolder.PageCallback callback) { requests++; pending = callback; }
        @Override public void loadPrevious(MediaListHolder.PageCallback callback) { requests++; pending = callback; }
    }
    private static class Result implements MediaPageController.Callback {
        int ready, failed, paused;
        boolean available;
        @Override public void onReady(boolean value) { ready++; available = value; }
        @Override public void onFailure() { failed++; }
        @Override public void onPaused(String message) { paused++; }
    }

    @Test public void replacementCanNavigateBeyond1200WithoutAccumulatingMetadata() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(range(0, 100), source);
        for (int start = 0; start < 15000; start += 50) {
            String anchor = "" + (start + 99);
            Result result = new Result();
            controller.ensureNext(controller.indexOfId(anchor), result);
            assertEquals(anchor, source.anchor);
            source.pending.onLoaded(range(start + 50, start + 150), true);
            assertEquals(100, controller.getItems().size());
            assertEquals(49, controller.indexOfId(anchor));
            assertTrue(result.available);
            assertEquals(1, result.ready);
            assertTrue(controller.hasMore());
        }
    }

    @Test public void prependReplacesTailAndRemapsAnchor() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(range(100, 200), source);
        Result result = new Result();
        controller.ensurePrevious(0, result);
        assertEquals("100", source.anchor);
        source.pending.onLoaded(range(50, 150), true);
        assertEquals(50, controller.indexOfId("100"));
        assertEquals(-1, controller.indexOfId("199"));
        assertTrue(result.available);
    }

    @Test public void invalidReplacementDoesNotEvictCurrentMediaAndCanRetry() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(range(0, 100), source);
        Result result = new Result();
        controller.ensureNext(99, result);
        source.pending.onLoaded(range(100, 200), true);
        assertEquals(1, result.failed);
        assertEquals(99, controller.indexOfId("99"));
        controller.ensureNext(99, result);
        source.pending.onLoaded(Arrays.asList(photo(99), photo(100), photo(100)), true);
        assertEquals(2, result.failed);
        assertEquals(100, controller.getItems().size());
        controller.ensureNext(99, result);
        source.pending.onLoaded(range(0, 2000), true);
        assertEquals(3, result.failed);
        assertEquals(100, controller.getItems().size());
    }

    @Test public void emptyScanPausesRatherThanRecursingAndDuplicateCallbacksAreIgnored() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(range(0, 100), source);
        Result result = new Result();
        controller.ensureNext(99, result);
        controller.ensurePrevious(0, result);
        assertEquals(1, source.requests);
        source.pending.onLoaded(range(50, 100), true);
        source.pending.onFailure();
        assertEquals(1, result.paused);
        assertEquals(0, result.failed);
        assertTrue(controller.hasMore());
        assertFalse(controller.isLoading());
    }

    @Test public void terminalForwardDoesNotDisableBackwardAndCloseIgnoresLateReplacement() {
        Source source = new Source();
        source.more = false;
        MediaPageController controller = new MediaPageController(range(100, 200), source);
        assertFalse(controller.hasMore());
        assertTrue(controller.hasPrevious());
        Result result = new Result();
        controller.ensurePrevious(0, result);
        controller.close();
        assertEquals("100", source.anchor);
        source.pending.onLoaded(range(50, 150), true);
        assertEquals(0, result.ready);
        assertEquals(0, controller.indexOfId("100"));
        assertFalse(controller.hasPrevious());
    }
    @Test public void rawOrderedPinNeverTurnsNewerSparseMatchIntoPrevious() {
        MediaItem anchor = photo(1);
        anchor.setBrowseRawOffset(10);
        MediaItem later = photo(2);
        later.setBrowseRawOffset(5000);
        List<MediaItem> rows = SlidingPhotoWindow.withAnchor(Arrays.asList(later), anchor);
        assertEquals("1", rows.get(0).getId());
        assertEquals("2", rows.get(1).getId());
        Source source = new Source();
        MediaPageController controller = new MediaPageController(rows, source);
        Result result = new Result();
        controller.ensurePrevious(0, result);
        source.pending.onLoaded(rows, true);
        assertEquals(1, result.paused);
        assertFalse(result.available);
        assertEquals(0, controller.indexOfId("1"));
        MediaItem earlier = photo(0);
        earlier.setBrowseRawOffset(5);
        controller.ensurePrevious(0, result);
        source.pending.onLoaded(SlidingPhotoWindow.withAnchor(Arrays.asList(earlier), anchor), true);
        assertTrue(result.available);
        assertEquals(1, controller.indexOfId("1"));
    }

    @Test public void rawOrderedPinKeepsNewerAnchorAfterOlderWindow() {
        MediaItem anchor = photo(2);
        anchor.setBrowseRawOffset(5000);
        MediaItem earlier = photo(1);
        earlier.setBrowseRawOffset(10);
        List<MediaItem> rows = SlidingPhotoWindow.withAnchor(Arrays.asList(earlier), anchor);
        assertEquals("1", rows.get(0).getId());
        assertEquals("2", rows.get(1).getId());
        assertEquals(2, SlidingPhotoWindow.withAnchor(rows, anchor).size());
    }

    @Test public void returnHandsExactDisplayedModelBackBeforeSourceIsClosed() {
        Source source = new Source();
        MediaItem displayed = photo(42);
        displayed.setBrowseRawOffset(10000);
        MediaPageController controller = new MediaPageController(Arrays.asList(displayed), source);
        controller.returnToList(0);
        assertSame(displayed, source.returned);
        source.returned = null;
        controller.close();
        controller.returnToList(0);
        assertNull(source.returned);
    }

}
