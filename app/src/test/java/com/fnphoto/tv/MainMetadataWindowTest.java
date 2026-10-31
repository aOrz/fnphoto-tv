package com.fnphoto.tv;

import com.fnphoto.tv.paging.MetadataLimits;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class MainMetadataWindowTest {
    @Test public void wholePagesStopBeforeCapacityWithoutDiscardingAPartialPage() {
        assertTrue(MainMetadataWindow.fitsNextPage(1155, 35));
        assertFalse(MainMetadataWindow.fitsNextPage(1190, 35));
        assertTrue(MainMetadataWindow.fitsNextPage(1140, 60));
        assertFalse(MainMetadataWindow.fitsNextPage(1200, 60));
        assertFalse(MainMetadataWindow.fitsNextPage(Integer.MAX_VALUE, 35));
    }

    @Test public void unpagedListsAreCopiedAndBoundedNotBackedByTheLargeResponse() {
        List<String> original = new ArrayList<>();
        for (int i = 0; i < 100000; i++) original.add("id" + i);
        List<String> copy = MainMetadataWindow.copyUnpaged(original);
        assertEquals(MetadataLimits.MAX_ITEMS, copy.size());
        original.set(0, "changed");
        original.clear();
        assertEquals("id0", copy.get(0));
        assertEquals(MetadataLimits.MAX_ITEMS, copy.size());
    }

    @Test public void explicitWindowReplacementPreservesAllHundredThousandRawRows() {
        MainPagingState paging = new MainPagingState(35);
        int retained = 0, displayed = 0, windows = 1;
        while (paging.hasMore()) {
            if (!MainMetadataWindow.fitsNextPage(retained, 35)) {
                retained = 0; // the explicit next-window action; cursor stays intact
                windows++;
            }
            int offset = paging.begin();
            List<String> rows = new ArrayList<>();
            for (int i = offset; i < Math.min(100000, offset + 35); i++) rows.add("id" + i);
            int added = paging.accept(rows, offset + rows.size() < 100000).size();
            retained += added;
            displayed += added;
            assertTrue(retained <= MetadataLimits.MAX_ITEMS);
            assertTrue(paging.retainedIdCount() <= 70);
        }
        assertEquals(100000, displayed);
        assertEquals(100000, paging.getOffset());
        assertTrue(windows > 1);
    }
}
