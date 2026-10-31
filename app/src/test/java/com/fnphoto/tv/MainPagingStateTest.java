package com.fnphoto.tv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MainPagingStateTest {
    @Test public void firstPageDoesNotStartNextPageAndSingleFlightIsEnforced() {
        MainPagingState state = new MainPagingState(2);
        assertEquals(0, state.begin());
        assertEquals(-1, state.begin());
        assertEquals(Arrays.asList(0, 1), state.accept(Arrays.asList("a", "b"), null));
        assertFalse(state.isLoading());
        assertTrue(state.hasMore());
        assertEquals(2, state.getOffset());
    }
    @Test public void failureRetainsOffsetAndAllowsExplicitRetry() {
        MainPagingState state = new MainPagingState(2);
        state.begin(); state.accept(Arrays.asList("a", "b"), true);
        assertEquals(2, state.begin()); state.fail();
        assertTrue(state.hasFailed());
        assertEquals(2, state.begin());
        assertFalse(state.hasFailed());
        assertEquals(Collections.singletonList(0), state.accept(Collections.singletonList("c"), false));
        assertFalse(state.hasMore());
        assertEquals(-1, state.begin());
    }
    @Test public void overlappingPagesDeduplicateButAdvanceByServerRows() {
        MainPagingState state = new MainPagingState(2);
        state.begin(); state.accept(Arrays.asList("a", "b"), null);
        state.begin();
        assertEquals(Collections.singletonList(1), state.accept(Arrays.asList("b", "c"), true));
        assertEquals(4, state.getOffset());
        assertTrue(state.hasMore());
    }
    @Test public void repeatedPageStopsEvenIfServerClaimsMore() {
        MainPagingState state = new MainPagingState(2);
        state.begin(); state.accept(Arrays.asList("a", "b"), true);
        state.begin();
        assertTrue(state.accept(Arrays.asList("a", "b"), true).isEmpty());
        assertTrue(state.isStalled());
        assertFalse(state.hasMore());
    }
    @Test public void explicitHasNextHandlesServerPageCapBelowRequestedSize() {
        MainPagingState state = new MainPagingState(60);
        state.begin(); state.accept(Collections.singletonList("a"), true);
        assertTrue(state.hasMore());
        assertEquals(1, state.begin());
        state.accept(Collections.singletonList("b"), false);
        assertFalse(state.hasMore());
    }
    @Test public void missingHasNextDoesNotTurnShortPageIntoFalseEnd() {
        MainPagingState state = new MainPagingState(35);
        for (int i = 0; i < 3; i++) {
            assertEquals(i, state.begin());
            assertEquals(Collections.singletonList(0), state.accept(Collections.singletonList("id" + i), null));
            assertTrue(state.hasMore());
        }
        assertEquals(3, state.begin());
        state.accept(Collections.emptyList(), null);
        assertFalse(state.hasMore());
        assertFalse(state.isStalled());
    }
    @Test public void contradictoryEmptyPageStopsWithIncompleteWarning() {
        MainPagingState state = new MainPagingState(2);
        state.begin(); state.accept(Collections.emptyList(), true);
        assertFalse(state.hasMore()); assertTrue(state.isStalled());
    }
    @Test public void cancelDoesNotLoseOffsetOrPreventResume() {
        MainPagingState state = new MainPagingState(2);
        state.begin(); state.accept(Arrays.asList("a", "b"), true);
        state.begin(); state.cancel();
        assertEquals(2, state.begin());
    }
    @Test public void duplicateIdsWithinPageAreOnlyDisplayedOnce() {
        MainPagingState state = new MainPagingState(3);
        state.begin();
        assertEquals(Arrays.asList(0, 2), state.accept(Arrays.asList("a", "a", "b"), false));
        assertEquals(3, state.getOffset());
    }
    @Test public void largeDatasetIsFetchedOnlyWhenCallerRequestsEachPage() {
        for (int size : new int[] {0, 34, 35, 36, 60, 100, 1000, 10000}) {
            MainPagingState state = new MainPagingState(60);
            int displayed = 0, requests = 0;
            while (state.hasMore()) {
                int offset = state.begin(); requests++;
                List<String> ids = new ArrayList<>();
                for (int i = offset; i < Math.min(size, offset + 60); i++) ids.add("id" + i);
                displayed += state.accept(ids, offset + ids.size() < size).size();
                assertFalse(state.isLoading());
                if (requests == 1) assertEquals(Math.min(size, 60), displayed);
            }
            assertEquals(size, displayed);
            assertEquals(Math.max(1, (size + 59) / 60), requests);
        }
    }
    @Test public void terminalPrefetchAfterOpeningDetailMustStillBeDelivered() {
        MainPagingState state = new MainPagingState(2);
        state.begin(); state.accept(Arrays.asList("a", "b"), true);
        MainPagingState.DeliveryCursor detail = new MainPagingState.DeliveryCursor(2);
        state.begin(); state.accept(Collections.singletonList("c"), false);
        assertFalse(state.hasMore());
        assertTrue(detail.hasPending(3)); // source.hasMore remains true for this consumer.
        detail.delivered(3);
        assertFalse(detail.hasPending(3));
        assertEquals(-1, state.begin()); // Buffered delivery needs no further HTTP request.
    }
    @Test public void failedRequestDoesNotAdvanceDetailDeliveryCursor() {
        MainPagingState.DeliveryCursor detail = new MainPagingState.DeliveryCursor(2);
        MainPagingState state = new MainPagingState(2);
        state.begin(); state.fail();
        assertFalse(detail.hasPending(2));
        assertTrue(detail.hasPending(3));
        detail.delivered(3);
        assertFalse(detail.hasPending(3));
    }
    @Test(expected = IllegalStateException.class) public void unexpectedCompletionRejected() {
        new MainPagingState(2).accept(Collections.singletonList("a"), false);
    }
    @Test(expected = IllegalArgumentException.class) public void invalidPageSizeRejected() {
        new MainPagingState(0);
    }
    @Test public void oversizedServerPageDoesNotSkipRowsAndCanRetry() {
        MainPagingState state = new MainPagingState(2);
        state.begin();
        assertTrue(state.accept(Arrays.asList("a", "b", "c"), true).isEmpty());
        assertTrue(state.hasFailed());
        assertTrue(state.hasInvalidPage());
        assertEquals(0, state.getOffset());
        assertEquals(0, state.retainedIdCount());
        assertEquals(0, state.begin());
        assertEquals(Arrays.asList(0, 1), state.accept(Arrays.asList("a", "b"), true));
        assertFalse(state.hasInvalidPage());
    }

    @Test public void integerOffsetLimitStopsBeforeIssuingAnUnsafeRequest() throws Exception {
        MainPagingState state = new MainPagingState(2);
        java.lang.reflect.Field offset = MainPagingState.class.getDeclaredField("offset");
        offset.setAccessible(true);
        offset.setInt(state, Integer.MAX_VALUE - 1);
        assertEquals(-1, state.begin());
        assertTrue(state.isOffsetLimited());
        assertFalse(state.hasMore());
        assertEquals(Integer.MAX_VALUE - 1, state.getOffset());
        assertEquals(0, state.retainedIdCount());
    }

    @Test public void nullIdRejectsTheWholePageWithoutMutatingDedupOrOffset() {
        MainPagingState state = new MainPagingState(2);
        state.begin();
        assertTrue(state.accept(Arrays.asList("a", null), true).isEmpty());
        assertTrue(state.hasInvalidPage());
        assertEquals(0, state.getOffset());
        assertEquals(0, state.retainedIdCount());
        state.begin();
        assertEquals(Arrays.asList(0, 1), state.accept(Arrays.asList("a", "b"), false));
    }

    @Test public void rawScanDeduplicationMemoryIsBoundedAcrossHundredThousandRows() {
        MainPagingState state = new MainPagingState(35);
        int rows = 100000;
        while (state.hasMore()) {
            int offset = state.begin();
            List<String> ids = new ArrayList<>();
            for (int n = offset; n < Math.min(rows, offset + 35); n++) ids.add("id" + n);
            state.accept(ids, offset + ids.size() < rows);
            assertTrue(state.retainedIdCount() <= 70);
        }
        assertEquals(rows, state.getOffset());
    }

    @Test public void realEmptyTerminalPageIsNotAnIncompleteWarning() {
        MainPagingState state = new MainPagingState(35);
        state.begin(); state.accept(Collections.emptyList(), false);
        assertFalse(state.hasMore());
        assertFalse(state.isStalled());
    }

    @Test public void repeatedOldIdWithinOnePageStaysKnownWhenOtherIdsEvictHistory() {
        MainPagingState state = new MainPagingState(3);
        state.begin(); state.accept(Arrays.asList("a", "b", "c"), true);
        state.begin(); state.accept(Arrays.asList("d", "e", "f"), true);
        state.begin();
        assertEquals(Collections.singletonList(1), state.accept(Arrays.asList("a", "g", "a"), true));
        assertEquals(9, state.getOffset());
        assertTrue(state.retainedIdCount() <= 6);
    }

}
