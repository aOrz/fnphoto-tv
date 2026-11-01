package com.fnphoto.tv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class SlidingPhotoWindowTest {
    private static List<MediaItem> rows(int start, int count) {
        List<MediaItem> rows = new ArrayList<>();
        for (int i = start; i < start + count; i++)
            rows.add(new MediaItem("id" + i, "Photo " + i, "photo", null, null));
        return rows;
    }

    @Test public void oneHundredThousandRowsRemainBoundedAndCanBeRevisited() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(1050, 32);
        for (int offset = 0; offset < 100000;) {
            int count = Math.min(35, 100000 - offset);
            window.addForward(offset, count, rows(offset, count));
            offset += count;
            assertTrue(window.retainedRawRows() <= 1050);
            assertTrue(window.getItems().size() <= 1050);
            assertTrue(window.pageCount() <= 32);
            assertEquals(offset, window.endRawOffset());
        }
        assertEquals(100000, window.endRawOffset());
        while (window.hasPrevious()) {
            int end = window.firstRawOffset();
            int start = Math.max(0, end - 35);
            window.addBackwardRange(start, end, rows(start, end - start));
            assertTrue(window.retainedRawRows() <= 1050);
            assertTrue(window.getItems().size() <= 1050);
            assertTrue(window.pageCount() <= 32);
            assertEquals(start, window.firstRawOffset());
        }
        assertEquals("id0", window.getItems().get(0).getId());
    }

    @Test public void filteredEmptyAndShortPagesCannotGrowDescriptorsWithoutBound() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(1050, 32);
        for (int offset = 0; offset < 10000; offset++)
            window.addForward(offset, 1, Collections.emptyList());
        assertEquals(32, window.pageCount());
        assertEquals(9968, window.firstRawOffset());
        assertEquals(10000, window.endRawOffset());
        assertTrue(window.getItems().isEmpty());
        assertTrue(window.hasPrevious());
    }

    @Test public void backwardShortResponsesAreCommittedOnlyAfterCompleteRange() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(70, 2);
        window.reset(35);
        window.addForward(35, 35, rows(35, 35));
        List<MediaItem> accumulated = rows(0, 2);
        accumulated.addAll(rows(2, 33));
        window.addBackwardRange(0, 35, accumulated);
        assertEquals(0, window.firstRawOffset());
        assertEquals(70, window.endRawOffset());
        assertEquals(70, window.getItems().size());
    }

    @Test public void anchorSurvivesOppositeEdgeEvictionAndDuplicateIdsAreLocal() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(70, 2);
        window.addForward(0, 35, rows(0, 35));
        window.addForward(35, 35, rows(35, 35));
        SlidingPhotoWindow.Change change = window.addForward(70, 35, rows(70, 35));
        assertTrue(change.trimmed);
        assertEquals(35, change.removedItems);
        assertEquals(0, window.indexOf("id35"));
        assertEquals(-1, window.indexOf("id0"));
        window.addBackwardRange(0, 35, rows(0, 35));
        assertEquals(35, window.indexOf("id35"));
        assertEquals(-1, window.indexOf("id70"));
    }

    @Test public void inputAndOutputListsDoNotKeepMutableBackingStorage() {
        SlidingPhotoWindow window = new SlidingPhotoWindow();
        List<MediaItem> original = rows(0, 2);
        window.addForward(0, 2, original);
        original.clear();
        window.getItems().clear();
        assertEquals(2, window.getItems().size());
    }

    @Test public void invalidIntervalsDoNotPartiallyMutateState() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(70, 2);
        window.addForward(0, 35, rows(0, 35));
        try { window.addForward(36, 1, rows(36, 1)); fail(); }
        catch (IllegalArgumentException expected) { }
        try { window.addBackwardRange(0, 34, rows(0, 34)); fail(); }
        catch (IllegalArgumentException expected) { }
        try { window.addForward(35, 71, rows(35, 71)); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(35, window.endRawOffset());
        assertEquals(1, window.pageCount());
    }

    @Test public void noMatchPagesStillPreserveExactRawOffsetsAndDedupIsWindowOnly() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(70, 2);
        window.addForward(0, 35, rows(0, 1));
        window.addForward(35, 35, rows(0, 1));
        assertEquals(1, window.getItems().size());
        assertEquals(70, window.endRawOffset());
        window.addForward(70, 35, Collections.emptyList());
        window.addForward(105, 35, rows(0, 1));
        assertEquals(1, window.getItems().size());
        assertEquals(140, window.endRawOffset());
    }
    @Test public void backwardRequestKeepsExactRetryAndShortPageContinuation() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(70, 2);
        window.reset(35);
        window.append(35, 35, rows(35, 35), false);
        SlidingPhotoWindow.Request request = window.beginPrevious(35);
        assertEquals(0, request.offset);
        assertEquals(35, request.limit);
        assertFalse(window.acceptPrevious(0, 2, rows(0, 2)));
        assertEquals(2, window.previousRequest().offset);
        assertEquals(33, window.previousRequest().limit);
        assertEquals(35, window.firstOffset());
        assertEquals(2, window.pendingItemCount());
        // A failed HTTP attempt does not consume the request or mutate the window.
        assertEquals(2, window.beginPrevious(35).offset);
        assertTrue(window.acceptPrevious(2, 33, rows(2, 33)));
        assertNull(window.previousRequest());
        assertEquals(0, window.pendingItemCount());
        assertEquals(0, window.firstOffset());
        assertFalse(window.hasMore());
    }

    @Test public void backwardEvictionReopensPreviouslyExhaustedForwardBoundary() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(70, 2);
        window.reset(70);
        window.append(70, 35, rows(70, 35), true);
        window.append(105, 35, rows(105, 35), false);
        window.beginPrevious(35);
        assertTrue(window.acceptPrevious(35, 35, rows(35, 35)));
        assertTrue(window.hasMore());
        assertEquals(105, window.nextOffset());
    }

    @Test public void invalidBackwardResponseDoesNotConsumeRetryOrDiscardResidentData() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(70, 2);
        window.reset(35);
        window.append(35, 35, rows(35, 35), true);
        window.beginPrevious(35);
        try { window.acceptPrevious(0, 0, Collections.emptyList()); fail(); }
        catch (IllegalArgumentException expected) { }
        try { window.acceptPrevious(0, 36, rows(0, 36)); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(0, window.previousRequest().offset);
        assertEquals(35, window.firstOffset());
        assertEquals(0, window.pendingItemCount());
        window.clear();
        assertNull(window.previousRequest());
        assertEquals(0, window.pageCount());
    }
    @Test public void estimatedByteBudgetEvictsWholePagesAndOversizeIsAtomic() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(560, 16, 1000);
        window.append(0, 1, rows(0, 1), true);
        window.append(1, 1, rows(1, 1), true);
        window.append(2, 1, rows(2, 1), true);
        assertTrue(window.retainedEstimatedBytes() <= 1000);
        assertTrue(window.firstOffset() > 0);
        int before = window.nextOffset();
        long bytes = window.retainedEstimatedBytes();
        String huge = new String(new char[1000]);
        try {
            window.append(before, 1, Collections.singletonList(
                    new MediaItem("big", huge, "photo", null, null)), true);
            fail();
        } catch (IllegalArgumentException expected) { }
        assertEquals(before, window.nextOffset());
        assertEquals(bytes, window.retainedEstimatedBytes());
        window.clear();
        assertEquals(0, window.retainedEstimatedBytes());
    }

    @Test public void pendingByteOverflowPreservesExactRetryCursor() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(560, 16, 1000);
        window.reset(3);
        window.append(3, 1, rows(3, 1), true);
        window.beginPrevious(3);
        assertFalse(window.acceptPrevious(0, 2, rows(0, 2)));
        long bytes = window.pendingEstimatedBytes();
        try { window.acceptPrevious(2, 1, rows(2, 1)); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(2, window.previousRequest().offset);
        assertEquals(bytes, window.pendingEstimatedBytes());
        assertEquals(3, window.firstOffset());
        window.cancelPrevious();
        assertEquals(0, window.pendingEstimatedBytes());
    }
    @Test public void variableShortPagesAndRawPagingCanTraverseBothDirections() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(560, 16);
        MainPagingState paging = new MainPagingState(35);
        while (paging.hasMore()) {
            int offset = paging.begin();
            int count = Math.min(5000 - offset, offset % 35 + 1);
            List<String> ids = new ArrayList<>();
            for (MediaItem item : rows(offset, count)) ids.add(item.getId());
            paging.accept(ids, offset + count < 5000);
            window.append(offset, count, rows(offset, count), paging.hasMore());
            assertEquals(paging.getOffset(), window.nextOffset());
        }
        assertFalse(window.hasMore());
        while (window.hasPrevious()) {
            SlidingPhotoWindow.Request request = window.beginPrevious(35);
            while (request != null) {
                MainPagingState reverse = new MainPagingState(request.limit, request.offset);
                int offset = reverse.begin();
                // Failed requests must not move either raw cursor.
                reverse.fail();
                assertEquals(offset, reverse.begin());
                int count = Math.min(request.limit, offset % 7 + 1);
                List<String> ids = new ArrayList<>();
                for (MediaItem item : rows(offset, count)) ids.add(item.getId());
                reverse.accept(ids, true);
                window.acceptPrevious(offset, count, rows(offset, count));
                request = window.previousRequest();
                assertTrue(window.retainedRawRows() <= 560);
                assertTrue(window.pageCount() <= 16);
            }
            List<MediaItem> retained = window.snapshot();
            assertEquals("id" + window.firstOffset(), retained.get(0).getId());
            assertEquals("id" + (window.nextOffset() - 1), retained.get(retained.size() - 1).getId());
        }
        assertEquals(0, window.firstOffset());
        assertTrue(window.hasMore());
    }
    @Test public void duplicateModelInRetainedPageSurvivesOldPageEviction() {
        SlidingPhotoWindow window = new SlidingPhotoWindow(3, 3);
        MediaItem duplicate = rows(0, 1).get(0);
        window.append(0, 1, java.util.Collections.singletonList(duplicate), true);
        window.append(1, 1, java.util.Collections.singletonList(duplicate), true);
        window.append(2, 1, rows(2, 1), true);
        window.append(3, 1, rows(3, 1), true);
        assertEquals(1, window.firstOffset());
        assertEquals("id0", window.snapshot().get(0).getId());
        assertEquals(3, window.snapshot().size());
    }

    @Test public void sparsePinUsesRawOrderWhenDirectionChangesAcrossLargeGap() {
        MediaItem early = rows(10, 1).get(0);
        early.setBrowseRawOffset(10);
        MediaItem late = rows(5000, 1).get(0);
        late.setBrowseRawOffset(5000);
        List<MediaItem> rows = SlidingPhotoWindow.withAnchor(java.util.Collections.singletonList(late), early);
        assertSame(early, rows.get(0));
        assertSame(late, rows.get(1));
        rows = SlidingPhotoWindow.withAnchor(java.util.Collections.singletonList(early), late);
        assertSame(early, rows.get(0));
        assertSame(late, rows.get(1));
        assertEquals(2, SlidingPhotoWindow.withAnchor(rows, early).size());
        assertEquals(1, SlidingPhotoWindow.withAnchor(java.util.Collections.singletonList(late),
                new MediaItem("unknown", "unknown", "photo", null, null)).size());
    }
    @Test public void bufferedTerminalNeighborSurvivesEvictionOfDeliveredId() {
        MediaItem last = rows(10000, 1).get(0);
        last.setBrowseRawOffset(10000);
        List<MediaItem> terminal = java.util.Collections.singletonList(last);
        assertTrue(SlidingPhotoWindow.hasRawNeighbor(terminal, 0, false));
        assertFalse(SlidingPhotoWindow.hasRawNeighbor(terminal, 10000, false));
        assertFalse(SlidingPhotoWindow.hasRawNeighbor(terminal, -1, false));
        MediaItem first = rows(0, 1).get(0);
        first.setBrowseRawOffset(0);
        assertTrue(SlidingPhotoWindow.hasRawNeighbor(java.util.Collections.singletonList(first), 10000, true));
    }
}
