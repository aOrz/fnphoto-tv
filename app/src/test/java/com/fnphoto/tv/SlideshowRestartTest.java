package com.fnphoto.tv;

import com.fnphoto.tv.paging.MetadataLimits;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Whole-range restart is distinct from moving to index zero of a sliding snapshot. */
public class SlideshowRestartTest {
    private static MediaItem item(String id) { return new MediaItem(id, id, "photo", "t", "m"); }
    private static class Source implements MediaListHolder.PageSource {
        boolean supported = true;
        String boundary;
        int calls;
        MediaListHolder.PageCallback pending;
        @Override public boolean isWindowed() { return true; }
        @Override public boolean hasMore() { return false; }
        @Override public boolean supportsRestart() { return supported; }
        @Override public String getBoundaryMessage() { return boundary; }
        @Override public void loadMore(MediaListHolder.PageCallback callback) { fail("not forward paging"); }
        @Override public void restart(MediaListHolder.PageCallback callback) { calls++; pending = callback; }
    }
    private static class Result implements MediaPageController.Callback {
        int ready, failed, paused;
        boolean available;
        @Override public void onReady(boolean value) { ready++; available = value; }
        @Override public void onFailure() { failed++; }
        @Override public void onPaused(String message) { assertNotNull(message); paused++; }
    }
    private static MediaPageController controller(Source source) {
        return new MediaPageController(Arrays.asList(item("last-window-1"), item("last")), source);
    }

    @Test public void restartsActualRangeNotLastResidentWindowAndKeepsListIdentity() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        List<MediaItem> reference = controller.getItems();
        Result result = new Result();
        controller.restart(result);
        assertTrue(controller.isLoading());
        source.pending.onLoaded(Arrays.asList(item("first"), item("second")), true);
        assertTrue(result.available);
        assertEquals("last-window-1", reference.get(0).getId());
        assertTrue(controller.commitRestart());
        assertSame(reference, controller.getItems());
        assertEquals("first", reference.get(0).getId());
        assertFalse(controller.isLoading());
    }

    @Test public void pauseBeforeDeliveryCanLeaveOriginalSnapshotUncommitted() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        controller.restart(new Result());
        source.pending.onLoaded(Collections.singletonList(item("first")), true);
        // Activity rejects late navigation intent; it does not call commitRestart.
        assertEquals("last", controller.getItems().get(1).getId());
        controller.restart(new Result());
        assertFalse(controller.commitRestart());
        source.pending.onLoaded(Collections.singletonList(item("new-first")), true);
        assertTrue(controller.commitRestart());
        assertEquals("new-first", controller.getItems().get(0).getId());
    }

    @Test public void onlyOneRestartInFlight() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        controller.restart(new Result());
        controller.restart(new Result());
        assertEquals(1, source.calls);
    }

    @Test public void duplicateResponseCannotReplaceStagedFirstResponse() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.restart(result);
        source.pending.onLoaded(Collections.singletonList(item("first")), false);
        source.pending.onLoaded(Collections.singletonList(item("duplicate")), false);
        source.pending.onFailure();
        assertEquals(1, result.ready);
        assertEquals(0, result.failed);
        assertTrue(controller.commitRestart());
        assertEquals("first", controller.getItems().get(0).getId());
    }

    @Test public void closeInvalidatesPendingDeliveryAndCommit() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.restart(result);
        controller.close();
        source.pending.onLoaded(Collections.singletonList(item("first")), false);
        assertEquals(0, result.ready);
        assertFalse(controller.commitRestart());
        assertEquals(2, controller.getItems().size());
    }

    @Test public void completeFiniteNoSourceCanRestartWithoutNetwork() {
        MediaPageController controller = new MediaPageController(Collections.singletonList(item("one")), null);
        Result result = new Result();
        assertNull(controller.getRestartUnavailableMessage());
        controller.restart(result);
        assertTrue(result.available);
        assertTrue(controller.commitRestart());
    }

    @Test public void unsupportedSourceNeverLoopsResidentWindow() {
        Source source = new Source(); source.supported = false;
        MediaPageController controller = controller(source);
        Result result = new Result();
        assertNotNull(controller.getRestartUnavailableMessage());
        controller.restart(result);
        assertEquals(1, result.paused);
        assertEquals(0, source.calls);
        assertFalse(controller.commitRestart());
    }

    @Test public void boundaryBlocksRestartEvenIfSourceSupportsIt() {
        Source source = new Source(); source.boundary = "不完整";
        MediaPageController controller = controller(source);
        Result result = new Result();
        assertEquals("不完整", controller.getRestartUnavailableMessage());
        controller.restart(result);
        assertEquals(1, result.paused);
        assertEquals(0, source.calls);
    }

    @Test public void initialTruncationIsNotACompleteFiniteSource() {
        List<MediaItem> rows = new ArrayList<>();
        for (int i = 0; i <= MetadataLimits.MAX_ITEMS; i++) rows.add(item("" + i));
        MediaPageController controller = new MediaPageController(rows, null);
        assertNotNull(controller.getRestartUnavailableMessage());
        controller.restart(new Result());
        assertFalse(controller.commitRestart());
    }

    @Test public void emptyWithMoreIsPausedNotEndOrLoop() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.restart(result);
        source.pending.onLoaded(Collections.emptyList(), true);
        assertEquals(1, result.paused);
        assertEquals(0, result.ready);
        assertFalse(controller.commitRestart());
        assertEquals(2, controller.getItems().size());
    }

    @Test public void nowEmptyRangePreservesCurrentItemAndReportsNoFirstItem() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.restart(result);
        source.pending.onLoaded(Collections.emptyList(), false);
        assertEquals(1, result.ready);
        assertFalse(result.available);
        assertFalse(controller.commitRestart());
        assertEquals(2, controller.getItems().size());
    }

    @Test public void invalidRestartIsRejectedAtomicallyAndRetryRemainsPossible() {
        for (List<MediaItem> invalid : Arrays.asList(null,
                Arrays.asList(item("same"), item("same")),
                Arrays.asList(item("ok"), null), Collections.singletonList(item(null)))) {
            Source source = new Source();
            MediaPageController controller = controller(source);
            Result result = new Result();
            controller.restart(result);
            source.pending.onLoaded(invalid, false);
            assertEquals(1, result.failed);
            assertFalse(controller.commitRestart());
            assertEquals(2, controller.getItems().size());
            controller.restart(result);
            assertEquals(2, source.calls);
        }
    }

    @Test public void failedOrPausedRestartPreservesCurrentAndAllowsRetry() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.restart(result);
        source.pending.onFailure();
        assertEquals(1, result.failed);
        assertEquals(2, controller.getItems().size());
        controller.restart(result);
        source.pending.onPaused("五页预算");
        assertEquals(1, result.paused);
        assertEquals(2, controller.getItems().size());
        assertFalse(controller.isLoading());
    }
    @Test public void discardInvalidatesStagedAndInFlightRestartWithoutReplacingCurrent() {
        Source source = new Source();
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.restart(result);
        controller.discardRestart();
        source.pending.onLoaded(Collections.singletonList(item("first")), true);
        assertEquals(0, result.ready);
        assertFalse(controller.isLoading());
        assertFalse(controller.commitRestart());
        assertEquals(2, controller.getItems().size());
        controller.restart(result);
        source.pending.onLoaded(Collections.singletonList(item("first")), true);
        controller.discardRestart();
        assertFalse(controller.commitRestart());
        assertEquals(2, controller.getItems().size());
    }

}
