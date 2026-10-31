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

    @Test public void shortPageWithoutMetadataEnds() {
        GridPageState state = new GridPageState(2);
        state.accept(state.begin(false), Collections.singletonList(1), null);
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
}
