package com.fnphoto.tv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PhotoWallRowsTest {
    @Test public void aFullMonthWrapsVerticallyAndShowsItsHeaderOnlyOnce() {
        List<Object> dates = objects(31);
        List<PhotoWallRows.Row<Object, String>> result = PhotoWallRows.reflow(
                Collections.singletonList(row("October", dates, false)), 5);
        assertEquals(7, result.size());
        assertEquals("October", result.get(0).header);
        for (int i = 1; i < result.size(); i++) assertNull(result.get(i).header);
        assertEquals(1, result.get(6).items.size());
        assertRowsFit(result, 5);
        assertIdentityOrder(dates, result);
        assertEquals(31, dates.size()); // The source data was not consumed or edited.
    }

    @Test public void monthBoundariesDoNotMergeWhenThePreviousMonthEndsWithAShortRow() {
        Object september = new Object();
        List<Object> october = objects(7);
        List<PhotoWallRows.Row<Object, String>> result = PhotoWallRows.reflow(Arrays.asList(
                row("October", october, false), row("September", Arrays.asList(september), false)), 5);
        assertEquals(3, result.size());
        assertEquals(2, result.get(1).items.size());
        assertEquals("September", result.get(2).header);
        assertSame(september, result.get(2).items.get(0));
    }

    @Test public void narrowThenWideReflowRetainsTheSelectedObjectAndAllOtherIdentities() {
        List<Object> items = objects(31);
        Object selected = items.get(23);
        List<PhotoWallRows.Row<Object, String>> original = Collections.singletonList(row("Month", items, false));
        List<PhotoWallRows.Row<Object, String>> narrow = PhotoWallRows.reflow(original, 3);
        List<PhotoWallRows.Row<Object, String>> wide = PhotoWallRows.reflow(narrow, 6);
        assertIdentityOrder(items, narrow);
        assertIdentityOrder(items, wide);
        assertSame(selected, narrow.get(7).items.get(2));
        assertSame(selected, wide.get(3).items.get(5));
        assertEquals(11, narrow.size());
        assertEquals(6, wide.size());
        assertEquals("Month", wide.get(0).header);
        for (int i = 1; i < wide.size(); i++) assertNull(wide.get(i).header);
    }

    @Test public void pageChunksJoinThePartialRowWithoutMovingOrMergingTheFooter() {
        List<Object> firstPage = objects(35);
        List<Object> secondPage = objects(35);
        PhotoWallRows.Row<Object, String> footer = row("Load status", objects(1), true);
        List<PhotoWallRows.Row<Object, String>> result = PhotoWallRows.reflow(Arrays.asList(
                row("Album", firstPage, false), row(null, secondPage, false), footer), 6);
        assertEquals(13, result.size());
        assertSame(firstPage.get(34), result.get(5).items.get(4));
        assertSame(secondPage.get(0), result.get(5).items.get(5));
        assertEquals(4, result.get(11).items.size());
        assertSame(footer, result.get(12));
        assertEquals(1, footer.items.size());
    }

    @Test public void fixedNoticesAndEmptyHeadersStayInPlaceAsSectionBoundaries() {
        PhotoWallRows.Row<Object, String> notice = row("Incomplete results", objects(1), true);
        PhotoWallRows.Row<Object, String> error = row("Loading failed", Collections.emptyList(), false);
        List<Object> first = objects(2);
        List<Object> second = objects(2);
        List<PhotoWallRows.Row<Object, String>> result = PhotoWallRows.reflow(Arrays.asList(
                notice, row("Photos", first, false), error, row(null, second, false)), 6);
        assertEquals(4, result.size());
        assertSame(notice, result.get(0));
        assertSame(error, result.get(2));
        assertSame(first.get(0), result.get(1).items.get(0));
        assertSame(second.get(0), result.get(3).items.get(0));
        assertEquals(2, result.get(1).items.size());
    }

    @Test public void resizingABoundedLargeWindowNeitherDuplicatesNorDropsItems() {
        List<Object> window = objects(1200);
        List<PhotoWallRows.Row<Object, String>> result = Collections.singletonList(row("Window", window, false));
        for (int columns : new int[] {6, 3, 1, 5, 2, 6}) {
            result = PhotoWallRows.reflow(result, columns);
            assertIdentityOrder(window, result);
            assertRowsFit(result, columns);
            assertTrue(result.size() <= 1200);
        }
    }

    @Test public void emptyWallsStayEmptyAndInvalidColumnCountsAreRejected() {
        assertTrue(PhotoWallRows.reflow(Collections.emptyList(), 3).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> PhotoWallRows.reflow(Collections.emptyList(), 0));
    }

    private static PhotoWallRows.Row<Object, String> row(String header, List<Object> items, boolean fixed) {
        return new PhotoWallRows.Row<>(header, items, fixed);
    }

    private static List<Object> objects(int count) {
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new Object());
        return result;
    }

    private static void assertIdentityOrder(List<Object> expected, List<PhotoWallRows.Row<Object, String>> rows) {
        int index = 0;
        for (PhotoWallRows.Row<Object, String> row : rows) {
            for (Object item : row.items) {
                assertTrue("Unexpected additional item", index < expected.size());
                assertSame(expected.get(index++), item);
            }
        }
        assertEquals(expected.size(), index);
    }

    private static void assertRowsFit(List<PhotoWallRows.Row<Object, String>> rows, int columns) {
        for (PhotoWallRows.Row<Object, String> row : rows) {
            assertTrue("Row still requires horizontal scrolling", row.items.size() <= columns);
        }
    }
}
