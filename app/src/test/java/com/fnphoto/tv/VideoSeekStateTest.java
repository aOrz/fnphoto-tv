package com.fnphoto.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class VideoSeekStateTest {
    @Test public void relativeSeekClampsToBothEnds() {
        assertEquals(0, VideoSeekState.target(5_000, 60_000, -10_000));
        assertEquals(60_000, VideoSeekState.target(55_000, 60_000, 10_000));
        assertEquals(20_000, VideoSeekState.target(10_000, 60_000, 10_000));
        assertEquals(10_000, VideoSeekState.target(20_000, 60_000, -10_000));
    }

    @Test public void unknownAndEmptyDurationsHaveNoTarget() {
        assertEquals(-1, VideoSeekState.target(0, 0, 10_000));
        assertEquals(-1, VideoSeekState.target(0, -1, 10_000));
        assertEquals(-1, VideoSeekState.target(0, Long.MIN_VALUE, 10_000));
    }

    @Test public void oversizedPositionsAreNormalizedBeforeApplyingTheStep() {
        assertEquals(10_000, VideoSeekState.target(-500, 60_000, 10_000));
        assertEquals(50_000, VideoSeekState.target(90_000, 60_000, -10_000));
    }

    @Test public void arithmeticNeverWrapsAtLongLimits() {
        assertEquals(Long.MAX_VALUE, VideoSeekState.target(Long.MAX_VALUE - 5, Long.MAX_VALUE, 10));
        assertEquals(Long.MAX_VALUE, VideoSeekState.target(1, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(0, VideoSeekState.target(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE));
        assertEquals(0, VideoSeekState.target(0, Long.MAX_VALUE, Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE - 1, VideoSeekState.target(Long.MAX_VALUE, Long.MAX_VALUE, -1));
    }

    @Test public void repeatedSeeksAccumulateEvenWhileActualPositionIsStale() {
        VideoSeekState state = new VideoSeekState();
        assertEquals(Long.valueOf(20_000), state.request(10_000, 100_000, 10_000));
        assertNull(state.request(10_000, 100_000, 10_000));
        assertNull(state.request(10_000, 100_000, 10_000));
        assertEquals(40_000, state.displayedPosition(10_000));
        assertEquals(Long.valueOf(40_000), state.completed());
        assertNull(state.completed());
        assertEquals(41_000, state.displayedPosition(41_000));
    }

    @Test public void reversingDirectionUpdatesQueuedTargetRatherThanStalePosition() {
        VideoSeekState state = new VideoSeekState();
        assertEquals(Long.valueOf(40_000), state.request(30_000, 100_000, 10_000));
        assertNull(state.request(30_000, 100_000, 10_000));
        assertNull(state.request(30_000, 100_000, -10_000));
        assertNull(state.request(30_000, 100_000, -10_000));
        assertEquals(Long.valueOf(30_000), state.completed());
        assertNull(state.request(40_000, 100_000, -10_000));
        assertEquals(Long.valueOf(20_000), state.completed());
        assertNull(state.completed());
    }

    @Test public void returningToSentTargetNeedsNoSecondSeek() {
        VideoSeekState state = new VideoSeekState();
        assertEquals(Long.valueOf(20_000), state.request(10_000, 100_000, 10_000));
        assertNull(state.request(10_000, 100_000, 10_000));
        assertNull(state.request(10_000, 100_000, -10_000));
        assertNull(state.completed());
        assertEquals(21_000, state.displayedPosition(21_000));
    }

    @Test public void queuedRequestsStayAtBoundariesUntilDirectionChanges() {
        VideoSeekState state = new VideoSeekState();
        assertEquals(Long.valueOf(60_000), state.request(55_000, 60_000, 10_000));
        assertNull(state.request(55_000, 60_000, 10_000));
        assertNull(state.request(55_000, 60_000, -10_000));
        assertEquals(Long.valueOf(50_000), state.completed());
        assertNull(state.request(55_000, 60_000, -100_000));
        assertEquals(Long.valueOf(0), state.completed());
    }

    @Test public void absoluteScrubbingAndRelativeKeysShareOneQueue() {
        VideoSeekState state = new VideoSeekState();
        assertEquals(Long.valueOf(45_000), state.requestAbsolute(60_000, 45_000));
        assertNull(state.request(0, 60_000, 10_000));
        assertNull(state.requestAbsolute(60_000, 15_000));
        assertEquals(15_000, state.displayedPosition(45_000));
        assertEquals(Long.valueOf(15_000), state.completed());
        assertNull(state.completed());
    }

    @Test public void absoluteTargetsClampAndAnUnsolicitedCompletionDoesNothing() {
        VideoSeekState state = new VideoSeekState();
        assertNull(state.completed());
        assertEquals(Long.valueOf(60_000), state.requestAbsolute(60_000, Long.MAX_VALUE));
        assertNull(state.requestAbsolute(60_000, Long.MIN_VALUE));
        assertEquals(Long.valueOf(0), state.completed());
        assertNull(state.completed());
        assertNull(state.completed());
    }

    @Test public void invalidRequestsDoNotReplacePendingOrDisplayedPosition() {
        VideoSeekState state = new VideoSeekState();
        assertNull(state.request(10_000, 0, 10_000));
        assertNull(state.requestAbsolute(-1, 30_000));
        assertEquals(10_000, state.displayedPosition(10_000));
        assertEquals(Long.valueOf(20_000), state.request(10_000, 60_000, 10_000));
        assertNull(state.request(10_000, -1, -10_000));
        assertNull(state.requestAbsolute(0, 0));
        assertEquals(20_000, state.displayedPosition(10_000));
        assertNull(state.completed());
    }

    @Test public void resetDiscardsPendingTargetForNextPlayer() {
        VideoSeekState state = new VideoSeekState();
        state.request(10_000, 60_000, 10_000);
        state.request(10_000, 60_000, 10_000);
        state.reset();
        assertEquals(0, state.displayedPosition(0));
        assertNull(state.completed());
        assertEquals(Long.valueOf(10_000), state.request(0, 60_000, 10_000));
    }
}
