package com.fnphoto.tv.paging;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class GridPageStateTest {
    @Test public void duplicateBindingsCannotRequestSameOffset() {
        GridPageState state = new GridPageState(2);
        GridPageState.Request first = state.begin(false);
        assertEquals(0, first.offset);
        assertNull(state.begin(false));
        assertNull(state.begin(true));
        assertArrayEquals(new boolean[]{true, true}, state.accept(first, Arrays.asList(1, 2), null));
        assertEquals(2, state.begin(false).offset);
    }

    @Test public void failureRequiresExplicitRetryAndPreservesOffset() {
        GridPageState state = new GridPageState(2);
        GridPageState.Request first = state.begin(false);
        state.accept(first, Arrays.asList(1, 2), null);
        GridPageState.Request next = state.begin(false);
        assertTrue(state.fail(next));
        assertTrue(state.hasError());
        assertNull(state.begin(false));
        GridPageState.Request retry = state.begin(true);
        assertEquals(2, retry.offset);
        assertFalse(state.hasError());
        assertFalse(state.fail(next));
        assertTrue(state.isCurrent(retry));
    }

    @Test public void changedQueryRejectsOldResponseIncludingSameOffset() {
        GridPageState state = new GridPageState(2);
        GridPageState.Request old = state.begin(false);
        state.reset();
        GridPageState.Request current = state.begin(false);
        assertNull(state.accept(old, Arrays.asList(1, 2), true));
        assertFalse(state.fail(old));
        assertTrue(state.isCurrent(current));
        assertArrayEquals(new boolean[]{true}, state.accept(current, Collections.singletonList(1), false));
    }

    @Test public void destroyedScreenRejectsResponsesAndNewRequests() {
        GridPageState state = new GridPageState(2);
        GridPageState.Request request = state.begin(false);
        state.close();
        assertNull(state.accept(request, Arrays.asList(1, 2), true));
        assertFalse(state.fail(request));
        assertNull(state.begin(true));
        assertFalse(state.hasMore());
    }

    @Test public void duplicateRowsDoNotDuplicateViewsAndOffsetUsesRawCount() {
        GridPageState state = new GridPageState(2);
        state.accept(state.begin(false), Arrays.asList(1, 2), null);
        assertArrayEquals(new boolean[]{false, true}, state.accept(state.begin(false), Arrays.asList(2, 3), true));
        assertEquals(4, state.begin(false).offset);
    }

    @Test public void repeatedFullPageStopsEvenIfServerClaimsMore() {
        GridPageState state = new GridPageState(2);
        state.accept(state.begin(false), Arrays.asList(1, 2), true);
        state.accept(state.begin(false), Arrays.asList(1, 2), true);
        assertTrue(state.isStalled());
        assertFalse(state.hasMore());
        assertNull(state.begin(true));
    }

    @Test public void emptyPageStopsEvenIfServerClaimsMore() {
        GridPageState state = new GridPageState(2);
        state.accept(state.begin(false), Collections.emptyList(), true);
        assertFalse(state.hasMore());
    }

    @Test public void shortPageWithoutMetadataContinuesUntilEmptyProbe() {
        GridPageState state = new GridPageState(2);
        state.accept(state.begin(false), Collections.singletonList(1), null);
        assertTrue(state.hasMore());
        GridPageState.Request second = state.begin(false);
        assertEquals(1, second.offset);
        state.accept(second, Collections.singletonList(2), null);
        assertTrue(state.hasMore());
        GridPageState.Request probe = state.begin(false);
        assertEquals(2, probe.offset);
        state.accept(probe, Collections.emptyList(), null);
        assertFalse(state.hasMore());
    }

    @Test public void explicitHasNextSupportsServerSmallerPageCap() {
        GridPageState state = new GridPageState(100);
        state.accept(state.begin(false), Arrays.asList(1, 2), true);
        assertTrue(state.hasMore());
        assertEquals(2, state.begin(false).offset);
    }

    @Test public void explicitEndAvoidsExtraEmptyPage() {
        GridPageState state = new GridPageState(2);
        state.accept(state.begin(false), Arrays.asList(1, 2), false);
        assertFalse(state.hasMore());
    }

    @Test public void resettingClearsDeduplicationAndOffset() {
        GridPageState state = new GridPageState(2);
        state.accept(state.begin(false), Arrays.asList(1, 2), false);
        state.reset();
        GridPageState.Request first = state.begin(false);
        assertEquals(0, first.offset);
        assertArrayEquals(new boolean[]{true, true}, state.accept(first, Arrays.asList(1, 2), false));
    }
    @Test public void detailReceivesBufferedFinalPageAfterListPrefetchCompletes() {
        GridPageState.Delivery delivery = new GridPageState.Delivery(100);
        assertTrue(delivery.hasMore(150, false));
        assertTrue(delivery.hasBuffered(150));
        delivery.delivered(150);
        assertFalse(delivery.hasMore(150, false));
    }

    @Test public void detailCanRequestNetworkOnlyWhenNoUndeliveredBuffer() {
        GridPageState.Delivery delivery = new GridPageState.Delivery(100);
        assertFalse(delivery.hasBuffered(100));
        assertTrue(delivery.hasMore(100, true));
        delivery.delivered(200);
        assertFalse(delivery.hasBuffered(200));
        assertTrue(delivery.hasMore(200, true));
    }

    @Test public void hundredThousandRowsStayWithinWindowAndResumeWithoutSkipping() {
        GridPageState state = new GridPageState(100, 1200);
        int consumed = 0;
        int windows = 1;
        while (consumed < 100000) {
            if (state.isWindowFull()) {
                assertFalse(state.hasMore());
                assertNull(state.begin(true));
                assertNotNull(state.boundaryMessage());
                assertTrue(state.nextWindow());
                assertEquals(0, state.getSeenCount());
                windows++;
            }
            GridPageState.Request request = state.begin(false);
            assertNotNull(request);
            assertEquals(consumed, request.offset);
            int count = Math.min(request.limit, 100000 - consumed);
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) ids.add(consumed + i);
            boolean[] added = state.accept(request, ids, consumed + count < 100000);
            for (boolean value : added) assertTrue(value);
            consumed += count;
            assertTrue(state.getSeenCount() <= 1200);
            assertTrue(state.getWindowRows() <= 1200);
        }
        assertEquals(84, windows);
        assertFalse(state.hasMore());
        assertFalse(state.isWindowFull());
        assertNull(state.boundaryMessage());
    }

    @Test public void partialWindowRequestUsesRemainingBudgetAndDoesNotLoseRows() {
        GridPageState state = new GridPageState(3, 5);
        state.accept(state.begin(false), Arrays.asList(1, 2, 3), true);
        GridPageState.Request last = state.begin(false);
        assertEquals(3, last.offset);
        assertEquals(2, last.limit);
        state.accept(last, Arrays.asList(4, 5), true);
        assertTrue(state.isWindowFull());
        assertTrue(state.nextWindow());
        GridPageState.Request next = state.begin(false);
        assertEquals(5, next.offset);
        assertEquals(3, next.limit);
        assertNull(state.accept(last, Arrays.asList(4, 5), true));
        assertTrue(state.isCurrent(next));
    }

    @Test public void oversizedResponseRejectedAtomicallyAndRetryKeepsOffset() {
        GridPageState state = new GridPageState(2, 4);
        GridPageState.Request first = state.begin(false);
        assertNull(state.accept(first, Arrays.asList(1, 2, 3), true));
        assertTrue(state.hasError());
        assertTrue(state.isInvalidPage());
        assertEquals(0, state.getOffset());
        assertEquals(0, state.getSeenCount());
        assertNull(state.begin(false));
        GridPageState.Request retry = state.begin(true);
        assertEquals(0, retry.offset);
        assertFalse(state.isInvalidPage());
        assertArrayEquals(new boolean[]{true, true}, state.accept(retry, Arrays.asList(1, 2), true));
    }

    @Test public void nullRowsRejectWholePageRatherThanAdvancePastMissingRows() {
        GridPageState state = new GridPageState(2);
        assertNull(state.accept(state.begin(false), Arrays.asList(1, null), true));
        assertTrue(state.hasError());
        assertEquals(0, state.getSeenCount());
        assertEquals(0, state.getOffset());
    }

    @Test public void duplicateRowsConsumeBoundedRawBudget() {
        GridPageState state = new GridPageState(3, 6);
        state.accept(state.begin(false), Arrays.asList(1, 1, 2), true);
        state.accept(state.begin(false), Arrays.asList(2, 2, 3), true);
        assertEquals(3, state.getSeenCount());
        assertEquals(6, state.getWindowRows());
        assertTrue(state.isWindowFull());
    }

    @Test public void failureAfterWindowChangeDoesNotSkipAndResetInvalidatesIt() {
        GridPageState state = new GridPageState(2, 2);
        state.accept(state.begin(false), Arrays.asList(1, 2), true);
        assertTrue(state.nextWindow());
        GridPageState.Request failed = state.begin(false);
        assertTrue(state.fail(failed));
        GridPageState.Request retry = state.begin(true);
        assertEquals(2, retry.offset);
        state.reset();
        assertNull(state.accept(retry, Arrays.asList(3, 4), true));
        assertEquals(0, state.begin(false).offset);
    }

    @Test public void nextWindowCannotDiscardInFlightOrClosedState() {
        GridPageState state = new GridPageState(2, 2);
        state.begin(false);
        assertFalse(state.nextWindow());
        state.close();
        assertFalse(state.nextWindow());
        assertNull(state.begin(true));
    }

    @Test public void integerOffsetBoundaryNeverWrapsNegative() throws Exception {
        GridPageState state = new GridPageState(100);
        java.lang.reflect.Field offset = GridPageState.class.getDeclaredField("offset");
        offset.setAccessible(true);
        offset.setInt(state, Integer.MAX_VALUE - 1);
        GridPageState.Request request = state.begin(false);
        assertEquals(Integer.MAX_VALUE - 1, request.offset);
        assertEquals(1, request.limit);
        state.accept(request, Collections.singletonList(1), true);
        assertEquals(Integer.MAX_VALUE, state.getOffset());
        assertTrue(state.isOffsetLimit());
        assertFalse(state.hasMore());
        assertFalse(state.nextWindow());
        assertNull(state.begin(true));
        assertNotNull(state.boundaryMessage());
    }

    @Test public void explicitEndExactlyAtWindowBoundaryIsNotTruncation() {
        GridPageState state = new GridPageState(2, 2);
        state.accept(state.begin(false), Arrays.asList(1, 2), false);
        assertFalse(state.isWindowFull());
        assertFalse(state.nextWindow());
        assertNull(state.boundaryMessage());
    }
}
