package com.fnphoto.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fnphoto.tv.api.HttpClientProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Real Activity/DPAD regression using only an in-process loopback fixture.
 * On a dedicated synthetic emulator, install the matching app and test APKs, then run:
 * am instrument -w -e synthetic true -e class com.fnphoto.tv.EmptyStateDeviceTest
 * com.fnphoto.tv.test/androidx.test.runner.AndroidJUnitRunner
 * Screenshots are written to externalFilesDir("empty-state-evidence").
 * This does not establish compatibility with a real NAS or physical remote.
 */
public class EmptyStateDeviceTest {
    private static final String TIMELINE = "/p/api/v1/gallery/timeline";
    private static final String PERSON = "/p/api/v1/ai-person/photoLibrary/timeLine";
    private static final String SEARCH = "/p/api/v1/photo/search";
    private static final String FILES = "/p/api/v1/folder_view/getFileList";
    private Instrumentation instrumentation;
    private MainActivity activity;
    private Activity extraActivity;
    private Fixture fixture;
    private SharedPreferences prefs;
    private Map<String, ?> savedPrefs;

    @Before public void launchSyntheticEmptyLibrary() throws Exception {
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
        assertTrue(prefs.edit().clear().putString("nas_url", fixture.baseUrl())
                .putString("api_token", "synthetic-empty-state")
                .putString("secret", "synthetic-empty-state").commit());
        instrumentation.setInTouchMode(false);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> title(activity).equals("图库中还没有照片或视频") && activity.hasWindowFocus());
    }

    @After public void restoreSyntheticDevice() throws Exception {
        try {
            if (fixture != null) fixture.release();
            if (extraActivity != null) ui(() -> { extraActivity.finish(); return null; });
            if (activity != null) ui(() -> { activity.finish(); return null; });
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
                            new HashSet<>((java.util.Set<String>) value));
                }
                assertTrue(editor.commit());
            }
        } finally { MediaListHolder.clear(); }
    }

    @Test public void displayedNameAndEveryRootEmptyStateAreReadableAndActionable() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertEquals("Application identity must remain unchanged", "com.fnphoto.tv", context.getPackageName());
        assertEquals("飞牛相册", context.getApplicationInfo().loadLabel(context.getPackageManager()).toString());
        assertEquals("飞牛相册", ui(() -> ((TextView) activity.findViewById(R.id.tv_app_title)).getText().toString()));
        BrowseMode[] modes = { BrowseMode.GALLERY, BrowseMode.ALBUMS, BrowseMode.SHARED,
                BrowseMode.FAVORITES, BrowseMode.FOLDERS, BrowseMode.PEOPLE, BrowseMode.PLACES,
                BrowseMode.RECENT, BrowseMode.PHOTOS, BrowseMode.VIDEOS };
        String[] titles = { "图库中还没有照片或视频", "还没有相册", "还没有共享相册", "还没有收藏",
                "还没有可浏览的文件夹", "暂无人物分类", "暂无地点分类", "最近没有新增内容", "暂无照片", "暂无视频" };
        for (int i = 0; i < modes.length; i++) {
            final BrowseMode mode = modes[i];
            final String expected = titles[i];
            ui(() -> { fragment().loadBrowseMode(mode); return null; });
            await(() -> title(activity).equals(expected));
            assertStateReadable(activity);
            ui(() -> { fragment().focusBrowseContent(); return null; });
            await(() -> action(activity).hasFocus());
            screenshot("root-" + mode.id);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> drawer().isDrawerOpen(GravityCompat.END));
            assertTrue("Empty-state action must put remote focus in navigation",
                    ui(() -> activity.findViewById(R.id.menu_grid).hasFocus()));
            key(KeyEvent.KEYCODE_BACK);
            await(() -> !drawer().isDrawerOpen(GravityCompat.END));
        }
    }

    @Test public void loadingIsDistinctAndCompletionDoesNotStealDrawerFocus() throws Exception {
        fixture.hold(TIMELINE);
        ui(() -> { fragment().loadBrowseMode(BrowseMode.GALLERY); return null; });
        assertTrue("Delayed fixture received the request", fixture.arrived.await(10, TimeUnit.SECONDS));
        await(() -> title(activity).contains("正在加载"));
        assertEquals("Loading keeps navigation available without offering a duplicate request",
                "浏览其他内容", ui(() -> action(activity).getText().toString()));
        screenshot("root-loading");
        key(KeyEvent.KEYCODE_MENU);
        await(() -> drawer().isDrawerOpen(GravityCompat.END));
        fixture.release();
        await(() -> title(activity).equals("图库中还没有照片或视频"));
        assertTrue("Async completion must retain drawer focus",
                ui(() -> activity.findViewById(R.id.menu_grid).hasFocus()));
    }

    @Test public void rootPermissionFailureRetriesWithRemoteAndNeverClaimsEmpty() throws Exception {
        fixture.respond(TIMELINE, 403, "{\"code\":403,\"data\":{\"list\":[]}}");
        ui(() -> { fragment().loadBrowseMode(BrowseMode.GALLERY); return null; });
        await(() -> title(activity).equals("没有访问权限"));
        assertStateReadable(activity);
        ui(() -> { fragment().focusBrowseContent(); return null; });
        await(() -> action(activity).hasFocus());
        screenshot("root-permission-retry");
        int before = fixture.count(TIMELINE);
        fixture.clear(TIMELINE);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> title(activity).equals("图库中还没有照片或视频"));
        assertEquals("One explicit retry", before + 1, fixture.count(TIMELINE));
    }

    @Test public void malformedRootAndPersonResponsesAreErrorsNotEmpty() throws Exception {
        fixture.respond(TIMELINE, 200, "{\"data\":{\"list\":[]}}");
        ui(() -> { fragment().loadBrowseMode(BrowseMode.GALLERY); return null; });
        await(() -> title(activity).equals("加载失败"));
        fixture.respond(PERSON, 200, "{\"code\":9,\"data\":{\"list\":[]}}");
        ui(() -> { fragment().loadPersonTimeline(7, "合成人物"); return null; });
        await(() -> title(activity).equals("加载失败") && fixture.count(PERSON) == 1);
        ui(() -> { fragment().focusBrowseContent(); return null; });
        await(() -> action(activity).hasFocus());
        screenshot("person-business-error");
        fixture.clear(PERSON);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> title(activity).equals("此人物暂无照片"));
        assertEquals(2, fixture.count(PERSON));
        assertEquals("7", android.net.Uri.parse(fixture.lastTarget.get(PERSON)).getQueryParameter("id"));
        screenshot("person-empty");
    }

    @Test public void successfulRetryAndBackFromEmptyDateRestoreVisibleContent() throws Exception {
        fixture.respond(TIMELINE, 503, "{\"code\":503}");
        ui(() -> { fragment().loadBrowseMode(BrowseMode.GALLERY); return null; });
        await(() -> title(activity).equals("加载失败"));
        ui(() -> { fragment().focusBrowseContent(); return null; });
        await(() -> action(activity).hasFocus());
        fixture.respond(TIMELINE, 200, "{\"code\":0,\"data\":{\"list\":["
                + "{\"year\":2026,\"month\":10,\"day\":31,\"itemCount\":1}]}}");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> title(activity).isEmpty()
                && activity.findViewById(R.id.main_content_container).isShown()
                && fragment().getRowsSupportFragment().getVerticalGridView().hasFocus());
        screenshot("retry-restores-timeline");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> title(activity).equals("当前范围暂无照片或视频"));
        screenshot("date-empty");
        key(KeyEvent.KEYCODE_BACK);
        await(() -> title(activity).isEmpty() && activity.findViewById(R.id.main_content_container).isShown());
        assertFalse("Returning from a child empty state keeps the activity open", ui(activity::isFinishing));
        screenshot("back-restores-timeline");
    }

    @Test public void searchDistinguishesPromptNoResultsAndRetryWithoutStealingInput() throws Exception {
        final SearchActivity search = startSearch();
        await(() -> title(search).equals("搜索照片和视频"));
        final EditText input = ui(() -> (EditText) field(search, "editSearch"));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> action(search).hasFocus());
        key(KeyEvent.KEYCODE_DPAD_UP);
        await(input::hasFocus);
        ui(() -> { input.requestFocus(); input.setText("no-match"); return null; });
        await(() -> title(search).equals("未找到搜索结果"));
        assertTrue("Empty result must not steal the editor focus", ui(input::hasFocus));
        assertStateReadable(search);
        screenshot("search-no-results");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> action(search).hasFocus());
        key(KeyEvent.KEYCODE_DPAD_UP);
        await(input::hasFocus);
        fixture.respond(SEARCH, 503, "{\"code\":503}");
        ui(() -> { input.setText("retry-query"); return null; });
        await(() -> title(search).equals("搜索失败"));
        assertTrue("Failure must not steal the editor focus", ui(input::hasFocus));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> action(search).hasFocus());
        screenshot("search-error-retry");
        int before = fixture.count(SEARCH);
        key(KeyEvent.KEYCODE_DPAD_UP);
        await(input::hasFocus);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> action(search).hasFocus());
        assertEquals("Moving focus does not retry", before, fixture.count(SEARCH));
        fixture.clear(SEARCH);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> title(search).equals("未找到搜索结果"));
        assertEquals(before + 1, fixture.count(SEARCH));
        assertEquals("retry-query", android.net.Uri.parse(fixture.lastTarget.get(SEARCH)).getQueryParameter("keyword"));
        assertEquals("0", android.net.Uri.parse(fixture.lastTarget.get(SEARCH)).getQueryParameter("offset"));
        ui(() -> { input.setText(""); return null; });
        await(() -> title(search).equals("搜索照片和视频"));
    }

    @Test public void oldSearchResponseCannotReplaceClearedQueryPrompt() throws Exception {
        final SearchActivity search = startSearch();
        fixture.respond(SEARCH, 503, "{\"code\":503}");
        fixture.hold(SEARCH);
        final EditText input = ui(() -> (EditText) field(search, "editSearch"));
        ui(() -> { input.setText("slow-query"); return null; });
        assertTrue(fixture.arrived.await(10, TimeUnit.SECONDS));
        await(() -> title(search).equals("正在搜索"));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue("Loading has no action or visible grid to take focus", ui(input::hasFocus));
        assertFalse("Hidden action must not gain focus", ui(() -> action(search).hasFocus()));
        screenshot("search-loading");
        ui(() -> { input.setText(""); return null; });
        await(() -> title(search).equals("搜索照片和视频"));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> action(search).hasFocus());
        fixture.release();
        assertTrue(fixture.finished.await(10, TimeUnit.SECONDS));
        // The server finishing alone does not mean Retrofit has posted its callback yet.
        // Drain client workers before the main-thread barrier used by the final assertions.
        await(() -> HttpClientProvider.getClient(search).dispatcher().runningCallsCount() == 0
                && HttpClientProvider.getClient(search).dispatcher().queuedCallsCount() == 0);
        instrumentation.waitForIdleSync();
        assertEquals("Canceled old failure must not replace the input prompt", "搜索照片和视频", ui(() -> title(search)));
        assertTrue("Canceled response must not steal the selected action", ui(() -> action(search).hasFocus()));
    }

    @Test public void searchAuthenticationAndPermissionRetriesAreRemoteReachable() throws Exception {
        final SearchActivity search = startSearch();
        final EditText input = ui(() -> (EditText) field(search, "editSearch"));
        for (int status : new int[] {401, 403}) {
            fixture.respond(SEARCH, status, "{\"code\":" + status + "}");
            String query = "synthetic-status-" + status;
            ui(() -> { input.requestFocus(); input.setText(query); return null; });
            await(() -> title(search).equals(status == 401 ? "登录已失效" : "没有访问权限"));
            assertTrue("Async failure must preserve editor focus", ui(input::hasFocus));
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(() -> action(search).hasFocus());
            screenshot("search-" + status + "-retry");
            int before = fixture.count(SEARCH);
            fixture.clear(SEARCH);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> title(search).equals("未找到搜索结果"));
            assertEquals("One confirm, one retry", before + 1, fixture.count(SEARCH));
            assertEquals(query, android.net.Uri.parse(fixture.lastTarget.get(SEARCH)).getQueryParameter("keyword"));
            assertEquals("0", android.net.Uri.parse(fixture.lastTarget.get(SEARCH)).getQueryParameter("offset"));
        }
        key(KeyEvent.KEYCODE_BACK);
        await(search::isFinishing);
    }

    @Test public void searchDownEntersVisibleResultsInsteadOfHiddenStateAction() throws Exception {
        final SearchActivity search = startSearch();
        final EditText input = ui(() -> (EditText) field(search, "editSearch"));
        final RecyclerView results = ui(() -> (RecyclerView) field(search, "rvResults"));
        fixture.respond(SEARCH, 200, "{\"code\":0,\"data\":{\"list\":["
                + "{\"id\":17,\"fileName\":\"synthetic-focus.jpg\",\"category\":\"photo\"}],\"hasNext\":false}}");
        ui(() -> { input.setText("synthetic-focus"); return null; });
        await(() -> results.isShown() && results.getChildCount() > 0);
        assertTrue("New results must not steal editor focus", ui(input::hasFocus));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> results.hasFocus() && results.getFocusedChild() != null);
        assertFalse("Hidden state action cannot retain focus", ui(() -> action(search).hasFocus()));
        screenshot("search-result-remote-focus");
        key(KeyEvent.KEYCODE_DPAD_UP);
        await(input::hasFocus);
        key(KeyEvent.KEYCODE_BACK);
        await(search::isFinishing);
    }

    @Test public void searchAppendFailureFocusesVisibleRetryAndPreservesOffset() throws Exception {
        final SearchActivity search = startSearch();
        final EditText input = ui(() -> (EditText) field(search, "editSearch"));
        final TextView status = ui(() -> (TextView) field(search, "tvStatus"));
        final RecyclerView results = ui(() -> (RecyclerView) field(search, "rvResults"));
        String nextPage = SEARCH + "?keyword=append-focus&limit=100&offset=1";
        fixture.respond(SEARCH, 200, "{\"code\":0,\"data\":{\"list\":["
                + "{\"id\":17,\"fileName\":\"synthetic-focus.jpg\",\"category\":\"photo\"}],\"hasNext\":true}}");
        fixture.respond(nextPage, 503, "{\"code\":503}");
        ui(() -> { input.setText("append-focus"); return null; });
        await(() -> results.getChildCount() > 0 && status.isShown()
                && status.getText().toString().startsWith("搜索失败"));
        assertTrue("Append failure must not steal editor focus", ui(input::hasFocus));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(status::hasFocus);
        assertFalse("No hidden full-screen retry target", ui(() -> action(search).hasFocus()));
        screenshot("search-append-error-retry");
        int before = fixture.count(SEARCH);
        fixture.respond(nextPage, 200, Fixture.EMPTY);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> !status.isFocusable() && status.getText().toString().equals("已加载 1 个结果"));
        assertEquals(before + 1, fixture.count(SEARCH));
        assertEquals("append-focus", android.net.Uri.parse(fixture.lastTarget.get(SEARCH)).getQueryParameter("keyword"));
        assertEquals("1", android.net.Uri.parse(fixture.lastTarget.get(SEARCH)).getQueryParameter("offset"));
        assertEquals(1, (int) ui(() -> results.getAdapter().getItemCount()));
        ui(() -> { input.requestFocus(); return null; });
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> results.hasFocus() && results.getFocusedChild() != null);
        screenshot("search-append-retry-content");
    }

    @Test public void searchExplicitKeyboardRetainsKeysThenBackRestoresRemoteBrowse() throws Exception {
        assumeTrue("IME visibility diagnostic needs API21+", Build.VERSION.SDK_INT >= 21);
        final SearchActivity search = startSearch();
        final EditText input = ui(() -> (EditText) field(search, "editSearch"));
        fixture.respond(SEARCH, 503, "{\"code\":503}");
        ui(() -> { input.setText("synthetic-keyboard"); return null; });
        await(() -> title(search).equals("搜索失败"));
        final Rect browseFrame = ui(() -> visibleFrame(search));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        System.out.println("Search IME open requested: " + ui(() -> keyboardSnapshot(search)));
        awaitImeShown(true);
        awaitImeFrame(true, search, browseFrame);
        System.out.println("Search IME shown frame=" + ui(() -> visibleFrame(search))
                + "; browse frame=" + browseFrame);
        assertEquals("Opening the keyboard must not type the selected key", "synthetic-keyboard",
                ui(() -> input.getText().toString()));
        screenshot("search-explicit-keyboard");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue("Visible keyboard owns Down, not the retry action", ui(input::hasFocus));
        assertFalse(ui(() -> action(search).hasFocus()));
        key(KeyEvent.KEYCODE_BACK);
        awaitImeShown(false);
        awaitImeFrame(false, search, browseFrame);
        awaitKeyboardBrowse(search, browseFrame);
        assertFalse("First Back hides the keyboard, not Search", ui(search::isFinishing));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> action(search).hasFocus());
        screenshot("search-keyboard-back-remote-retry");
        key(KeyEvent.KEYCODE_DPAD_UP);
        await(input::hasFocus);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitImeShown(true);
        awaitImeFrame(true, search, browseFrame);
        assertEquals("Reopening the keyboard must not type", "synthetic-keyboard",
                ui(() -> input.getText().toString()));
        // Exercise the IME action callback, not a fabricated Activity.onKeyDown event.
        ui(() -> { input.onEditorAction(EditorInfo.IME_ACTION_SEARCH); return null; });
        awaitImeShown(false);
        awaitImeFrame(false, search, browseFrame);
        awaitKeyboardBrowse(search, browseFrame);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> action(search).hasFocus());
        screenshot("search-keyboard-action-remote-retry");
        key(KeyEvent.KEYCODE_BACK);
        await(search::isFinishing);
    }

    @Test public void searchWindowReturnHidesKeyboardAndKeepsQuery() throws Exception {
        assumeTrue("IME visibility diagnostic needs API21+", Build.VERSION.SDK_INT >= 21);
        final SearchActivity search = startSearch();
        final EditText input = ui(() -> (EditText) field(search, "editSearch"));
        fixture.respond(SEARCH, 503, "{\"code\":503}");
        ui(() -> { input.setText("synthetic-window-return"); return null; });
        await(() -> title(search).equals("搜索失败"));
        final Rect browseFrame = ui(() -> visibleFrame(search));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitImeShown(true);
        awaitImeFrame(true, search, browseFrame);
        // A controlled same-app window handoff; this is not a process-death or Home test.
        final Activity cover = instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(cover::hasWindowFocus);
            ui(() -> { cover.finish(); return null; });
            await(search::hasWindowFocus);
            awaitImeShown(false);
            awaitImeFrame(false, search, browseFrame);
            awaitKeyboardBrowse(search, browseFrame);
            assertEquals("Window return must preserve the query", "synthetic-window-return",
                    ui(() -> input.getText().toString()));
            assertTrue("Window return keeps the editor selected without reopening IME", ui(input::hasFocus));
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(() -> action(search).hasFocus());
            screenshot("search-window-return-remote-retry");
            key(KeyEvent.KEYCODE_DPAD_UP);
            await(input::hasFocus);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            awaitImeShown(true);
            awaitImeFrame(true, search, browseFrame);
            key(KeyEvent.KEYCODE_BACK);
            awaitImeShown(false);
            awaitImeFrame(false, search, browseFrame);
            awaitKeyboardBrowse(search, browseFrame);
            key(KeyEvent.KEYCODE_BACK);
            await(search::isFinishing);
        } finally {
            ui(() -> { cover.finish(); return null; });
        }
    }

    private void awaitImeFrame(boolean expected, SearchActivity search, Rect browseFrame) throws Exception {
        // On this dedicated docked-TV-IME fixture, shown=true is only a request flag. The
        // window frame must actually shrink/restore too; screenshots provide pixel evidence.
        // Floating/overlay IMEs need a different device scenario, not a relaxed assertion here.
        int keyboardThreshold = ui(() -> (int) (100 * search.getResources().getDisplayMetrics().density));
        try {
            await(() -> expected
                    ? browseFrame.bottom - visibleFrame(search).bottom > keyboardThreshold
                    : browseFrame.equals(visibleFrame(search)));
            System.out.println("Search IME frame-visible=" + expected + "; "
                    + ui(() -> keyboardSnapshot(search)));
        } catch (AssertionError timeout) {
            throw new AssertionError("IME window frame did not become " + expected + "; "
                    + ui(() -> keyboardSnapshot(search)), timeout);
        }
    }

    private String keyboardSnapshot(SearchActivity search) throws Exception {
        SearchKeyboardState state = (SearchKeyboardState) field(search, "keyboardState");
        return "frame=" + visibleFrame(search) + ", requested=" + field(state, "requested")
                + ", visible=" + field(state, "visible") + ", browse=" + state.routesRemoteKeys();
    }

    private Rect visibleFrame(SearchActivity search) {
        Rect frame = new Rect();
        search.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        return frame;
    }

    private void awaitKeyboardBrowse(SearchActivity search, Rect browseFrame) throws Exception {
        // The system flag switches before the IME hide animation/insets finish. Wait for the
        // actual window AND app ownership to settle, without sending a second navigation key.
        SearchKeyboardState state = ui(() -> (SearchKeyboardState) field(search, "keyboardState"));
        try {
            await(() -> browseFrame.equals(visibleFrame(search)) && state.routesRemoteKeys());
            System.out.println("Search IME hidden; window and browse ownership restored: " + browseFrame);
        } catch (AssertionError timeout) {
            throw new AssertionError("Keyboard browse not restored: " + ui(() ->
                    "frame=" + visibleFrame(search) + ", expected=" + browseFrame
                            + ", requested=" + field(state, "requested")
                            + ", visible=" + field(state, "visible")), timeout);
        }
    }

    @android.annotation.TargetApi(21)
    private void awaitImeShown(boolean expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            // System request flag only: callers also await actual window frame and capture pixels.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation()
                            .executeShellCommand("dumpsys input_method"))))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("mInputShown=" + expected)) return;
                }
            }
            Thread.sleep(100);
        }
        fail("IME visibility did not become " + expected);
    }

    @Test public void folderPermissionFailureRetriesOriginalOffsetThenShowsEmpty() throws Exception {
        fixture.respond(FILES, 403, "{\"code\":403}");
        Context context = instrumentation.getTargetContext();
        extraActivity = instrumentation.startActivitySync(new Intent(context, FolderBrowseActivity.class)
                .putExtra("FOLDER_PATH", "/synthetic").putExtra("FOLDER_NAME", "合成空目录")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final FolderBrowseActivity folder = (FolderBrowseActivity) extraActivity;
        await(() -> title(folder).equals("没有访问权限"));
        assertStateReadable(folder);
        await(() -> action(folder).hasFocus());
        screenshot("folder-permission-retry");
        int before = fixture.count(FILES);
        fixture.clear(FILES);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> title(folder).equals("文件夹为空"));
        assertEquals(before + 1, fixture.count(FILES));
        assertEquals("0", android.net.Uri.parse(fixture.lastTarget.get(FILES)).getQueryParameter("offset"));
        assertEquals("/synthetic", android.net.Uri.parse(fixture.lastTarget.get(FILES)).getQueryParameter("folderPath"));
        screenshot("folder-empty");
        await(() -> action(folder).hasFocus());
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(folder::isFinishing);
    }

    private SearchActivity startSearch() throws Exception {
        extraActivity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), SearchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(extraActivity::hasWindowFocus);
        assertEquals("Search starts in remote browsing, not automatic IME editing",
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                (int) ui(() -> extraActivity.getWindow().getAttributes().softInputMode
                        & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE));
        return (SearchActivity) extraActivity;
    }

    private MainFragment fragment() {
        return (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.main_content_container);
    }

    private DrawerLayout drawer() { return activity.findViewById(R.id.drawer_layout); }

    private TextView tagged(Activity owner, String tag) {
        return owner.getWindow().getDecorView().findViewWithTag(tag);
    }

    private String title(Activity owner) {
        TextView view = tagged(owner, "tv_state_title");
        return view != null && view.isShown() ? view.getText().toString() : "";
    }

    private TextView action(Activity owner) { return tagged(owner, "tv_state_action"); }

    private void assertStateReadable(Activity owner) throws Exception {
        ui(() -> {
            TextView title = tagged(owner, "tv_state_title");
            TextView message = tagged(owner, "tv_state_message");
            TextView action = action(owner);
            assertTrue(title.isShown());
            assertTrue(message.isShown());
            assertFalse(message.getText().toString().trim().isEmpty());
            float scale = owner.getResources().getDisplayMetrics().scaledDensity;
            assertTrue("Title readable at TV distance", title.getTextSize() / scale >= 22);
            assertTrue("Explanation readable at TV distance", message.getTextSize() / scale >= 16);
            assertTrue(action.isShown() && action.isFocusable() && action.isClickable());
            int[] location = new int[2];
            action.getLocationOnScreen(location);
            assertTrue("Action visible inside TV viewport", location[0] >= 0 && location[1] >= 0
                    && location[0] + action.getWidth() <= owner.getWindow().getDecorView().getWidth()
                    && location[1] + action.getHeight() <= owner.getWindow().getDecorView().getHeight());
            return null;
        });
    }

    private <T> T ui(Callable<T> operation) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(operation.call()); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        Throwable last = null;
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (ui(condition)) return; } catch (Throwable failure) { last = failure; }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Activity state", last);
    }

    private void key(int code) {
        // API29's sendKeyDownUpSync separately fills two zero downTimes. A real gesture
        // shares one downTime, including when DOWN processing spans more than a millisecond.
        long downTime = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0);
        instrumentation.sendKeySync(down);
        KeyEvent up = new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0);
        assertEquals("Injected gesture must share downTime", down.getDownTime(), up.getDownTime());
        assertEquals("Injected gesture must share device", down.getDeviceId(), up.getDeviceId());
        System.out.println("Injected remote pair key=" + code + ", device=" + down.getDeviceId()
                + ", downTime=" + down.getDownTime() + ", up.downTime=" + up.getDownTime());
        instrumentation.sendKeySync(up);
        instrumentation.waitForIdleSync();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("empty-state-evidence");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String suffix = bitmap.getWidth() + "x" + bitmap.getHeight() + "-font"
                + instrumentation.getTargetContext().getResources().getConfiguration().fontScale;
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + "-" + suffix + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }

    /** Empty, delayed, malformed and permission responses; no remote data or authentication. */
    private static final class Fixture implements AutoCloseable {
        private static final String EMPTY = "{\"code\":0,\"data\":{\"version\":\"synthetic\",\"list\":[],\"total\":0,\"hasNext\":false}}";
        final ServerSocket server;
        final Map<String, String> lastTarget = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        final Map<String, Reply> replies = new ConcurrentHashMap<>();
        volatile String heldPath;
        volatile CountDownLatch arrived = new CountDownLatch(1);
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile CountDownLatch finished = new CountDownLatch(1);

        Fixture() throws Exception {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        Thread worker = new Thread(() -> serve(socket));
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Exception ignored) { }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }
        int count(String path) { AtomicInteger counter = requests.get(path); return counter == null ? 0 : counter.get(); }
        void respond(String path, int status, String body) { replies.put(path, new Reply(status, body)); }
        void clear(String path) { replies.remove(path); }
        void hold(String path) {
            arrived = new CountDownLatch(1);
            finished = new CountDownLatch(1);
            gate = new CountDownLatch(1);
            heldPath = path;
        }
        void release() { gate.countDown(); heldPath = null; }

        private void serve(Socket socket) {
            boolean held = false;
            try (Socket connection = socket) {
                connection.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String request = reader.readLine();
                if (request == null) return;
                String target = request.split(" ")[1];
                String path = target.split("\\?", 2)[0];
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                lastTarget.put(path, baseUrl() + target);
                AtomicInteger counter = requests.get(path);
                if (counter == null) {
                    AtomicInteger created = new AtomicInteger();
                    AtomicInteger prior = requests.putIfAbsent(path, created);
                    counter = prior == null ? created : prior;
                }
                counter.incrementAndGet();
                Reply reply = replies.get(target);
                if (reply == null) reply = replies.get(path);
                if (reply == null) reply = new Reply(200, EMPTY);
                held = path.equals(heldPath);
                if (held) { arrived.countDown(); gate.await(15, TimeUnit.SECONDS); }
                byte[] body = reply.body.getBytes("UTF-8");
                String header = "HTTP/1.1 " + reply.status + " Synthetic\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(header.getBytes("UTF-8"));
                connection.getOutputStream().write(body);
                connection.getOutputStream().flush();
            } catch (Exception ignored) {
                // Canceled requests are expected in the stale-response scenario.
            } finally { if (held) finished.countDown(); }
        }

        @Override public void close() throws Exception { release(); server.close(); }
        private static final class Reply {
            final int status;
            final String body;
            Reply(int status, String body) { this.status = status; this.body = body; }
        }
    }
}
