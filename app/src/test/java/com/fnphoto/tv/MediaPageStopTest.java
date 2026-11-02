package com.fnphoto.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** JVM delivery/lifecycle bookkeeping; does not claim Android UI coverage. */
public class MediaPageStopTest {
    private static MediaItem photo(String id) {
        return new MediaItem(id, "synthetic", "photo", "thumb", "media");
    }

    private static class Source implements MediaListHolder.PageSource {
        final boolean windowed;
        int requests, stops;
        boolean completeDuringStop;
        MediaListHolder.PageCallback pending;
        Source(boolean windowed) { this.windowed = windowed; }
        @Override public boolean isWindowed() { return windowed; }
        @Override public boolean pausesOnHostStop() { return true; }
        @Override public boolean hasMore() { return true; }
        @Override public boolean hasPrevious() { return true; }
        @Override public boolean supportsRestart() { return true; }
        @Override public void loadMore(MediaListHolder.PageCallback callback) {
            requests++;
            pending = callback;
        }
        @Override public void loadPrevious(MediaListHolder.PageCallback callback) { loadMore(callback); }
        @Override public void restart(MediaListHolder.PageCallback callback) { loadMore(callback); }
        @Override public void onHostStopped() {
            stops++;
            if (completeDuringStop && pending != null) {
                pending.onLoaded(Arrays.asList(photo("current"), photo("stale")), true);
                pending.onFailure();
                pending.onPaused("cancelled");
            }
        }
    }

    private static class Result implements MediaPageController.Callback {
        int ready, failed, paused;
        @Override public void onReady(boolean available) { ready++; }
        @Override public void onFailure() { failed++; }
        @Override public void onPaused(String message) { paused++; }
        void assertUntouched() {
            assertEquals(0, ready);
            assertEquals(0, failed);
            assertEquals(0, paused);
        }
    }

    private static MediaPageController controller(Source source) {
        return new MediaPageController(Collections.singletonList(photo("current")), source);
    }

    @Test public void stopInvalidatesBeforeSourceCanSynchronouslyCompleteCancellation() {
        Source source = new Source(true);
        source.completeDuringStop = true;
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.ensureNext(0, result);
        controller.onHostStopped();
        assertEquals(1, source.stops);
        assertEquals(1, source.requests);
        assertFalse(controller.isLoading());
        assertTrue(controller.hasMore());
        assertTrue(controller.hasPrevious());
        assertEquals(1, controller.getItems().size());
        result.assertUntouched();
    }

    @Test public void lateAppendCannotMutateItemsOrConsumeExplicitRetryCallback() {
        Source source = new Source(false);
        MediaPageController controller = controller(source);
        Result stopped = new Result();
        controller.ensureNext(0, stopped);
        MediaListHolder.PageCallback old = source.pending;
        controller.onHostStopped();
        Result resumed = new Result();
        controller.ensureNext(0, resumed);
        assertEquals(2, source.requests);
        old.onLoaded(Collections.singletonList(photo("stale")), false);
        old.onFailure();
        old.onPaused("stale");
        assertEquals(1, controller.getItems().size());
        assertTrue(controller.isLoading());
        stopped.assertUntouched();
        resumed.assertUntouched();
        source.pending.onLoaded(Collections.singletonList(photo("next")), true);
        assertEquals(1, resumed.ready);
        assertEquals(2, controller.getItems().size());
    }

    @Test public void lateWindowResponseCannotConsumeNewForwardOrBackwardDelivery() {
        for (boolean forward : new boolean[]{true, false}) {
            Source source = new Source(true);
            MediaPageController controller = controller(source);
            Result stopped = new Result();
            if (forward) controller.ensureNext(0, stopped);
            else controller.ensurePrevious(0, stopped);
            MediaListHolder.PageCallback old = source.pending;
            controller.onHostStopped();
            Result resumed = new Result();
            if (forward) controller.ensureNext(0, resumed);
            else controller.ensurePrevious(0, resumed);
            old.onFailure();
            old.onPaused("stale");
            old.onLoaded(Arrays.asList(photo("current"), photo("stale")), true);
            stopped.assertUntouched();
            resumed.assertUntouched();
            assertTrue(controller.isLoading());
            assertEquals(1, controller.getItems().size());
            source.pending.onLoaded(forward
                    ? Arrays.asList(photo("current"), photo("next"))
                    : Arrays.asList(photo("previous"), photo("current")), true);
            assertEquals(1, resumed.ready);
            assertFalse(controller.isLoading());
        }
    }

    @Test public void lateRestartCannotStageOrStealNewRestartDelivery() {
        Source source = new Source(true);
        MediaPageController controller = controller(source);
        Result stopped = new Result();
        controller.restart(stopped);
        MediaListHolder.PageCallback old = source.pending;
        controller.onHostStopped();
        Result resumed = new Result();
        controller.restart(resumed);
        old.onLoaded(Collections.singletonList(photo("stale")), false);
        old.onFailure();
        old.onPaused("stale");
        stopped.assertUntouched();
        resumed.assertUntouched();
        assertTrue(controller.isLoading());
        assertFalse(controller.commitRestart());
        source.pending.onLoaded(Collections.singletonList(photo("first")), false);
        assertEquals(1, resumed.ready);
        assertTrue(controller.commitRestart());
        assertEquals("first", controller.getItems().get(0).getId());
    }

    @Test public void stoppedHostDiscardsAlreadyStagedRestart() {
        Source source = new Source(true);
        MediaPageController controller = controller(source);
        controller.restart(new Result());
        source.pending.onLoaded(Collections.singletonList(photo("first")), false);
        controller.onHostStopped();
        assertFalse(controller.commitRestart());
        assertEquals("current", controller.getItems().get(0).getId());
    }

    @Test public void nonCancellingSourceKeepsDeliverySoItsFinalPageIsNotLost() {
        Source source = new Source(false) {
            @Override public boolean pausesOnHostStop() { return false; }
        };
        MediaPageController controller = controller(source);
        Result result = new Result();
        controller.ensureNext(0, result);
        controller.onHostStopped();
        assertTrue(controller.isLoading());
        source.pending.onLoaded(Collections.singletonList(photo("final")), false);
        assertFalse(controller.isLoading());
        assertEquals(1, result.ready);
        assertEquals(2, controller.getItems().size());
        assertFalse(controller.hasMore());
    }

    @Test public void noSourceAndClosedControllerDoNotStartWorkOnStop() {
        MediaPageController finite = new MediaPageController(Collections.singletonList(photo("current")), null);
        finite.onHostStopped();
        assertFalse(finite.isLoading());
        assertEquals(1, finite.getItems().size());
        Source source = new Source(true);
        MediaPageController closed = controller(source);
        closed.close();
        closed.onHostStopped();
        assertEquals(0, source.stops);
        assertEquals(0, source.requests);
    }
}
