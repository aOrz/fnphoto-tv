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
        boolean available;
        @Override public void onReady(boolean nextAvailable) { ready++; available = nextAvailable; }
        @Override public void onFailure() { failures++; }
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
}
