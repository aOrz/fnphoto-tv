package com.fnphoto.tv;

import com.fnphoto.tv.paging.GridPageState;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
import static com.fnphoto.tv.GridScreenState.Kind.*;

public class GridScreenStateTest {
    private final GridPageState pages = new GridPageState(2, 4);

    private GridScreenState.Kind search(boolean ready, int count) {
        return GridScreenState.search("holiday", ready, count, pages, GridScreenState.Failure.REQUEST);
    }

    private GridScreenState.Kind folder(boolean loading, boolean failed, int folders, int photos) {
        return GridScreenState.folder(loading, failed, folders, photos, pages, GridScreenState.Failure.REQUEST);
    }

    private void acceptEmpty() {
        pages.accept(pages.begin(false), Collections.emptyList(), false);
    }

    @Test public void blankQueryShowsInputInsteadOfNoResults() {
        acceptEmpty();
        assertEquals(INPUT, GridScreenState.search("", true, 0, pages, GridScreenState.Failure.REQUEST));
    }

    @Test public void debounceAndNotStartedRequestsAreLoading() {
        assertEquals(LOADING, search(false, 0));
        assertEquals(LOADING, search(true, 0));
        assertEquals(LOADING, folder(false, false, 0, 0));
    }

    @Test public void activeRequestIsNotAnEmptyResult() {
        pages.begin(false);
        assertEquals(LOADING, search(true, 0));
        assertEquals(LOADING, folder(false, false, 0, 0));
    }

    @Test public void successfulEmptySearchAndFolderHaveDistinctStates() {
        acceptEmpty();
        assertEquals(NO_RESULTS, search(true, 0));
        assertEquals(EMPTY, folder(false, false, 0, 0));
    }

    @Test public void folderCannotBeEmptyUntilBothRequestsSucceed() {
        acceptEmpty();
        assertEquals(LOADING, folder(true, false, 0, 0));
        assertEquals(ERROR, folder(false, true, 0, 0));
        assertEquals(CONTENT, folder(false, false, 1, 0));
    }

    @Test public void initialFailureAndFailedAppendNeverClaimEmptyOrComplete() {
        pages.fail(pages.begin(false));
        assertEquals(ERROR, search(true, 0));
        assertEquals(ERROR, folder(false, false, 0, 0));
        GridPageState.Request retry = pages.begin(true);
        assertEquals(0, retry.offset);
        pages.accept(retry, Arrays.asList(10, 11), true);
        pages.fail(pages.begin(false));
        assertEquals(ERROR, search(true, 2));
        assertEquals(ERROR, folder(false, false, 0, 2));
        assertEquals(2, pages.getOffset());
    }

    @Test public void invalidPageIsAnErrorAndDoesNotAdvanceOffset() {
        GridPageState.Request page = pages.begin(false);
        pages.accept(page, Arrays.asList(10, null), false);
        assertEquals(ERROR, search(true, 0));
        assertEquals(ERROR, folder(false, false, 0, 0));
        assertEquals(0, pages.getOffset());
    }

    @Test public void emptyPageClaimingMoreIsIncompleteRatherThanEmpty() {
        pages.accept(pages.begin(false), Collections.emptyList(), true);
        assertEquals(BOUNDARY, search(true, 0));
        assertEquals(BOUNDARY, folder(false, false, 0, 0));
    }

    @Test public void fullWindowIsIncompleteRatherThanEnd() {
        pages.accept(pages.begin(false), Arrays.asList(1, 2), true);
        pages.accept(pages.begin(false), Arrays.asList(3, 4), true);
        assertEquals(BOUNDARY, search(true, 4));
        assertEquals(BOUNDARY, folder(false, false, 0, 4));
        assertTrue(pages.isWindowFull());
    }

    @Test public void emptyNextWindowDoesNotEraseEarlierSearchOrFolderResults() {
        pages.accept(pages.begin(false), Arrays.asList(1, 2), true);
        pages.accept(pages.begin(false), Arrays.asList(3, 4), true);
        assertTrue(pages.nextWindow());
        assertEquals(LOADING, search(true, 0));
        acceptEmpty();
        assertEquals(4, pages.getOffset());
        assertEquals(0, pages.getWindowRows());
        assertEquals(EMPTY_WINDOW, search(true, 0));
        assertEquals(EMPTY_WINDOW, folder(false, false, 0, 0));

        // A new query or directory starts a fresh scope; true emptiness is valid again.
        pages.reset();
        acceptEmpty();
        assertEquals(NO_RESULTS, search(true, 0));
        assertEquals(EMPTY, folder(false, false, 0, 0));
    }

    @Test public void finishedNonemptyResultsRemainContent() {
        pages.accept(pages.begin(false), Arrays.asList(1, 2), false);
        assertEquals(CONTENT, search(true, 2));
        assertEquals(CONTENT, folder(false, false, 0, 2));
    }

    @Test public void changingQueryClearsThePreviousEmptyOutcome() {
        acceptEmpty();
        assertEquals(NO_RESULTS, search(true, 0));
        pages.reset();
        assertEquals(LOADING, search(false, 0));
    }

    @Test public void httpAuthenticationAndPermissionAreDistinctFromGenericFailures() {
        pages.fail(pages.begin(false));
        assertEquals(AUTHENTICATION, GridScreenState.search("x", true, 0, pages,
                GridScreenState.Failure.fromHttpStatus(401)));
        assertEquals(PERMISSION, GridScreenState.folder(false, true, 0, 0, pages,
                GridScreenState.Failure.fromHttpStatus(403)));
        assertEquals(ERROR, GridScreenState.search("x", true, 0, pages,
                GridScreenState.Failure.fromHttpStatus(500)));
        // HTTP 200 business errors have no documented permission-code contract.
        assertEquals(ERROR, GridScreenState.search("x", true, 0, pages,
                GridScreenState.Failure.fromHttpStatus(200)));
    }
}
