package com.fnphoto.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MediaDetailLifecycleTest {
    @Test public void transportKeyDuringPrepareOnlyRecordsIntent() {
        DetailPlaybackState state = new DetailPlaybackState();
        assertFalse(state.isPrepared());
        assertFalse(state.shouldPlay());
        state.toggle();
        assertFalse(state.shouldPlay());
        state.prepared();
        assertFalse(state.shouldPlay());
        state.toggle();
        assertTrue(state.shouldPlay());
        state.reset();
        assertFalse(state.isPrepared());
        state.prepared();
        assertTrue(state.shouldPlay());
    }

    @Test public void stoppingSlideshowInvalidatesAdvanceButNotCurrentImage() {
        DetailLoadState image = new DetailLoadState();
        DetailLoadState navigation = new DetailLoadState();
        long imageRequest = image.next();
        long pendingAdvance = navigation.next();
        navigation.next();
        assertFalse(navigation.isCurrent(pendingAdvance));
        assertTrue(image.isCurrent(imageRequest));
    }

    private static MediaItem photo(String id) {
        return new MediaItem(id, id + ".jpg", "photo", "thumb/" + id, "photo/" + id);
    }

    private static class Source implements MediaListHolder.PageSource {
        int requests;
        boolean more = true;
        MediaListHolder.PageCallback pending;
        @Override public boolean hasMore() { return more; }
        @Override public void loadMore(MediaListHolder.PageCallback callback) {
            requests++;
            pending = callback;
        }
    }

    private static class Result implements MediaPageController.Callback {
        int ready;
        int failures;
        int pauses;
        boolean available;
        @Override public void onReady(boolean nextAvailable) { ready++; available = nextAvailable; }
        @Override public void onFailure() { failures++; }
        @Override public void onPaused(String message) { pauses++; }
    }

    @Test public void navigationInvalidatesOldImageAndVideoCallbacks() {
        DetailLoadState state = new DetailLoadState();
        long old = state.next();
        assertTrue(state.isCurrent(old));
        assertEquals(old, state.current());
        long next = state.next();
        assertFalse(state.isCurrent(old));
        assertTrue(state.isCurrent(next));
        state.close();
        assertFalse(state.isCurrent(next));
        assertFalse(state.isCurrent(state.next()));
    }

    @Test public void prefetchExcludesVideoAndMissingUrls() {
        assertTrue(DetailLoadState.shouldPreload(photo("1")));
        assertFalse(DetailLoadState.shouldPreload(new MediaItem("2", "clip", "video", "t", "video")));
        assertFalse(DetailLoadState.shouldPreload(new MediaItem("3", "bad", "photo", "t", null)));
        assertFalse(DetailLoadState.shouldPreload(null));
    }

    @Test public void withinLoadedPrefixNeedsNoRequest() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Arrays.asList(photo("1"), photo("2")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        assertTrue(result.available);
        assertEquals(0, source.requests);
    }

    @Test public void repeatedNextAtBoundaryStartsOnlyOnePage() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        controller.ensureNext(0, result);
        assertEquals(1, source.requests);
        assertTrue(controller.isLoading());
        source.pending.onLoaded(Arrays.asList(photo("1"), photo("2")), true);
        assertEquals(1, result.ready);
        assertTrue(result.available);
        assertFalse(controller.isLoading());
        assertEquals(2, controller.getItems().size());
    }

    @Test public void pageSnapshotMergesByStableIdWithoutReorderingCurrentMedia() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), source);
        controller.ensureNext(0, new Result());
        source.pending.onLoaded(Arrays.asList(photo("2"), photo("1"), photo("2"), photo("3")), false);
        assertEquals("1", controller.getItems().get(0).getId());
        assertEquals("2", controller.getItems().get(1).getId());
        assertEquals(3, controller.getItems().size());
        assertFalse(controller.hasMore());
    }

    @Test public void failedBoundaryCanRetry() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        source.pending.onFailure();
        assertEquals(1, result.failures);
        assertTrue(controller.hasMore());
        controller.ensureNext(0, result);
        assertEquals(2, source.requests);
        source.pending.onLoaded(Arrays.asList(photo("1"), photo("2")), false);
        assertTrue(result.available);
    }

    @Test public void destroyedDetailIgnoresLatePageAndDoesNotRetainStaticSource() {
        Source source = new Source();
        List<MediaItem> initial = Collections.singletonList(photo("1"));
        MediaListHolder.set(initial, source);
        MediaPageController controller = new MediaPageController(MediaListHolder.get(), MediaListHolder.getPageSource());
        MediaListHolder.clear();
        assertNull(MediaListHolder.get());
        assertNull(MediaListHolder.getPageSource());
        Result result = new Result();
        controller.ensureNext(0, result);
        controller.close();
        source.pending.onLoaded(Arrays.asList(photo("1"), photo("2")), true);
        assertEquals(0, result.ready);
        assertEquals(1, controller.getItems().size());
        assertFalse(controller.hasMore());
    }

    @Test public void repeatedFullPageCannotLoopForever() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        source.pending.onLoaded(Collections.singletonList(photo("1")), true);
        assertFalse(result.available);
        assertFalse(controller.hasMore());
        controller.ensureNext(0, result);
        assertEquals(1, source.requests);
    }

    @Test public void duplicateCompletionCannotAdvanceTwice() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        source.pending.onLoaded(Arrays.asList(photo("1"), photo("2")), true);
        source.pending.onLoaded(Arrays.asList(photo("1"), photo("2")), true);
        source.pending.onFailure();
        assertEquals(1, result.ready);
        assertEquals(0, result.failures);
    }

    @Test public void noSourceReportsActualEndWithoutNetwork() {
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), null);
        Result result = new Result();
        controller.ensureNext(0, result);
        assertEquals(1, result.ready);
        assertFalse(result.available);
    }

    @Test public void sparseScanPauseReleasesWaitAndAllowsOnlyExplicitNextRequest() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        source.pending.onPaused("已扫描175条，继续扫描");
        assertEquals(1, result.pauses);
        assertEquals(0, result.ready);
        assertEquals(0, result.failures);
        assertFalse(controller.isLoading());
        assertTrue(controller.hasMore());
        assertEquals(1, source.requests);
        source.pending.onPaused("late duplicate");
        assertEquals(1, result.pauses);
        controller.ensureNext(0, result);
        assertEquals(2, source.requests);
        source.pending.onLoaded(Arrays.asList(photo("1"), photo("2")), false);
        assertTrue(result.available);
    }

    @Test public void destroyedDetailIgnoresLatePause() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("1")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        controller.close();
        source.pending.onPaused("late");
        assertEquals(0, result.pauses);
        assertFalse(controller.isLoading());
    }

    @Test public void detailCannotAccumulateBeyondWindowEvenWithBuggySource() {
        Source source = new Source();
        MediaPageController controller = new MediaPageController(Collections.singletonList(photo("0")), source);
        Result result = new Result();
        controller.ensureNext(0, result);
        java.util.ArrayList<MediaItem> excessive = new java.util.ArrayList<>();
        for (int i = 0; i < 10000; i++) excessive.add(photo("" + i));
        source.pending.onLoaded(excessive, true);
        assertEquals(com.fnphoto.tv.paging.MetadataLimits.MAX_ITEMS, controller.getItems().size());
        assertFalse(controller.hasMore());
        assertNotNull(controller.getBoundaryMessage());
    }

    @Test public void exactWindowStopsBeforeAnotherNetworkRequest() {
        Source source = new Source();
        java.util.ArrayList<MediaItem> initial = new java.util.ArrayList<>();
        for (int i = 0; i < com.fnphoto.tv.paging.MetadataLimits.MAX_ITEMS; i++) initial.add(photo("" + i));
        MediaPageController controller = new MediaPageController(initial, source);
        assertFalse(controller.hasMore());
        assertNotNull(controller.getBoundaryMessage());
        controller.ensureNext(initial.size() - 1, new Result());
        assertEquals(0, source.requests);
    }
}
