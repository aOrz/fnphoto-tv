package com.fnphoto.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class SearchKeyboardStateTest {
    @Test public void initialBrowseBypassesHiddenIme() {
        assertTrue(new SearchKeyboardState().routesRemoteKeys());
    }

    @Test public void explicitOpenWaitsForKeyboardWithoutStealingDirections() {
        SearchKeyboardState state = new SearchKeyboardState();
        state.request();
        state.observeVisibility(false);
        assertFalse(state.routesRemoteKeys());
    }

    @Test public void visibleKeyboardOwnsKeysEvenWithoutExplicitRequest() {
        SearchKeyboardState state = new SearchKeyboardState();
        state.observeVisibility(true);
        assertFalse(state.routesRemoteKeys());
    }

    @Test public void observedDismissalReturnsToBrowsing() {
        SearchKeyboardState state = new SearchKeyboardState();
        state.request();
        state.observeVisibility(true);
        state.observeVisibility(false);
        assertTrue(state.routesRemoteKeys());
    }

    @Test public void backOrDoneDoesNotHijackStillVisibleKeyboard() {
        SearchKeyboardState state = new SearchKeyboardState();
        state.request();
        state.observeVisibility(true);
        state.dismiss();
        assertFalse(state.routesRemoteKeys());
        state.observeVisibility(false);
        assertTrue(state.routesRemoteKeys());
    }

    @Test public void cancelPendingOrFloatingKeyboardAllowsBrowsingAgain() {
        SearchKeyboardState state = new SearchKeyboardState();
        state.request();
        state.dismiss();
        assertTrue(state.routesRemoteKeys());
    }

    @Test public void browseDownKeepsUpWhenKeyboardAppears() {
        SearchKeyboardState state = new SearchKeyboardState();
        assertTrue(state.routeKey(1, 23, 10, true, false));
        state.request();
        state.observeVisibility(true);
        assertTrue(state.routeKey(1, 23, 10, true, false)); // repeat
        assertTrue(state.routeKey(1, 23, 10, false, true));
        assertFalse(state.routeKey(1, 23, 11, true, false));
    }

    @Test public void imeBackKeepsUpWhenKeyboardHidesBetweenHalves() {
        SearchKeyboardState state = new SearchKeyboardState();
        state.request();
        state.observeVisibility(true);
        assertFalse(state.routeKey(1, 4, 10, true, false));
        state.observeVisibility(false);
        assertFalse(state.routeKey(1, 4, 10, false, true));
        assertTrue(state.routeKey(1, 20, 11, true, false));
    }

    @Test public void keysAndDevicesHaveIndependentOwners() {
        SearchKeyboardState state = new SearchKeyboardState();
        assertTrue(state.routeKey(1, 20, 10, true, false));
        state.request();
        assertFalse(state.routeKey(2, 20, 10, true, false));
        assertFalse(state.routeKey(1, 23, 10, true, false));
        assertTrue(state.routeKey(1, 20, 10, false, true));
        assertFalse(state.routeKey(2, 20, 10, false, true));
        assertFalse(state.routeKey(1, 23, 10, false, true));
    }

    @Test public void orphanUpAndCanceledWindowCannotBeIntercepted() {
        SearchKeyboardState state = new SearchKeyboardState();
        assertFalse(state.routeKey(1, 20, 10, false, true));
        assertTrue(state.routeKey(1, 20, 11, true, false));
        state.clearKeys();
        assertFalse(state.routeKey(1, 20, 11, false, true));
    }

    @Test public void newDownReplacesMissingUpWithoutLeakingOldOwnership() {
        SearchKeyboardState state = new SearchKeyboardState();
        assertTrue(state.routeKey(1, 20, 10, true, false));
        state.request();
        assertFalse(state.routeKey(1, 20, 11, true, false));
        assertFalse(state.routeKey(1, 20, 10, false, true));
        assertFalse(state.routeKey(1, 20, 11, false, true));
    }

    @Test public void failedKeyboardShowReturnsToBrowseWithoutATimer() {
        SearchKeyboardState state = new SearchKeyboardState();
        int request = state.request();
        state.rejectRequest(request);
        assertTrue(state.routesRemoteKeys());
    }

    @Test public void oldShowFailureCannotCancelNewKeyboardRequest() {
        SearchKeyboardState state = new SearchKeyboardState();
        int old = state.request();
        state.dismiss();
        int current = state.request();
        state.rejectRequest(old);
        assertFalse(state.routesRemoteKeys());
        state.rejectRequest(current);
        assertTrue(state.routesRemoteKeys());
    }
    @Test public void initialHideMayCloseAutomaticImeButNotAnExplicitOpen() {
        SearchKeyboardState state = new SearchKeyboardState();
        state.observeVisibility(true);
        assertTrue(state.shouldHideInitialKeyboard());
        state.request();
        assertFalse(state.shouldHideInitialKeyboard());
    }
}
