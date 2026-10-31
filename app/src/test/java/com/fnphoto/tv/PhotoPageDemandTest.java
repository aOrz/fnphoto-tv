package com.fnphoto.tv;

import com.fnphoto.tv.paging.MetadataLimits;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** State-level regression tests; these do not exercise Android focus or image rendering. */
public class PhotoPageDemandTest {
    @Test public void firstMatchingPhotoAtIndexZeroCanRequestMore() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        assertTrue(demand.selectPhoto(0, false));
        assertTrue(demand.shouldLoad(1, 12));
        demand.onRequestStarted();
        assertEquals(4, demand.remainingPages());
        assertFalse(demand.selectPhoto(0, true));
        assertEquals(4, demand.remainingPages());
    }

    @Test public void distantPhotoDoesNotRequestPagesUntilItEntersThePrefetchRows() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectPhoto(10, false);
        assertFalse(demand.shouldLoad(35, 12));
        demand.selectPhoto(23, false);
        assertTrue(demand.shouldLoad(35, 12));
        demand.onRequestStarted();
        assertFalse(demand.shouldLoad(70, 12));
    }

    @Test public void footerAtCurrentEndRequestsOnceAndKeepsItsOriginalAnchorAfterAppend() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        assertTrue(demand.selectFooter(35, false));
        assertTrue(demand.shouldLoad(35, 12));
        demand.onRequestStarted();
        for (int replacement = 0; replacement < 100; replacement++) {
            assertFalse(demand.selectFooter(70 + replacement, false));
        }
        assertFalse(demand.shouldLoad(70, 12));
        assertFalse(demand.needsMore(70, 12));
        assertEquals(35, demand.footerHandoffIndex(70));
        assertEquals(4, demand.remainingPages());
    }

    @Test public void movingToFooterDuringSlowRequestCountsThatRequestInTheSameBudget() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectPhoto(23, false);
        demand.onRequestStarted();
        assertTrue(demand.selectFooter(35, true));
        assertEquals(4, demand.remainingPages());
        assertEquals(-1, demand.footerHandoffIndex(35));
        assertEquals(35, demand.footerHandoffIndex(36));
        demand.handoffToPhoto(35);
        assertEquals(4, demand.remainingPages());
        assertTrue(demand.shouldLoad(36, 12));
    }

    @Test public void programmaticHandoffsAcrossSparsePagesCannotRefillTheBudget() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectFooter(1, false);
        int displayed = 1;
        for (int page = 0; page < 5; page++) {
            assertTrue(demand.shouldLoad(displayed, 12));
            demand.onRequestStarted();
            int newPhoto = displayed++;
            demand.handoffToPhoto(newPhoto);
            assertFalse(demand.selectPhoto(newPhoto, false));
            assertEquals(4 - page, demand.remainingPages());
        }
        assertTrue(demand.isPaused());
        assertFalse(demand.shouldLoad(displayed, 12));
        assertTrue(demand.needsMore(displayed, 12)); // Keep a reachable explicit continuation action.
        assertThrows(IllegalStateException.class, demand::onRequestStarted);
    }

    @Test public void zeroMatchPagesKeepFooterDemandButStopAfterFiveRequests() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectFooter(0, false);
        for (int page = 0; page < 5; page++) {
            assertTrue(demand.shouldLoad(0, 18));
            demand.onRequestStarted();
            assertFalse(demand.selectFooter(0, false));
        }
        assertTrue(demand.isPaused());
        assertFalse(demand.shouldLoad(0, 18));
        assertEquals(-1, demand.footerHandoffIndex(0));
    }

    @Test public void explicitContinuationRefreshesBudgetAndMovesFooterAnchorToCurrentEnd() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectFooter(35, false);
        for (int page = 0; page < 5; page++) demand.onRequestStarted();
        demand.explicitContinue(37);
        assertEquals(5, demand.remainingPages());
        assertTrue(demand.shouldLoad(37, 12));
        assertFalse(demand.shouldLoad(38, 12));
        assertEquals(37, demand.footerHandoffIndex(38));
    }

    @Test public void layoutDistanceChangesDoNotGrantRequestsOrMoveFooterAnchor() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectPhoto(20, false);
        assertFalse(demand.shouldLoad(35, 12));
        assertTrue(demand.shouldLoad(35, 18));
        demand.onRequestStarted();
        for (int columns : new int[] {1, 3, 6, 4, 6, 1}) {
            demand.shouldLoad(35, columns * 3);
            assertEquals(4, demand.remainingPages());
        }
        demand.selectFooter(35, false);
        demand.onRequestStarted();
        for (int columns : new int[] {1, 6, 3, 4}) {
            assertFalse(demand.shouldLoad(70, columns * 3));
            assertEquals(35, demand.footerHandoffIndex(70));
        }
    }

    @Test public void movingBackToAnEarlierPhotoStopsCompletionDrivenPrefetch() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectFooter(35, false);
        demand.onRequestStarted();
        demand.selectPhoto(0, true);
        assertEquals(-1, demand.footerHandoffIndex(70));
        assertFalse(demand.shouldLoad(70, 18));
    }

    @Test public void resetDropsOldSelectionAndFooterHandoff() {
        PhotoPageDemand demand = new PhotoPageDemand(5);
        demand.selectFooter(35, false);
        demand.onRequestStarted();
        demand.reset();
        assertEquals(5, demand.remainingPages());
        assertFalse(demand.shouldLoad(35, 18));
        assertEquals(-1, demand.footerHandoffIndex(70));
        assertTrue(demand.selectFooter(35, false));
    }

    @Test public void invalidSelectionCannotCreateAnAutomaticDemand() {
        assertThrows(IllegalArgumentException.class, () -> new PhotoPageDemand(0));
        PhotoPageDemand demand = new PhotoPageDemand(5);
        assertFalse(demand.selectPhoto(-1, false));
        assertFalse(demand.selectFooter(-1, false));
        assertFalse(demand.shouldLoad(35, 12));
        demand.selectPhoto(35, false);
        assertFalse(demand.shouldLoad(35, 12));
        assertThrows(IllegalArgumentException.class, () -> demand.handoffToPhoto(-1));
    }

    @Test public void repeatedKeysCannotStartParallelPagesOrSpendBudgetWithoutARequest() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectPhoto(23, false);
        for (int press = 0; press < 100; press++) harness.reconcile();
        assertEquals(1, harness.requests);
        assertEquals(4, harness.demand.remainingPages());
        assertTrue(harness.paging.isLoading());
    }

    @Test public void slowPageCompletionCanReevaluateSameSelectionWithoutAnotherKey() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectPhoto(34, false);
        assertTrue(harness.reconcile());
        harness.complete(1, 1, true);
        assertTrue(harness.reconcile());
        assertEquals(2, harness.requests);
        assertEquals(3, harness.demand.remainingPages());
        harness.complete(35, 35, true);
        assertFalse(harness.reconcile());
        assertEquals(71, harness.displayed);
    }

    @Test public void metadataCompletionMakesFooterHandoffAvailable() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectFooter(35, false);
        assertTrue(harness.reconcile());
        harness.complete(35, 35, true);
        assertEquals(70, harness.displayed);
        assertEquals(35, harness.demand.footerHandoffIndex(harness.displayed));
        assertFalse(harness.reconcile());
    }

    @Test public void failureDoesNotRetryUntilExplicitActionAndKeepsRawOffset() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectFooter(35, false);
        assertTrue(harness.reconcile());
        harness.failRequest();
        for (int update = 0; update < 100; update++) assertFalse(harness.reconcile());
        assertEquals(35, harness.paging.getOffset());
        harness.explicitContinue();
        assertEquals(2, harness.requests);
        assertEquals(35, harness.requestedOffsets.get(1).intValue());
    }

    @Test public void fiveSparsePagesPauseAt175RawRowsUntilExplicitContinuation() {
        PagingHarness harness = new PagingHarness(true);
        harness.demand.selectFooter(0, false);
        for (int page = 0; page < 5; page++) {
            assertTrue(harness.reconcile());
            harness.complete(35, 0, true);
        }
        assertEquals(175, harness.paging.getOffset());
        assertEquals(0, harness.displayed);
        assertTrue(harness.scan.isPaused());
        for (int update = 0; update < 100; update++) assertFalse(harness.reconcile());
        harness.demand.selectPhoto(0, false); // A focus event is not consent to resume a paused scan.
        harness.demand.selectFooter(0, false);
        assertFalse(harness.reconcile());
        harness.explicitContinue();
        assertEquals(175, harness.requestedOffsets.get(5).intValue());
        harness.complete(35, 1, true);
        assertEquals(1, harness.displayed);
        assertFalse(harness.scan.isPaused());
    }

    @Test public void occasionalSparseMatchesDoNotTurnCompletionIntoUnlimitedScanning() {
        PagingHarness harness = new PagingHarness(true);
        harness.seed(1);
        harness.demand.selectPhoto(0, false);
        for (int page = 0; page < 5; page++) {
            assertTrue(harness.reconcile());
            harness.complete(35, 1, true);
            // A posted focus restore/rebind on the same photo must preserve the episode.
            assertFalse(harness.demand.selectPhoto(0, false));
        }
        assertEquals(5, harness.requests);
        assertEquals(176, harness.paging.getOffset());
        assertEquals(6, harness.displayed);
        assertFalse(harness.reconcile());
    }

    @Test public void shortPagesWithoutHasNextRemainResumableAfterAutomaticBudgetStops() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(1);
        harness.demand.selectPhoto(0, false);
        for (int page = 0; page < 5; page++) {
            assertTrue(harness.reconcile());
            harness.complete(1, 1, null);
        }
        assertTrue(harness.paging.hasMore());
        assertTrue(harness.demand.isPaused());
        assertEquals(6, harness.paging.getOffset());
        assertFalse(harness.reconcile());
        harness.explicitContinue();
        assertEquals(6, harness.requestedOffsets.get(5).intValue());
        harness.complete(0, 0, null);
        assertFalse(harness.paging.hasMore());
        assertFalse(harness.reconcile());
    }

    @Test public void denseWindowStopsAt1190AndExplicitNextBatchKeepsAllRawRows() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(1190);
        harness.demand.selectFooter(1190, false);
        assertFalse(harness.reconcile());
        assertEquals(0, harness.requests);
        harness.nextWindow();
        assertEquals(1190, harness.requestedOffsets.get(0).intValue());
        harness.complete(35, 35, true);
        assertEquals(1225, harness.paging.getOffset());
        assertEquals(35, harness.displayed);
    }

    @Test public void sharedFiftyItemPagesCanFill1200ButNeverRequestPastWindow() {
        PagingHarness harness = new PagingHarness(false, 50);
        harness.seed(1150);
        harness.demand.selectFooter(1150, false);
        assertTrue(harness.reconcile());
        harness.complete(50, 50, true);
        harness.demand.handoffToPhoto(1199);
        assertFalse(harness.reconcile());
        assertEquals(MetadataLimits.MAX_ITEMS, harness.displayed);
        assertEquals(1, harness.requests);
    }

    @Test public void terminalAndContradictoryEmptyPagesNeverCauseAutomaticLoops() {
        for (Boolean hasNext : new Boolean[] {null, false, true}) {
            PagingHarness harness = new PagingHarness(false);
            harness.seed(35);
            harness.demand.selectFooter(35, false);
            harness.reconcile();
            harness.complete(0, 0, hasNext);
            assertFalse(harness.reconcile());
            assertEquals(Boolean.TRUE.equals(hasNext), harness.paging.isStalled());
        }
    }

    @Test public void invalidPageKeepsOffsetAndRequiresExplicitRetry() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectFooter(35, false);
        harness.reconcile();
        harness.finishCallback();
        harness.paging.accept(Collections.singletonList(null), true);
        assertTrue(harness.paging.hasInvalidPage());
        assertFalse(harness.reconcile());
        assertEquals(35, harness.paging.getOffset());
        harness.explicitContinue();
        assertEquals(35, harness.requestedOffsets.get(1).intValue());
    }

    @Test public void repeatedRawPageStopsEvenWhenFooterStillWantsMore() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectFooter(35, false);
        harness.reconcile();
        harness.finishCallback();
        assertTrue(harness.paging.accept(harness.ids(0, 35), true).isEmpty());
        assertTrue(harness.paging.isStalled());
        assertFalse(harness.reconcile());
        assertEquals(70, harness.paging.getOffset());
        assertEquals(35, harness.displayed);
    }

    @Test public void offsetLimitRejectsRequestBeforeSpendingAutomaticBudget() throws Exception {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        java.lang.reflect.Field offset = MainPagingState.class.getDeclaredField("offset");
        offset.setAccessible(true);
        offset.setInt(harness.paging, Integer.MAX_VALUE - 34);
        harness.demand.selectFooter(35, false);
        assertFalse(harness.reconcile());
        assertTrue(harness.paging.isOffsetLimited());
        assertEquals(0, harness.requests);
        assertEquals(5, harness.demand.remainingPages());
        assertFalse(harness.reconcile());
    }

    @Test public void lostFocusOwnershipSuppressesReevaluationEvenWithFooterSelection() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectFooter(35, false);
        harness.reconcile();
        harness.ownsFocus = false; // Models drawer/detail/window ownership; no Android UI assertion.
        harness.complete(1, 1, true);
        assertFalse(harness.reconcile());
        assertEquals(1, harness.requests);
    }

    @Test public void canceledNavigationRejectsOldPageBeforeItMutatesNewScreen() {
        PagingHarness harness = new PagingHarness(false);
        harness.seed(35);
        harness.demand.selectFooter(35, false);
        harness.reconcile();
        Object oldCall = harness.call;
        long oldGeneration = harness.generation;
        harness.scope.next();
        harness.demand.reset();
        assertFalse(harness.paging.isLoading());
        assertFalse(harness.scope.complete(oldCall, oldGeneration));
        assertEquals(35, harness.paging.getOffset());
        assertEquals(35, harness.displayed);
        assertFalse(harness.reconcile());
    }

    /** Combines real paging states with synthetic request completions, not a Fragment replacement. */
    private static final class PagingHarness {
        final MainPagingState paging;
        final SparseScanBudget scan = new SparseScanBudget(5);
        final PhotoPageDemand demand = new PhotoPageDemand(5);
        final MainRequestScope scope = new MainRequestScope();
        final boolean filtered;
        final List<Integer> requestedOffsets = new ArrayList<>();
        int displayed;
        int requests;
        boolean ownsFocus = true;
        boolean continuingSparseScan;
        Object call;
        long generation;

        PagingHarness(boolean filtered) { this(filtered, 35); }
        PagingHarness(boolean filtered, int pageSize) {
            this.filtered = filtered;
            paging = new MainPagingState(pageSize);
        }

        void seed(int count) {
            while (displayed < count) {
                int offset = paging.begin();
                int size = Math.min(paging.getPageSize(), count - displayed);
                displayed += paging.accept(ids(offset, size), true).size();
            }
        }

        boolean reconcile() {
            if (!ownsFocus || paging.isLoading() || paging.hasFailed() || !paging.hasMore()
                    || scan.isPaused() || !MainMetadataWindow.fitsNextPage(displayed, paging.getPageSize())
                    || !demand.shouldLoad(displayed, 18)) return false;
            if (filtered && !continuingSparseScan) assertTrue(scan.start(false));
            return startRequest();
        }

        boolean startRequest() {
            int offset = paging.begin();
            if (offset < 0) return false;
            demand.onRequestStarted();
            requests++;
            requestedOffsets.add(offset);
            call = new Object();
            generation = scope.generation();
            scope.track(call, paging::cancel);
            return true;
        }

        void finishCallback() { assertTrue(scope.complete(call, generation)); }

        void complete(int rawRows, int matches, Boolean hasNext) {
            finishCallback();
            int added = paging.accept(ids(paging.getOffset(), rawRows), hasNext).size();
            displayed += Math.min(matches, added);
            continuingSparseScan = filtered && scan.acceptPage(matches > 0, paging.hasMore());
        }

        void failRequest() { finishCallback(); paging.fail(); continuingSparseScan = false; }

        void explicitContinue() {
            demand.explicitContinue(displayed);
            continuingSparseScan = false;
            if (filtered) assertTrue(scan.start(true));
            assertTrue(startRequest());
        }

        void nextWindow() {
            scope.next();
            displayed = 0;
            scan.reset();
            demand.reset();
            demand.selectFooter(0, false);
            explicitContinue();
        }

        List<String> ids(int offset, int count) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < count; i++) result.add("id-" + (offset + i));
            return result;
        }
    }
}
