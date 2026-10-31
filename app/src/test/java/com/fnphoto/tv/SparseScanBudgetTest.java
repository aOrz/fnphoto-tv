package com.fnphoto.tv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SparseScanBudgetTest {
    @Test public void fiveEmptyMatchPagesPauseWithoutPretendingTheServerIsExhausted() {
        SparseScanBudget budget = new SparseScanBudget(5);
        assertTrue(budget.start(false));
        for (int page = 1; page <= 5; page++) {
            assertEquals(page < 5, budget.acceptPage(false, true));
        }
        assertTrue(budget.isPaused());
        assertFalse(budget.start(false)); // reselecting a card is not user consent to another scan
        assertTrue(budget.start(true));
        assertFalse(budget.isPaused());
        assertTrue(budget.acceptPage(false, true));
    }

    @Test public void matchingPageAndServerEndStopTheCurrentBatch() {
        SparseScanBudget budget = new SparseScanBudget(5);
        budget.start(true);
        assertFalse(budget.acceptPage(true, true));
        assertFalse(budget.isPaused());
        budget.start(true);
        assertFalse(budget.acceptPage(false, false));
        assertFalse(budget.isPaused());
    }

    @Test public void sparseHundredThousandRowsNeedExplicitResumesAndRemainBounded() {
        SparseScanBudget budget = new SparseScanBudget(5);
        MainPagingState state = new MainPagingState(35);
        int requests = 0, matches = 0, batches = 0;
        boolean explicit = false;
        while (state.hasMore()) {
            assertTrue(budget.start(explicit));
            int batchRequests = 0;
            boolean again;
            do {
                int offset = state.begin();
                List<String> rows = new ArrayList<>();
                for (int i = offset; i < Math.min(100000, offset + 35); i++) rows.add("id" + i);
                state.accept(rows, offset + rows.size() < 100000);
                boolean matched = rows.contains("id99999");
                if (matched) matches++;
                requests++; batchRequests++;
                assertTrue(state.retainedIdCount() <= 70);
                again = budget.acceptPage(matched, state.hasMore());
            } while (again);
            assertTrue(batchRequests <= 5);
            batches++;
            if (state.hasMore()) {
                assertTrue(budget.isPaused());
                assertFalse(budget.start(false));
            }
            explicit = true;
        }
        assertEquals(1, matches);
        assertEquals(2858, requests);
        assertEquals(572, batches);
        assertEquals(100000, state.getOffset());
    }

    @Test public void failureRetryKeepsRawOffsetAndCancellationDropsTheOldBudget() {
        SparseScanBudget budget = new SparseScanBudget(5);
        MainPagingState state = new MainPagingState(2);
        budget.start(true);
        state.begin(); state.accept(Arrays.asList("a", "b"), true);
        budget.acceptPage(false, true);
        assertEquals(2, state.begin()); state.fail();
        budget.start(true);
        assertEquals(2, state.begin());
        state.accept(Arrays.asList("c", "d"), true);
        assertTrue(budget.acceptPage(false, true));
        budget.reset(); state.cancel();
        assertFalse(budget.isPaused());
        assertTrue(budget.start(false));
        assertEquals(4, state.begin());
    }
}
