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

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRow;
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
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
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
        throw new AssertionError("Timed out waiting for real UI state", last);
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
        volatile String lastAlbumOffset = "";
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
                } else if (target.startsWith("/p/api/v1/album/photos")) {
                    String offset = android.net.Uri.parse(baseUrl() + target).getQueryParameter("offset");
                    lastAlbumOffset = offset; albumRequests.incrementAndGet();
                    int start = Integer.parseInt(offset);
                    body = photos(start, Math.min(35, 70 - start), start + 35 < 70).getBytes("UTF-8");
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
            } catch (Exception ignored) { }
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
        @Override public void close() throws Exception { server.close(); }
    }
}
