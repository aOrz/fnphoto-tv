package com.fnphoto.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in synthetic Activity/DPAD tests. No NAS and no external fixture server required.
 * Run on a dedicated emulator: am instrument -w -e synthetic true -e class
 * com.fnphoto.tv.SlideshowDeviceTest com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner
 * Pull /sdcard/Android/data/com.fnphoto.tv/files/slideshow-device-evidence into test/.generated/.
 */
@SuppressWarnings("deprecation")
public class SlideshowDeviceTest extends InstrumentationTestCase {
    private MediaDetailActivity activity;
    private SharedPreferences preferences;
    private final Map<String, Object> original = new HashMap<>();
    private static final String[] KEYS = {"nas_url", "api_token", "saved_source", "login_session",
            "slideshow_seconds", "slideshow_loop", "slideshow_videos"};
    private FixtureServer server;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated synthetic emulator required", "goldfish".equals(Build.HARDWARE)
                || "ranchu".equals(Build.HARDWARE));
        assertEquals("Pass -e synthetic true", "true",
                InstrumentationRegistry.getArguments().getString("synthetic"));
        preferences = getInstrumentation().getTargetContext()
                .getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String current = preferences.getString("nas_url", "");
        assertTrue("Refusing a device configured for a non-fixture NAS", current.isEmpty()
                || current.equals("http://10.0.2.2:5666"));
        for (String key : KEYS) original.put(key, preferences.getAll().get(key));
        server = new FixtureServer();
        assertTrue(preferences.edit().putString("nas_url", server.endpoint())
                .putString("api_token", "synthetic-slideshow")
                .putString("saved_source", server.endpoint())
                .putString("login_session", "synthetic-slideshow")
                .putInt("slideshow_seconds", 3).putBoolean("slideshow_loop", false)
                .putBoolean("slideshow_videos", true).commit());
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (activity != null) ui(() -> { activity.finish(); return null; });
            getInstrumentation().waitForIdleSync();
            if (server != null) server.close();
            if (preferences != null && !original.isEmpty()) {
                SharedPreferences.Editor edit = preferences.edit();
                for (String key : KEYS) {
                    Object value = original.get(key);
                    if (value == null) edit.remove(key);
                    else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                    else if (value instanceof Integer) edit.putInt(key, (Integer) value);
                    else edit.putString(key, (String) value);
                }
                assertTrue(edit.commit());
            }
            MediaListHolder.clear();
        } finally { super.tearDown(); }
    }

    public void testMenuDpadChangesAndPersistsAllOptionsWithoutStarting() throws Exception {
        launch(Arrays.asList(photo("1"), photo("2")), null, 0);
        key(KeyEvent.KEYCODE_MENU);
        waitFor("settings dialog", () -> dialog() != null && dialog().isShowing());
        assertDialogFocus("照片间隔：3 秒");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertDialogFocus("照片间隔：5 秒");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertDialogFocus("循环当前范围：关");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertDialogFocus("播放视频：开");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertDialogFocus("播放视频：关（跳过视频）");
        screenshot("settings-dpad.png");
        key(KeyEvent.KEYCODE_BACK);
        assertNull(ui(() -> dialog()));
        assertFalse(ui(() -> state().active()));
        assertEquals(5, preferences.getInt("slideshow_seconds", -1));
        assertTrue(preferences.getBoolean("slideshow_loop", false));
        assertFalse(preferences.getBoolean("slideshow_videos", true));
        assertTrue(ui(() -> activity.getCurrentFocus() != null));
    }

    public void testPhotoPauseWaitsPastIntervalAndResumeAdvances() throws Exception {
        launch(Arrays.asList(photo("1"), photo("2")), null, 0);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(ui(() -> state().running()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(ui(() -> state().paused()));
        SystemClock.sleep(3600);
        assertEquals(0, index());
        screenshot("photo-paused.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("resumed next photo", () -> indexOnUi() == 1);
        assertTrue(ui(() -> state().running()));
        screenshot("photo-resumed-next.png");
    }

    public void testLoopReturnsToFirstOfCompleteSourceAndSinglePhotoWorks() throws Exception {
        assertTrue(preferences.edit().putBoolean("slideshow_loop", true).commit());
        launch(Arrays.asList(photo("1"), photo("2"), photo("3")), null, 2);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("loop reaches actual first item", () -> indexOnUi() == 0);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        screenshot("loop-first-item.png");
        closeActivity();
        launch(Collections.singletonList(photo("single")), null, 0);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(3800);
        assertEquals(0, index());
        assertTrue("Single photo loop remains active", ui(() -> state().running()));
        assertNull(ui(() -> field(activity, "videoErrorMessage")));
    }

    public void testDisabledVideoIsSkippedWithoutOpeningItsStream() throws Exception {
        assertTrue(preferences.edit().putBoolean("slideshow_videos", false).commit());
        MediaItem video = new MediaItem("v", "Synthetic forbidden video", "video", null,
                server.endpoint() + "/must-not-open-video");
        launch(Arrays.asList(photo("1"), video, photo("2")), null, 0);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("next eligible photo skips video", () -> indexOnUi() == 2);
        assertEquals("Skipping must not initialize any video request", 0, server.videoRequests.get());
        assertNull(ui(() -> field(activity, "player")));
        assertNull(ui(() -> field(activity, "fallbackPlayer")));
        screenshot("video-skipped.png");
    }

    public void testLateBoundaryDeliveryCannotAdvancePausedSession() throws Exception {
        DelayedSource source = new DelayedSource();
        launch(Collections.singletonList(photo("1")), source, 0);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("boundary request", () -> source.pending != null);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(ui(() -> state().paused()));
        ui(() -> { source.complete(Arrays.asList(photo("1"), photo("2"))); return null; });
        SystemClock.sleep(3600);
        assertEquals("Late page must not auto-advance while paused", 0, index());
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("explicit resume advances", () -> indexOnUi() == 1);
    }

    public void testHomeStopsSessionAndLatePageCannotAdvance() throws Exception {
        DelayedSource source = new DelayedSource();
        launch(Collections.singletonList(photo("1")), source, 0);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("boundary request", () -> source.pending != null);
        assertTrue("System Home action must be accepted", getInstrumentation().getUiAutomation()
                .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
        waitFor("system Home removes detail window focus", () -> !activity.hasWindowFocus());
        waitFor("onStop cancels automatic playback", () -> !state().active());
        ui(() -> { source.complete(Arrays.asList(photo("1"), photo("2"))); return null; });
        SystemClock.sleep(3500);
        assertFalse(ui(() -> state().active()));
        assertEquals(0, index());
        assertNull(ui(() -> field(activity, "player")));
    }

    public void testAllVideosDisabledEndsWithPersistentStatusAndNoSpinner() throws Exception {
        assertTrue(preferences.edit().putBoolean("slideshow_videos", false)
                .putBoolean("slideshow_loop", true).commit());
        // Initial browse preview has a thumbnail so the pre-existing missing-thumbnail
        // autoplay path is not mistaken for a slideshow request. The final skipped item
        // deliberately has no thumbnail, exercising suppressed playback after Home.
        MediaItem preview = new MediaItem("a", "Synthetic preview", "video",
                server.endpoint() + "/photo/preview", server.endpoint() + "/must-not-open-video/a");
        launchRaw(Arrays.asList(preview, video("b")), null, 0);
        key(KeyEvent.KEYCODE_MENU);
        waitFor("settings dialog", () -> dialog() != null && dialog().isShowing());
        ui(() -> { dialog().getButton(AlertDialog.BUTTON_POSITIVE).requestFocus(); return null; });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("all-video finite loop terminates", () -> !state().active()
                && containsText((View) field(activity, "container"), "当前视频未播放"));
        assertEquals(0, server.videoRequests.get());
        assertTrue(ui(() -> ((ProgressBar) field(activity, "loadingIndicator")).getVisibility() != View.VISIBLE));
        SystemClock.sleep(1000);
        assertTrue(ui(() -> containsText((View) field(activity, "container"), "当前视频未播放")));
        screenshot("all-videos-skipped-status.png");
        assertTrue("System Home action must be accepted", getInstrumentation().getUiAutomation()
                .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
        waitFor("system Home removes detail window focus", () -> !activity.hasWindowFocus());
        waitFor("skipped detail really reaches onStop", () -> Boolean.TRUE.equals(field(activity, "reloadOnStart")));
        Intent returnToDetail = new Intent(getInstrumentation().getTargetContext(), MediaDetailActivity.class);
        returnToDetail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        getInstrumentation().getTargetContext().startActivity(returnToDetail);
        waitFor("return to suppressed video without starting it", () -> activity.hasWindowFocus()
                && containsText((View) field(activity, "container"), "当前视频未播放"));
        SystemClock.sleep(1000);
        assertEquals("Home/return must not play a suppressed video with no thumbnail", 0, server.videoRequests.get());
        assertNull(ui(() -> field(activity, "player")));
        assertNull(ui(() -> field(activity, "fallbackPlayer")));
        assertFalse(ui(() -> state().active()));
        screenshot("all-videos-skipped-return.png");
    }

    public void testSkipBudgetPauseCanEnableVideoAndActuallyRequestPlayback() throws Exception {
        assertTrue(preferences.edit().putBoolean("slideshow_videos", false).commit());
        List<MediaItem> items = new ArrayList<>();
        items.add(photo("start"));
        for (int i = 0; i < 180; i++) items.add(video("skip-" + i));
        launch(items, null, 0);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("finite skip budget pauses", () -> state().paused()
                && containsText((View) field(activity, "container"), "已跳过175项视频"));
        assertEquals(0, server.videoRequests.get());
        screenshot("video-skip-budget-paused.png");
        key(KeyEvent.KEYCODE_MENU);
        waitFor("settings dialog", () -> dialog() != null && dialog().isShowing());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertDialogFocus("播放视频：关（跳过视频）");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertDialogFocus("播放视频：开");
        ui(() -> { dialog().getButton(AlertDialog.BUTTON_POSITIVE).requestFocus(); return null; });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("enabled video actually opens its URL", () -> server.videoRequests.get() > 0);
        // Intentional HTTP404 exercises initialization and persistent error, not codec compatibility.
        waitFor("fixture HTTP404 produces a persistent error", () -> field(activity, "videoErrorMessage") != null);
        assertFalse("An HTTP failure must not leave a running black slideshow", ui(() -> state().running()));
        screenshot("enabled-video-request-error.png");
    }

    private MediaItem video(String id) {
        return new MediaItem(id, "Synthetic video " + id, "video", null,
                server.endpoint() + "/must-not-open-video/" + id);
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private MediaItem photo(String id) {
        String url = server.endpoint() + "/photo/" + id;
        return new MediaItem(id, "Synthetic photo " + id, "photo", url, url);
    }

    private void launch(List<MediaItem> items, MediaListHolder.PageSource source, int index) throws Exception {
        launchRaw(items, source, index);
        waitFor("foreground photo really rendered", () -> {
            ImageView image = (ImageView) field(activity, "imageView");
            return activity.hasWindowFocus() && (Boolean) field(activity, "currentPhotoReady")
                    && image != null && image.getDrawable() instanceof BitmapDrawable;
        });
        assertTrue("Fixture must actually serve a photo", server.photoRequests.get() > 0);
    }

    private void launchRaw(List<MediaItem> items, MediaListHolder.PageSource source, int index) throws Exception {
        ui(() -> { MediaListHolder.set(items, source); return null; });
        Intent intent = new Intent(getInstrumentation().getTargetContext(), MediaDetailActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("CURRENT_INDEX", index);
        getInstrumentation().setInTouchMode(false);
        activity = (MediaDetailActivity) getInstrumentation().startActivitySync(intent);
        waitFor("landscape detail window focused", () -> activity.hasWindowFocus()
                && activity.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                && activity.getWindow().getDecorView().getWidth()
                    > activity.getWindow().getDecorView().getHeight());
    }

    private void closeActivity() throws Exception {
        ui(() -> { activity.finish(); return null; });
        getInstrumentation().waitForIdleSync();
        activity = null;
    }

    private SlideshowState state() throws Exception { return (SlideshowState) field(activity, "slideshow"); }
    private AlertDialog dialog() throws Exception { return (AlertDialog) field(activity, "slideshowDialog"); }
    private int indexOnUi() throws Exception { return (Integer) field(activity, "currentIndex"); }
    private int index() throws Exception { return ui(() -> indexOnUi()); }

    private void assertDialogFocus(String expected) throws Exception {
        assertEquals(expected, ui(() -> {
            View view = dialog().getWindow().getCurrentFocus();
            return view instanceof TextView ? ((TextView) view).getText().toString() : "<no text focus>";
        }));
    }

    private void key(int code) {
        getInstrumentation().sendKeyDownUpSync(code);
        getInstrumentation().waitForIdleSync();
    }

    private void waitFor(String description, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (ui(() -> {
                // API19's portrait launcher can recreate a landscape detail. Assert against
                // the actual resumed Activity, not a destroyed startActivitySync reference.
                for (android.app.Activity resumed : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (resumed instanceof MediaDetailActivity && !resumed.isFinishing())
                        activity = (MediaDetailActivity) resumed;
                }
                return condition.call();
            })) return;
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for " + description);
    }

    private void screenshot(String name) throws Exception {
        // Idle dispatch can precede the next compositor frame on an unaccelerated emulator.
        SystemClock.sleep(400);
        Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Screenshot required for UI evidence", bitmap);
        try {
            Context context = getInstrumentation().getTargetContext();
            File external = context.getExternalFilesDir(null);
            File directory = external == null ? null : new File(external, "slideshow-device-evidence");
            if (directory == null || !(directory.isDirectory() || directory.mkdirs())) {
                // API19 emulator images may have no mounted external volume. Private files
                // remain exportable via adb run-as on this dedicated debug test package.
                directory = new File(context.getFilesDir(), "slideshow-device-evidence");
            }
            assertTrue("A writable screenshot evidence directory is required",
                    directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        } finally { bitmap.recycle(); }
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private <T> T ui(Callable<T> operation) throws Exception {
        Object[] value = new Object[1];
        Throwable[] failure = new Throwable[1];
        getInstrumentation().runOnMainSync(() -> {
            try { value[0] = operation.call(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] != null) throw (Exception) failure[0];
        @SuppressWarnings("unchecked") T result = (T) value[0];
        return result;
    }

    private static final class DelayedSource implements MediaListHolder.PageSource {
        MediaListHolder.PageCallback pending;
        boolean more = true;
        @Override public boolean hasMore() { return more; }
        @Override public void loadMore(MediaListHolder.PageCallback callback) { pending = callback; }
        void complete(List<MediaItem> items) {
            more = false;
            MediaListHolder.PageCallback callback = pending;
            pending = null;
            callback.onLoaded(items, false);
        }
    }

    /** Loopback only; generated bytes and fixed labels, never private files or real credentials. */
    private static final class FixtureServer {
        final AtomicInteger photoRequests = new AtomicInteger();
        final AtomicInteger videoRequests = new AtomicInteger();
        private final ServerSocket socket;
        private final Thread worker;
        private final byte[] png;
        private volatile boolean closed;
        FixtureServer() throws Exception {
            Bitmap bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(25, 70, 105));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE); paint.setTextSize(48);
            canvas.drawText("SYNTHETIC SLIDESHOW QA", 80, 260, paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            bitmap.recycle(); png = bytes.toByteArray();
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(() -> {
                while (!closed) {
                    try (Socket client = socket.accept()) {
                        client.setSoTimeout(3000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                        String line = reader.readLine();
                        if (line == null) continue;
                        boolean photo = line.startsWith("GET /photo/");
                        if (photo) photoRequests.incrementAndGet(); else videoRequests.incrementAndGet();
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { /* Headers discarded. */ }
                        byte[] body = photo ? png : new byte[0];
                        OutputStream out = client.getOutputStream();
                        out.write(((photo ? "HTTP/1.1 200 OK" : "HTTP/1.1 404 Not Found")
                                + "\r\nContent-Type: image/png\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        out.write(body); out.flush();
                    } catch (Exception ignored) { /* close() interrupts accept; no private logs. */ }
                }
            }, "synthetic-slideshow-http");
            worker.setDaemon(true); worker.start();
        }
        String endpoint() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        void close() throws Exception { closed = true; socket.close(); worker.join(4000); }
    }
}
