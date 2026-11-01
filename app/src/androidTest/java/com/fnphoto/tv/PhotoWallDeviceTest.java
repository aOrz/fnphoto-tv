package com.fnphoto.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRowPresenter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real Leanback layout/focus tests. Run on a dedicated synthetic emulator, never a NAS account. */
public class PhotoWallDeviceTest {
    private Instrumentation instrumentation;
    private MainActivity activity;
    private Activity extraActivity;
    private Fixture fixture;
    private SharedPreferences prefs;
    private Map<String, ?> savedPrefs;

    @Before public void launchSyntheticWall() throws Exception {
        assumeTrue("Dedicated Android emulator required", Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("goldfish"));
        assumeTrue("Explicit synthetic fixture opt-in required", "true".equals(
                InstrumentationRegistry.getArguments().getString("synthetic")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String endpoint = prefs.getString("nas_url", "");
        assertTrue("Refusing to replace a non-fixture NAS account", endpoint.isEmpty()
                || endpoint.startsWith("http://127.0.0.1:")
                || endpoint.equals("http://10.0.2.2:5666")
                || endpoint.equals("http://10.0.2.2:5667"));
        savedPrefs = prefs.getAll();
        fixture = new Fixture();
        prefs.edit().clear().putString("nas_url", fixture.baseUrl())
                .putString("api_token", "synthetic-wall-test")
                .putString("secret", "synthetic-wall-test").commit();
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> rows().size() > 3);
        await(() -> rowHolder(0) != null && rowHolder(0).getGridView().getWidth() > 0);
    }

    @After public void finishSyntheticWall() throws Exception {
        if (extraActivity != null) onMain(() -> { extraActivity.finish(); return null; });
        if (activity != null) onMain(() -> { activity.finish(); return null; });
        if (instrumentation != null) instrumentation.waitForIdleSync();
        if (fixture != null) fixture.close();
        if (prefs != null && savedPrefs != null) {
            SharedPreferences.Editor editor = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : savedPrefs.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof java.util.Set) editor.putStringSet(entry.getKey(),
                        new java.util.HashSet<>((java.util.Set<String>) value));
            }
            editor.commit();
        }
    }

    @Test public void monthWrapsAndRemoteMovesWithoutHorizontalPanning() throws Exception {
        onMain(() -> {
            int count = 0, headers = 0;
            int columns = layout().columns;
            for (int i = 0; i < rows().size(); i++) {
                ListRow row = (ListRow) rows().get(i);
                assertTrue("Every month row fits its column budget", row.getAdapter().size() <= columns);
                count += row.getAdapter().size();
                if (row.getHeaderItem() != null) headers++;
            }
            assertEquals("31 October days plus two September days", 33, count);
            assertEquals("Each month gets one header", 2, headers);
            select(0, 0);
            return null;
        });
        await(() -> rowHolder(0).getGridView().hasFocus());
        int[] originalLefts = onMain(() -> assertRowFits(0));
        int columns = originalLefts.length;
        for (int i = 1; i < columns; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> rowHolder(0).getSelectedPosition() == columns - 1);
        onMain(() -> { assertArrayEquals(originalLefts, assertRowFits(0)); return null; });
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        onMain(() -> { assertArrayEquals("Right edge must not pan", originalLefts, assertRowFits(0)); return null; });
        screenshot("timeline-last-column");
        for (int i = 0; i < 4; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> fragment().getSelectedPosition() > 0);
        onMain(() -> {
            int selected = fragment().getSelectedPosition();
            assertRowFits(selected);
            View focus = activity.getCurrentFocus();
            assertNotNull(focus);
            int[] location = new int[2];
            focus.getLocationOnScreen(location);
            assertTrue("Vertical navigation keeps focused card on screen", location[1] >= 0
                    && location[1] < activity.getWindow().getDecorView().getHeight());
            return null;
        });
        screenshot("timeline-scrolled");
    }

    @Test public void resizePreservesPagedItemsFocusFooterAndRawOffset() throws Exception {
        onMain(() -> { fragment().loadPhotosByAlbum("1", longTitle()); return null; });
        await(() -> media().size() == 35 && field(fragment(), "pageFooter") != null);
        onMain(() -> { select(1, 1); return null; });
        await(() -> rowHolder(1) != null && rowHolder(1).getSelectedPosition() == 1);
        Object selected = onMain(() -> rowHolder(1).getSelectedItem());
        List<MediaItem> before = onMain(() -> new ArrayList<>(media()));
        Object paging = onMain(() -> field(fragment(), "photoPaging"));
        Object footer = onMain(() -> field(fragment(), "pageFooter"));
        int requests = fixture.albumRequests.get();
        int originalColumns = onMain(() -> layout().columns);
        int originalWidth = onMain(() -> activity.findViewById(R.id.main_content_container).getWidth());
        onMain(() -> {
            View container = activity.findViewById(R.id.main_content_container);
            ViewGroup.LayoutParams params = container.getLayoutParams();
            params.width = Math.min(Math.round(originalWidth * 0.63f), Math.round(520
                    * activity.getResources().getDisplayMetrics().density));
            container.setLayoutParams(params);
            return null;
        });
        await(() -> layout().columns < originalColumns);
        await(() -> field(fragment(), "selectedWallItem") == selected
                && rowHolder(fragment().getSelectedPosition()) != null
                && rowHolder(fragment().getSelectedPosition()).getSelectedItem() == selected);
        onMain(() -> {
            assertSame("Paging state survives resize", paging, field(fragment(), "photoPaging"));
            assertSame("Footer action survives resize", footer, field(fragment(), "pageFooter"));
            assertEquals(before.size(), media().size());
            for (int i = 0; i < before.size(); i++) assertSame(before.get(i), media().get(i));
            assertRowFits(fragment().getSelectedPosition());
            return null;
        });
        assertEquals("Resize must not request another server page", requests, fixture.albumRequests.get());
        screenshot("album-narrow-long-title");
        onMain(() -> {
            View container = activity.findViewById(R.id.main_content_container);
            ViewGroup.LayoutParams params = container.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            container.setLayoutParams(params);
            return null;
        });
        await(() -> layout().columns == originalColumns);
        await(() -> rowHolder(fragment().getSelectedPosition()) != null
                && rowHolder(fragment().getSelectedPosition()).getSelectedItem() == selected);
        onMain(() -> { select(34 / layout().columns, 34 % layout().columns); return null; });
        await(() -> media().size() == 70);
        assertEquals("Crossing prefetch boundary requests exactly the next 35", requests + 1,
                fixture.albumRequests.get());
        onMain(() -> {
            assertSame(before.get(0), media().get(0));
            assertEquals("35", fixture.lastAlbumOffset);
            for (int i = 0; i < rows().size(); i++) {
                assertTrue(((ListRow) rows().get(i)).getAdapter().size() <= layout().columns);
            }
            assertRowFits(fragment().getSelectedPosition());
            return null;
        });
        screenshot("album-second-page");
    }

    @Test public void footerStaysTextOnlyWhileLoadingAndAtEndOfAlbum() throws Exception {
        onMain(() -> { fragment().loadPhotosByAlbum("1", "Synthetic footer"); return null; });
        awaitFirstPhoto(35);
        fixture.albumGate = new CountDownLatch(1);
        int before = fixture.albumRequests.get();
        onMain(() -> { select(rows().size() - 1, 0); return null; });
        await(() -> "__page_action".equals(focusedTag(R.id.media_item_id))
                && fixture.albumRequests.get() == before + 1);
        onMain(() -> { assertTextFooter("正在加载"); return null; });
        // Repeated input while the footer owns demand must not duplicate the in-flight page.
        for (int i = 0; i < 5; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(before + 1, fixture.albumRequests.get());
        screenshot("footer-loading-text-only");
        fixture.albumGate.countDown();
        await(() -> media().size() == 70 && !((MainPagingState) field(fragment(), "photoPaging")).hasMore());
        await(() -> "photo".equals(focusedTag(R.id.media_item_type)));
        onMain(() -> { select(rows().size() - 1, 0); return null; });
        await(() -> "__page_action".equals(focusedTag(R.id.media_item_id)));
        onMain(() -> { assertTextFooter("已到末尾"); return null; });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("Confirmed end must not request another page", before + 1, fixture.albumRequests.get());
        screenshot("footer-album-end-text-only");
    }

    @Test public void textFooterRetainsExplicitRetryAndOriginalOffset() throws Exception {
        onMain(() -> { fragment().loadPhotosByAlbum("1", "Synthetic retry"); return null; });
        awaitFirstPhoto(35);
        fixture.albumMode = "error";
        int before = fixture.albumRequests.get();
        onMain(() -> { select(rows().size() - 1, 0); return null; });
        await(() -> ((MainPagingState) field(fragment(), "photoPaging")).hasFailed()
                && "__page_action".equals(focusedTag(R.id.media_item_id)));
        onMain(() -> { assertTextFooter("确定重试"); return null; });
        assertEquals("35", fixture.lastAlbumOffset);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("Focus alone must not retry a failure", before + 1, fixture.albumRequests.get());
        screenshot("footer-retry-text-only");
        fixture.albumMode = "normal";
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> media().size() == 70);
        assertEquals("Retry must reuse the failed raw offset", "35", fixture.lastAlbumOffset);
        assertEquals(before + 2, fixture.albumRequests.get());
    }

    /** Called on the UI thread with the footer row attached. */
    private void assertTextFooter(String expected) throws Exception {
        ListRow footer = (ListRow) field(fragment(), "pageFooter");
        assertNull("No misleading permanent loading header", footer.getHeaderItem());
        int index = rows().indexOf(footer);
        HorizontalGridView grid = rowHolder(index).getGridView();
        ItemBridgeAdapter.ViewHolder holder = (ItemBridgeAdapter.ViewHolder)
                grid.findViewHolderForAdapterPosition(0);
        assertNotNull("Footer must be attached", holder);
        View view = holder.getViewHolder().view;
        assertTrue("Paging status is text, never a photo placeholder", view instanceof TextView);
        TextView status = (TextView) view;
        assertTrue(status.getText().toString(), status.getText().toString().contains(expected));
        assertEquals(View.VISIBLE, status.getVisibility());
        assertTrue("Retry/continue remains remote focusable", status.isFocusable());
        // Unlike CardPresenter's info overlay, text visibility must not depend on focus.
        status.clearFocus();
        assertEquals(View.VISIBLE, status.getVisibility());
        assertTrue(status.getText().toString().contains(expected));
        assertTrue(status.requestFocus());
    }

    @Test public void searchAndFolderUseMeasuredSpansAndKeepRemoteFocus() throws Exception {
        Context context = instrumentation.getTargetContext();
        extraActivity = instrumentation.startActivitySync(new Intent(context, SearchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final SearchActivity search = (SearchActivity) extraActivity;
        onMain(() -> { ((EditText) field(search, "editSearch")).setText("synthetic"); return null; });
        await(() -> ((RecyclerView) field(search, "rvResults")).getChildCount() >= 2);
        final RecyclerView searchGrid = onMain(() -> (RecyclerView) field(search, "rvResults"));
        checkRecyclerRemote(searchGrid);
        screenshot("search-measured-grid");
        onMain(() -> { search.finish(); return null; });
        extraActivity = instrumentation.startActivitySync(new Intent(context, FolderBrowseActivity.class)
                .putExtra("FOLDER_PATH", "/synthetic").putExtra("FOLDER_NAME", longTitle())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final FolderBrowseActivity folder = (FolderBrowseActivity) extraActivity;
        await(() -> ((RecyclerView) field(folder, "rvPhotos")).getChildCount() >= 2);
        final RecyclerView folderGrid = onMain(() -> (RecyclerView) field(folder, "rvPhotos"));
        checkRecyclerRemote(folderGrid);
        screenshot("folder-measured-grid-long-title");
    }

    @Test public void ordinaryAlbumStartsAtFirstPhotoAndKeepsDetailAndPagingFocus() throws Exception {
        checkAlbumEntry(false);
    }

    @Test public void sharedAlbumStartsAtFirstPhotoAndKeepsDetailAndPagingFocus() throws Exception {
        checkAlbumEntry(true);
    }

    private void checkAlbumEntry(boolean shared) throws Exception {
        openAlbumDirectory(shared);
        selectLateAlbum();
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitFirstPhoto(shared ? 50 : 35);
        screenshot(shared ? "shared-album-first-photo" : "album-first-photo");
        onMain(() -> { select(1, 1); return null; });
        await(() -> String.valueOf(layout().columns + 2).equals(focusedTag(R.id.media_item_id)));
        String selected = onMain(() -> focusedTag(R.id.media_item_id));
        assertNotEquals("Detail test must not use the first photo", "1", selected);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MediaDetailActivity.class.getName(), null, false);
        try {
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            extraActivity = instrumentation.waitForMonitorWithTimeout(monitor, 10000);
            assertNotNull("DPAD opens real media detail", extraActivity);
            key(KeyEvent.KEYCODE_BACK);
            await(() -> activity.hasWindowFocus() && selected.equals(focusedTag(R.id.media_item_id)));
            extraActivity = null;
        } finally { instrumentation.removeMonitor(monitor); }
        int boundary = shared ? 49 : 34;
        onMain(() -> { select(boundary / layout().columns, boundary % layout().columns); return null; });
        await(() -> media().size() == 70 && String.valueOf(boundary + 1).equals(focusedTag(R.id.media_item_id)));
        onMain(() -> {
            assertEquals("Appending must not reset focus", String.valueOf(boundary + 1), focusedTag(R.id.media_item_id));
            return null;
        });
        key(KeyEvent.KEYCODE_BACK);
        await(() -> (shared ? "shared_album" : "album").equals(focusedTag(R.id.media_item_type)));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitFirstPhoto(shared ? 50 : 35);
    }

    @Test public void albumLoadingEmptyAndErrorKeepVisibleStateFocusThenRetryFirstPhoto() throws Exception {
        for (boolean shared : new boolean[] { false, true }) {
            openAlbumDirectory(shared);
            selectLateAlbum();
            fixture.albumMode = "empty";
            fixture.albumGate = new CountDownLatch(1);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> activity.findViewById(R.id.browse_state_container).isShown());
            onMain(() -> { assertFalse("Loading has no focused hidden photo", "photo".equals(focusedTag(R.id.media_item_type))); return null; });
            fixture.albumGate.countDown();
            await(() -> activity.hasBrowseStateFocus() && !((MainPagingState) field(fragment(), "photoPaging")).isLoading());
            key(KeyEvent.KEYCODE_BACK);
            await(() -> (shared ? "shared_album" : "album").equals(focusedTag(R.id.media_item_type)));
            fixture.albumMode = "error";
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> activity.hasBrowseStateFocus() && ((MainPagingState) field(fragment(), "photoPaging")).hasFailed());
            onMain(() -> { assertNull("Error must not focus a media card", focusedTag(R.id.media_item_id)); return null; });
            fixture.albumMode = "normal";
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            awaitFirstPhoto(shared ? 50 : 35);
        }
    }

    @Test public void delayedAlbumCannotStealDrawerFocusOrApplyAfterBack() throws Exception {
        for (boolean shared : new boolean[] { false, true }) {
            openAlbumDirectory(shared);
            selectLateAlbum();
            fixture.albumGate = new CountDownLatch(1);
            int initialRequests = fixture.albumRequests.get();
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> fixture.albumRequests.get() > initialRequests);
            key(KeyEvent.KEYCODE_MENU);
            await(() -> !activity.canFocusBrowseContent());
            fixture.albumGate.countDown();
            await(() -> media().size() == (shared ? 50 : 35));
            Thread.sleep(300);
            onMain(() -> {
                assertFalse(activity.canFocusBrowseContent());
                assertFalse("Late album must not focus through drawer", "photo".equals(focusedTag(R.id.media_item_type)));
                return null;
            });
            key(KeyEvent.KEYCODE_BACK);
            await(() -> activity.canFocusBrowseContent());
            key(KeyEvent.KEYCODE_BACK);
            await(() -> (shared ? "shared_album" : "album").equals(focusedTag(R.id.media_item_type)));
            fixture.albumGate = new CountDownLatch(1);
            String selected = onMain(() -> focusedTag(R.id.media_item_id));
            int before = fixture.albumRequests.get();
            int completed = fixture.albumResponses.get();
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> fixture.albumRequests.get() > before);
            key(KeyEvent.KEYCODE_BACK);
            await(() -> (shared ? "shared_album" : "album").equals(focusedTag(R.id.media_item_type))
                    && selected.equals(focusedTag(R.id.media_item_id)));
            screenshot(shared ? "shared-album-back-during-load" : "album-back-during-load");
            fixture.albumGate.countDown();
            await(() -> fixture.albumResponses.get() > completed);
            instrumentation.waitForIdleSync();
            onMain(() -> {
                assertFalse((Boolean) field(fragment(), "isPhotoListView"));
                assertEquals(selected, focusedTag(R.id.media_item_id));
                return null;
            });
        }
    }

    @Test public void initialAlbumHandoffSurvivesReflowBeforeItsPostedFocusTask() throws Exception {
        openAlbumDirectory(false);
        selectLateAlbum();
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitFirstPhoto(35);
        onMain(() -> {
            // Model the exact first-content continuation: loading moved focus to the menu,
            // then a measured-width callback replaces the queued handoff before it executes.
            activity.focusBrowseMenuButton();
            Field pending = MainFragment.class.getDeclaredField("initialPhotoFocusPending");
            pending.setAccessible(true);
            pending.setBoolean(fragment(), true);
            java.lang.reflect.Method first = MainFragment.class.getDeclaredMethod("focusFirstPhotoAfterLoad");
            first.setAccessible(true);
            first.invoke(fragment());
            java.lang.reflect.Method reflow = MainFragment.class.getDeclaredMethod("updateWallWidth", int.class);
            reflow.setAccessible(true);
            reflow.invoke(fragment(), Math.round(activity.findViewById(R.id.main_content_container).getWidth() * 0.63f));
            return null;
        });
        awaitFirstPhoto(35);
    }

    private void openAlbumDirectory(boolean shared) throws Exception {
        fixture.albumMode = "normal";
        onMain(() -> { if (shared) fragment().loadSharedAlbums(); else fragment().loadAlbums(); return null; });
        await(() -> rows().size() > 4 && rowHolder(0) != null);
        await(() -> (shared ? "shared_album" : "album").equals(focusedTag(R.id.media_item_type)));
    }

    private void selectLateAlbum() throws Exception {
        onMain(() -> { select(3, 2); return null; });
        await(() -> rowHolder(3) != null && rowHolder(3).getSelectedPosition() == 2
                && rowHolder(3).getGridView().hasFocus());
    }

    private void awaitFirstPhoto(int count) throws Exception {
        await(() -> media() != null && media().size() == count);
        await(() -> "photo".equals(focusedTag(R.id.media_item_type))
                && "1".equals(focusedTag(R.id.media_item_id))
                && fragment().getSelectedPosition() == 0 && rowHolder(0).getSelectedPosition() == 0);
        onMain(() -> { assertEquals("First photo has actual focus", "1", focusedTag(R.id.media_item_id)); return null; });
    }

    private String focusedTag(int tag) {
        View view = activity.getCurrentFocus();
        while (view != null) {
            Object value = view.getTag(tag);
            if (value instanceof String) return (String) value;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return null;
    }

    private void checkRecyclerRemote(RecyclerView grid) throws Exception {
        int spans = onMain(() -> {
            GridLayoutManager manager = (GridLayoutManager) grid.getLayoutManager();
            assertFalse("Wall only scrolls vertically", manager.canScrollHorizontally());
            for (int i = 0; i < manager.getSpanCount(); i++) {
                View card = manager.findViewByPosition(i);
                assertNotNull("All first-row spans are attached", card);
                assertTrue(card.getLeft() >= grid.getPaddingLeft());
                assertTrue(card.getRight() <= grid.getWidth() - grid.getPaddingRight());
                View image = card.findViewById(R.id.iv_photo);
                assertNotNull(image);
                assertEquals("Image follows measured cell aspect", Math.round(image.getWidth() * 9f / 16f),
                        image.getHeight());
            }
            assertTrue(manager.findViewByPosition(0).requestFocus());
            return manager.getSpanCount();
        });
        for (int i = 1; i < spans; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> grid.getChildAdapterPosition(grid.getFocusedChild()) == spans - 1);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> grid.getChildAdapterPosition(grid.getFocusedChild()) >= spans);
    }

    private MainFragment fragment() {
        return (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.main_content_container);
    }
    private ArrayObjectAdapter rows() throws Exception { return (ArrayObjectAdapter) field(fragment(), "mRowsAdapter"); }
    private PhotoWallLayout layout() throws Exception { return (PhotoWallLayout) field(fragment(), "wallLayout"); }
    private List<MediaItem> media() throws Exception { return (List<MediaItem>) field(fragment(), "currentMediaList"); }
    private ListRowPresenter.ViewHolder rowHolder(int row) {
        if (fragment() == null || fragment().getRowsSupportFragment() == null) return null;
        return (ListRowPresenter.ViewHolder) fragment().getRowsSupportFragment().getRowViewHolder(row);
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private void select(int row, int column) {
        fragment().getRowsSupportFragment().setSelectedPosition(row, false,
                new ListRowPresenter.SelectItemViewHolderTask(column));
        fragment().getRowsSupportFragment().getVerticalGridView().requestFocus();
    }
    private int[] assertRowFits(int row) throws Exception {
        ListRowPresenter.ViewHolder holder = rowHolder(row);
        assertNotNull("Selected row must be attached", holder);
        HorizontalGridView grid = holder.getGridView();
        int count = ((ListRow) rows().get(row)).getAdapter().size();
        int[] lefts = new int[count];
        for (int i = 0; i < count; i++) {
            View card = grid.getLayoutManager().findViewByPosition(i);
            assertNotNull("Every card in the row is visible, including the last", card);
            lefts[i] = card.getLeft();
            assertTrue("Card starts inside row", card.getLeft() >= grid.getPaddingLeft() - 1);
            assertTrue("Card ends inside row", card.getRight() <= grid.getWidth() - grid.getPaddingRight() + 1);
            float zoomedLeft = card.getLeft() - card.getWidth() * (card.getScaleX() - 1f) / 2f;
            float zoomedRight = card.getRight() + card.getWidth() * (card.getScaleX() - 1f) / 2f;
            assertTrue("Focus zoom stays in viewport", zoomedLeft >= 0 && zoomedRight <= grid.getWidth());
        }
        return lefts;
    }
    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 30000;
        Throwable last = null;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try { if (onMain(condition)) return; } catch (Throwable error) { last = error; }
            Thread.sleep(100);
        }
        String state = onMain(() -> "focusedType=" + focusedTag(R.id.media_item_type)
                + ", focusedId=" + focusedTag(R.id.media_item_id)
                + ", selectedRow=" + fragment().getSelectedPosition()
                + ", mediaCount=" + (media() == null ? -1 : media().size()));
        screenshot("timeout-state");
        throw new AssertionError("Timed out waiting for real UI state: " + state, last);
    }
    private void key(int key) throws Exception {
        instrumentation.sendKeyDownUpSync(key);
        instrumentation.waitForIdleSync();
        Thread.sleep(250);
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("wall-evidence");
        assertNotNull(directory);
        directory.mkdirs();
        String suffix = bitmap.getWidth() + "x" + bitmap.getHeight() + "-"
                + instrumentation.getTargetContext().getResources().getDisplayMetrics().densityDpi
                + "-font" + instrumentation.getTargetContext().getResources().getConfiguration().fontScale;
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + "-" + suffix + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }
    private static String longTitle() {
        StringBuilder name = new StringBuilder("合成超长相册名称");
        for (int i = 0; i < 20; i++) name.append("0123456789abcdefghijklmnopqrstuvwxyz");
        return name.toString();
    }

    /** No auth or remote NAS: a loopback-only deterministic 33-date / 70-photo fixture. */
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger albumRequests = new AtomicInteger();
        final AtomicInteger albumResponses = new AtomicInteger();
        volatile String lastAlbumOffset = "";
        volatile String albumMode = "normal";
        volatile CountDownLatch albumGate;
        final byte[] image;
        Fixture() throws Exception {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(38, 83, 113));
            Paint paint = new Paint(); paint.setColor(Color.WHITE); paint.setTextSize(30);
            canvas.drawText("SYNTHETIC TV", 40, 100, paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes); bitmap.recycle(); image = bytes.toByteArray();
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        Thread worker = new Thread(() -> serve(socket)); worker.setDaemon(true); worker.start();
                    } catch (Exception ignored) { }
                }
            });
            thread.setDaemon(true); thread.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void serve(Socket socket) {
            boolean albumRequest = false;
            try (Socket connection = socket) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String first = reader.readLine();
                if (first == null) return;
                String target = first.split(" ")[1];
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                byte[] body;
                String type = "application/json";
                if (target.startsWith("/image") || target.startsWith("/p/api/v1/stream/p/")) {
                    body = image; type = "image/jpeg";
                }
                else if (target.startsWith("/p/api/v1/gallery/timeline")) {
                    StringBuilder days = new StringBuilder();
                    for (int i = 0; i < 33; i++) {
                        if (i > 0) days.append(',');
                        days.append("{\"year\":2026,\"month\":").append(i < 31 ? 10 : 9)
                                .append(",\"day\":").append(i < 31 ? 31 - i : 33 - i)
                                .append(",\"itemCount\":1}");
                    }
                    body = ("{\"code\":0,\"data\":{\"list\":[" + days + "]}}").getBytes("UTF-8");
                } else if (target.startsWith("/p/api/v1/album/list")
                        || target.startsWith("/p/api/v1/album_grant/list_to_me")) {
                    StringBuilder albums = new StringBuilder();
                    for (int i = 1; i <= 36; i++) {
                        if (i > 1) albums.append(',');
                        albums.append("{\"albumId\":").append(i).append(",\"albumName\":\"Synthetic album ")
                                .append(i).append("\",\"posterUrl\":\"/image/album.jpg\",\"photoCount\":70}");
                    }
                    body = ("{\"code\":0,\"data\":{\"total\":36,\"list\":[" + albums + "]}}").getBytes("UTF-8");
                } else if (target.startsWith("/p/api/v1/album/photos")
                        || target.startsWith("/p/api/v1/album/normal/getList")) {
                    albumRequest = true;
                    String offset = android.net.Uri.parse(baseUrl() + target).getQueryParameter("offset");
                    lastAlbumOffset = offset; albumRequests.incrementAndGet();
                    int start = Integer.parseInt(offset);
                    CountDownLatch gate = albumGate;
                    if (gate != null) gate.await(15, TimeUnit.SECONDS);
                    int limit = target.startsWith("/p/api/v1/album/normal/getList") ? 50 : 35;
                    body = ("error".equals(albumMode) ? "{\"code\":500}"
                            : "empty".equals(albumMode) ? photos(0, 0, false)
                            : photos(start, Math.min(limit, 70 - start), start + limit < 70)).getBytes("UTF-8");
                } else if (target.startsWith("/p/api/v1/photo/search")
                        || target.startsWith("/p/api/v1/folder_view/getFileList")) {
                    body = photos(0, 70, false).getBytes("UTF-8");
                } else if (target.startsWith("/p/api/v1/gallery/getList")) {
                    body = photos(0, 1, false).getBytes("UTF-8");
                } else body = "{\"code\":0,\"data\":{\"version\":\"synthetic\",\"list\":[]}}".getBytes("UTF-8");
                String header = "HTTP/1.1 200 OK\r\nContent-Type: " + type
                        + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(header.getBytes("UTF-8"));
                connection.getOutputStream().write(body);
                connection.getOutputStream().flush();
            } catch (Exception ignored) {
            } finally { if (albumRequest) albumResponses.incrementAndGet(); }
        }
        String photos(int start, int count, boolean more) {
            StringBuilder items = new StringBuilder();
            for (int i = start; i < start + count; i++) {
                if (i > start) items.append(',');
                items.append("{\"id\":").append(i + 1)
                        .append(",\"category\":\"photo\",\"fileName\":\"")
                        .append(i == 0 ? longTitle() : "Synthetic " + (i + 1))
                        .append("\",\"photoUUID\":\"synthetic\",\"additional\":{\"thumbnail\":{\"mUrl\":\"/image/")
                        .append(i).append(".jpg\",\"sUrl\":\"/image/").append(i).append(".jpg\"}}}");
            }
            return "{\"code\":0,\"data\":{\"total\":70,\"count\":" + count + ",\"hasNext\":" + more
                    + ",\"list\":[" + items + "]}}";
        }
        @Override public void close() throws Exception { if (albumGate != null) albumGate.countDown(); server.close(); }
    }
}
