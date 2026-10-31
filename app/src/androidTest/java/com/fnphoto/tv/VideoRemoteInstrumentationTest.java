package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.test.platform.app.InstrumentationRegistry;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.video.VideoListener;

import java.lang.reflect.Field;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Opt-in device regression, using only the generated QA clips and the local QA server.
 * Start qa_server.py --profile video --host 127.0.0.1 --port 5666, then run on a
 * dedicated emulator with: am instrument -w -e synthetic true -e class
 * com.fnphoto.tv.VideoRemoteInstrumentationTest com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner
 * Requires clip_a/clip_b of at least 40 seconds. Real NAS endpoints/devices are rejected.
 */
@SuppressWarnings("deprecation")
public class VideoRemoteInstrumentationTest extends InstrumentationTestCase {
    private static final String ENDPOINT = "http://10.0.2.2:5666";
    private MediaDetailActivity activity;
    private SharedPreferences preferences;
    private final Map<String, String> original = new HashMap<>();
    private static final String[] PREF_KEYS = {"nas_url", "api_token", "saved_source", "login_session"};

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated synthetic emulator required", "goldfish".equals(Build.HARDWARE)
                || "ranchu".equals(Build.HARDWARE));
        assertEquals("Pass -e synthetic true to authorize the local QA fixture",
                "true", InstrumentationRegistry.getArguments().getString("synthetic"));
        Context context = getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String endpoint = preferences.getString("nas_url", "");
        assertTrue("Refusing a device configured for a non-fixture NAS",
                endpoint.isEmpty() || ENDPOINT.equals(endpoint));
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : PREF_KEYS) original.put(key, preferences.contains(key) ? preferences.getString(key, "") : null);
        assertTrue(editor.putString("nas_url", ENDPOINT).putString("api_token", "synthetic-remote-test")
                .putString("saved_source", ENDPOINT).putString("login_session", "synthetic-remote-test").commit());
        ui(() -> {
            String photo = ENDPOINT + "/p/api/v1/stream/p/t/152/o/synthetic?size=m";
            MediaListHolder.set(Arrays.asList(
                    new MediaItem("151", "Synthetic video A", "video", null, ENDPOINT + "/p/api/v1/stream/v/151"),
                    new MediaItem("152", "Synthetic photo", "photo", photo, photo),
                    new MediaItem("314", "Synthetic video B", "video", null, ENDPOINT + "/p/api/v1/stream/v/314")));
            return null;
        });
        Intent intent = new Intent(context, MediaDetailActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Opening video after a touchscreen interaction must not lose the first remote arrow.
        getInstrumentation().setInTouchMode(true);
        activity = (MediaDetailActivity) getInstrumentation().startActivitySync(intent);
        waitFor("activity focus", () -> activity.hasWindowFocus());
        waitForPlayer(0);
        assertTrue("Generate QA clips of at least 40 seconds", snapshot().duration >= 40000);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (activity != null) ui(() -> { activity.finish(); return null; });
            getInstrumentation().waitForIdleSync();
            if (preferences != null && !original.isEmpty()) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : PREF_KEYS) {
                    if (original.get(key) == null) editor.remove(key);
                    else editor.putString(key, original.get(key));
                }
                editor.commit();
            }
            MediaListHolder.clear();
        } finally { super.tearDown(); }
    }

    public void testPausedArrowsSeekWithoutSwitchingAndPreservePause() throws Exception {
        pauseAt(5000);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertAt(0, 15000, false);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertAt(0, 5000, false);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(snapshot().playRequested);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        assertFalse(snapshot().playRequested);
    }

    public void testControlFocusAndNextButtonKeepSeekSeparateFromNavigation() throws Exception {
        pauseAt(5000);
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(snapshot().controls);
        assertFocus(R.string.video_play);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocus(R.string.video_forward);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertFocus(R.string.video_play);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocus(R.string.video_next);
        assertAt(0, 5000, false);
        saveScreenshot("controls-next.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("next button opens photo", () -> (Integer) field(activity, "currentIndex") == 1);
        assertNull(ui(() -> field(activity, "player")));
        waitForSwitch();
        // Photo browsing keeps the existing direct left/right behavior.
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitForPlayer(2);
    }

    public void testMediaNavigationIsExplicitAndWorksFromTheControlRow() throws Exception {
        pauseAt(5000);
        key(KeyEvent.KEYCODE_DPAD_UP);
        key(KeyEvent.KEYCODE_MEDIA_NEXT);
        waitFor("MEDIA_NEXT opens photo", () -> (Integer) field(activity, "currentIndex") == 1);
        waitForSwitch();
        key(KeyEvent.KEYCODE_MEDIA_NEXT);
        waitForPlayer(2);
        waitForSwitch();
        key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        waitFor("MEDIA_PREVIOUS opens photo", () -> (Integer) field(activity, "currentIndex") == 1);
    }

    public void testBackClosesControlRowBeforeExiting() throws Exception {
        pauseAt(5000);
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(snapshot().controls);
        key(KeyEvent.KEYCODE_BACK);
        assertFalse(snapshot().controls);
        assertFalse(ui(() -> activity.isFinishing()));
        assertAt(0, 5000, false);
        key(KeyEvent.KEYCODE_BACK);
        waitFor("second Back finishes", () -> activity.isFinishing());
    }

    public void testHeldSeekIsThrottledAndCannotLeakIntoNextPhoto() throws Exception {
        pauseAt(5000);
        long started = SystemClock.uptimeMillis();
        dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 0, started, started);
        assertAt(0, 15000, false);
        dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 1, started, started + 100);
        assertAt(0, 15000, false);
        dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 2, started, started + 250);
        assertAt(0, 25000, false);
        key(KeyEvent.KEYCODE_MEDIA_NEXT);
        waitFor("explicit navigation opens photo", () -> (Integer) field(activity, "currentIndex") == 1);
        waitForSwitch();
        dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 3, started, started + 1000);
        dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, 0, started, started + 1100);
        assertEquals(1, (int) ui(() -> (Integer) field(activity, "currentIndex")));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitForPlayer(2);
    }

    public void testDedicatedTransportKeysAndSeekBoundaries() throws Exception {
        pauseAt(5000);
        key(KeyEvent.KEYCODE_MEDIA_PLAY);
        key(KeyEvent.KEYCODE_MEDIA_PLAY);
        assertTrue(snapshot().playRequested);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        assertFalse(snapshot().playRequested);
        pauseAt(5000);
        key(KeyEvent.KEYCODE_MEDIA_REWIND);
        assertAt(0, 0, false);
        key(KeyEvent.KEYCODE_MEDIA_REWIND);
        assertAt(0, 0, false);
        long duration = snapshot().duration;
        pauseAt(duration - 2000);
        key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        assertAt(0, duration, false);
        key(KeyEvent.KEYCODE_MEDIA_REWIND);
        assertAt(0, duration - 10000, false);
    }

    public void testTouchControlsScrubAndReturnToRemoteFocus() throws Exception {
        pauseAt(5000);
        float[] surface = ui(() -> {
            View view = (View) field(activity, "videoSurface");
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            return new float[] {at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 4f};
        });
        tap(surface[0], surface[1]);
        assertTrue(snapshot().controls);
        float[] track = ui(() -> {
            SeekBar bar = (SeekBar) field(field(activity, "videoControls"), "progress");
            int[] at = new int[2];
            bar.getLocationOnScreen(at);
            return new float[] {at[0] + bar.getWidth() * 0.75f, at[1] + bar.getHeight() / 2f};
        });
        tap(track[0], track[1]);
        long duration = snapshot().duration;
        long expected = ui(() -> {
            SeekBar bar = (SeekBar) field(field(activity, "videoControls"), "progress");
            return (long) (duration * (bar.getProgress() / 1000.0));
        });
        assertTrue("Touch must move the video, not merely display a new thumb", expected > duration / 2);
        assertAt(0, expected, false);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocus(R.string.video_forward);
        assertAt(0, expected, false);
    }

    public void testEndedVideoRewindStaysPausedUntilExplicitPlay() throws Exception {
        long duration = snapshot().duration;
        pauseAt(duration - 1000);
        key(KeyEvent.KEYCODE_MEDIA_PLAY);
        waitFor("natural playback completion", () -> player() != null
                && player().getPlaybackState() == Player.STATE_ENDED);
        assertFalse("Ended video should agree with the displayed Play action", snapshot().playRequested);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertAt(0, duration - 10000, false);
        key(KeyEvent.KEYCODE_MEDIA_PLAY);
        assertTrue(snapshot().playRequested);
        assertEquals(0, snapshot().index);
    }

    private void pauseAt(long position) throws Exception {
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        // Arrange a known position; every asserted transition below uses production key dispatch.
        awaitRenderedSeek(position, () -> ui(() -> { player().seekTo(position); return null; }));
        assertFalse(snapshot().playRequested);
    }

    public void testPausedSeekRendersTheRequestedFrame() throws Exception {
        pauseAt(5000);
        awaitRenderedSeek(15000, () -> { key(KeyEvent.KEYCODE_DPAD_RIGHT); return null; });
        assertAt(0, 15000, false);
        saveScreenshot("seek-15s.png");
    }

    private void awaitRenderedSeek(long position, Callable<Void> action) throws Exception {
        boolean[] rendered = {false};
        SimpleExoPlayer current = ui(() -> player());
        VideoListener listener = new VideoListener() {
            @Override public void onRenderedFirstFrame() { rendered[0] = true; }
        };
        ui(() -> { current.addVideoListener(listener); return null; });
        try {
            action.call();
            waitFor("decoded frame after seeking to " + position, () -> rendered[0]
                    && current.getPlaybackState() == Player.STATE_READY
                    && Math.abs(current.getCurrentPosition() - position) < 300);
        } finally { ui(() -> { current.removeVideoListener(listener); return null; }); }
    }

    private void key(int code) {
        // Go through the window/ViewRoot pipeline so focus navigation is really exercised.
        getInstrumentation().sendKeyDownUpSync(code);
        getInstrumentation().waitForIdleSync();
    }

    private void tap(float x, float y) {
        long time = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { getInstrumentation().sendPointerSync(event); }
            finally { event.recycle(); }
        }
        getInstrumentation().waitForIdleSync();
    }

    private void saveScreenshot(String name) throws Exception {
        Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        if (screenshot == null) return; // Device screenshot support is not the input assertion.
        try {
            File directory = new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),
                    "video-remote-evidence");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } finally { screenshot.recycle(); }
    }

    private void dispatch(int code, int action, int repeat, long downTime, long eventTime) throws Exception {
        ui(() -> {
            assertTrue(activity.dispatchKeyEvent(new KeyEvent(downTime, eventTime, action, code, repeat)));
            return null;
        });
    }

    private void assertAt(int index, long position, boolean playing) throws Exception {
        Snapshot current = snapshot();
        assertEquals("Seek must keep the selected media", index, current.index);
        assertEquals("Playback intent must be preserved", playing, current.playRequested);
        assertEquals("Requested position", position, current.position, 300);
        assertNull("No playback error", current.error);
    }

    private void assertFocus(int stringResource) throws Exception {
        assertEquals(activity.getString(stringResource), ui(() -> {
            View focused = activity.getCurrentFocus();
            return focused instanceof TextView ? ((TextView) focused).getText().toString() : "<no button focus>";
        }));
    }

    private void waitForSwitch() throws Exception {
        waitFor("navigation debounce", () -> (Boolean) field(activity, "canSwitch"));
    }

    private void waitForPlayer(int expectedIndex) throws Exception {
        waitFor("decoded synthetic video at index " + expectedIndex, () -> {
            String error = (String) field(activity, "videoErrorMessage");
            assertNull("Player failed: " + error, error);
            SimpleExoPlayer current = player();
            return (Integer) field(activity, "currentIndex") == expectedIndex && current != null
                    && current.getPlaybackState() == Player.STATE_READY && current.getDuration() > 0
                    && current.getVideoDecoderCounters() != null
                    && current.getVideoDecoderCounters().renderedOutputBufferCount > 0;
        });
    }

    private void waitFor(String description, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 45000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (ui(condition)) return;
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for " + description);
    }

    private SimpleExoPlayer player() throws Exception {
        return (SimpleExoPlayer) field(activity, "player");
    }

    private Snapshot snapshot() throws Exception {
        return ui(() -> {
            Snapshot result = new Snapshot();
            result.index = (Integer) field(activity, "currentIndex");
            SimpleExoPlayer current = player();
            if (current != null) {
                result.position = current.getCurrentPosition();
                result.duration = current.getDuration();
                result.playRequested = current.getPlayWhenReady();
            }
            VideoControls controls = (VideoControls) field(activity, "videoControls");
            result.controls = controls != null && controls.isVisible();
            result.error = (String) field(activity, "videoErrorMessage");
            return result;
        });
    }

    private static final class Snapshot {
        int index;
        long position;
        long duration;
        boolean playRequested;
        boolean controls;
        String error;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private <T> T ui(Callable<T> operation) throws Exception {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        getInstrumentation().runOnMainSync(() -> {
            try { result[0] = operation.call(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] != null) throw (Exception) failure[0];
        @SuppressWarnings("unchecked") T value = (T) result[0];
        return value;
    }
}
