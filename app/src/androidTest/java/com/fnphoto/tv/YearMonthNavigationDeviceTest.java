package com.fnphoto.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.VerticalGridView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Real Activity/Leanback/DPAD scenarios, using an emulator-only loopback fixture.
 * Install matching app/test APKs and run with -e synthetic true -e class
 * com.fnphoto.tv.YearMonthNavigationDeviceTest. This fixture seeds a synthetic
 * session, so it tests navigation, not login or real NAS compatibility.
 * Screenshots and installed APK SHA-256 are written to year-month-evidence.
 */
public class YearMonthNavigationDeviceTest {
    private Instrumentation instrumentation;
    private MainActivity activity;
    private Activity cover;
    private Activity detail;
    private Fixture fixture;
    private SharedPreferences prefs;
    private Map<String, ?> savedPrefs;

    @Before public void launchSyntheticAlbum() throws Exception {
        assumeTrue("Dedicated Android emulator required", Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("goldfish"));
        assumeTrue("Explicit synthetic fixture opt-in required", "true".equals(
                InstrumentationRegistry.getArguments().getString("synthetic")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String endpoint = prefs.getString("nas_url", "");
        assertTrue("Refusing to replace a private NAS account", endpoint.isEmpty()
                || endpoint.startsWith("http://127.0.0.1:")
                || endpoint.equals("http://10.0.2.2:5666")
                || endpoint.equals("http://10.0.2.2:5667")
                || endpoint.equals("http://10.0.2.2:5668"));
        savedPrefs = prefs.getAll();
        fixture = new Fixture();
        assertTrue(prefs.edit().clear().putString("nas_url", fixture.baseUrl())
                .putString("api_token", "synthetic-year-month")
                .putString("secret", "synthetic-year-month")
                .putString(BrowseSelection.ACCOUNT_KEY, BrowseSelection.accountId(fixture.baseUrl(), "synthetic"))
                .commit());
        instrumentation.setInTouchMode(false);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> fragment() != null && activity.hasWindowFocus());
        ui(() -> { fragment().loadPhotosByAlbum("7001", "合成年月相册"); return null; });
        await(() -> media() != null && media().size() == 35 && "1001".equals(focusedId()));
    }

    @After public void restoreSyntheticDevice() throws Exception {
        try {
            if (fixture != null) fixture.release();
            if (cover != null) ui(() -> { cover.finish(); return null; });
            if (detail != null) ui(() -> { detail.finish(); return null; });
            if (activity != null) ui(() -> { activity.finish(); return null; });
            if (instrumentation != null) ui(() -> {
                // A failed recreation assertion must not leave the replacement Activity
                // running against a closed fixture and leak it into the next scenario.
                for (Stage stage : Stage.values()) {
                    if (stage == Stage.DESTROYED) continue;
                    for (Activity candidate : new ArrayList<>(ActivityLifecycleMonitorRegistry
                            .getInstance().getActivitiesInStage(stage))) {
                        if (candidate instanceof MainActivity || candidate instanceof MediaDetailActivity
                                || candidate instanceof SettingsActivity) candidate.finish();
                    }
                }
                return null;
            });
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

    @Test public void remoteEntryYearMonthBackAndConfirmationUseRealDialog() throws Exception {
        openPickerWithRemote();
        await(() -> pickerDialog().isShowing() && !pickerChoosingMonth());
        assertEquals("选择月份", ui(() -> ((TextView) pickerDialog().getCurrentFocus()).getText().toString()));
        awaitPickerRendered(false);
        screenshot("picker-year");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(this::pickerChoosingMonth);
        awaitPickerRendered(true);
        screenshot("picker-month");
        key(KeyEvent.KEYCODE_BACK);
        await(() -> !pickerChoosingMonth() && pickerDialog().isShowing());
        key(KeyEvent.KEYCODE_BACK);
        await(() -> !pickerDialog().isShowing() && activity.hasWindowFocus());
        assertNull("Cancel leaves the original query intact", ui(() -> field(fragment(), "selectedMonth")));
        assertEquals("1001", ui(() -> media().get(0).getId()));

        openPickerWithRemote();
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(this::pickerChoosingMonth);
        int year = ui(() -> (Integer) field(field(fragment(), "monthPicker"), "year"));
        int month = ui(() -> Integer.parseInt(((TextView) pickerDialog().getCurrentFocus())
                .getText().toString().split(" ")[0]));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> selectedMonth() != null && selectedMonth().year == year && selectedMonth().month == month);
        assertTrue("Return is a reachable top-bar action", ui(() -> activity.findViewById(
                R.id.btn_year_month_return).isShown()));
    }

    @Test public void ordinaryAlbumPausesAfterFivePagesThenFindsSixthPageAndRestoresId() throws Exception {
        ui(() -> { selectPhoto(2); return null; });
        await(() -> "1003".equals(focusedId()));
        int requests = fixture.albumRequests.get();
        choose(2026, 9);
        await(() -> scanPaused() && paging().getOffset() == 175);
        assertEquals("One gesture is bounded to five raw pages", requests + 5, fixture.albumRequests.get());
        assertEquals(0, ui(() -> media().size()).intValue());
        assertEquals("尚未找到匹配内容", ui(this::title));
        assertTrue(ui(this::message).contains("175"));
        assertFalse("Pause must not claim absence", ui(this::title).contains("没有"));
        screenshot("album-five-page-pause");
        continueWithRemote();
        await(() -> media().size() == 35 && "1176".equals(media().get(0).getId()));
        assertEquals("Only the sixth page is needed after explicit continuation", requests + 6,
                fixture.albumRequests.get());
        assertEquals(175, ui(() -> media().get(0).getBrowseRawOffset()).intValue());
        await(() -> "1176".equals(focusedId()));
        awaitFocusedSyntheticImage();
        screenshot("album-sixth-page-month-result");
        activateDrawerAction("year_month_return");
        await(() -> selectedMonth() == null && "1003".equals(focusedId()));
        awaitFocusedSyntheticImage();
        assertEquals("Restoration reloads the original source coordinate", "0", fixture.lastAlbumOffset);
        assertFalse(ui(() -> activity.findViewById(R.id.btn_year_month_return).isShown()));
        screenshot("album-original-stable-id-restored");
        fixture.assertOrdinaryAlbumContract();
    }

    @Test public void emptyMonthRequiresCompletionAndUnknownCaptureDateCannotClaimAbsence() throws Exception {
        choose(2026, 7);
        await(this::scanPaused);
        assertEquals("尚未找到匹配内容", ui(this::title));
        continueWithRemote();
        await(() -> title().equals("2026年07月没有匹配照片或视频"));
        assertFalse(ui(() -> paging().hasMore()));
        screenshot("complete-empty-month");
        fixture.unknownCaptureDate = true;
        choose(2026, 7);
        await(this::scanPaused);
        continueWithRemote();
        await(() -> title().equals("日期信息不完整"));
        assertTrue(ui(this::message).contains("不能确认"));
        screenshot("unknown-capture-date-incomplete");
    }

    @Test public void oldMonthResponseCannotReplaceNewMonthOrStealDrawerFocus() throws Exception {
        fixture.holdNextAlbum();
        int requests = fixture.albumRequests.get();
        choose(2026, 9);
        assertTrue("Old month request entered fixture", fixture.arrived.await(10, TimeUnit.SECONDS));
        choose(2026, 10);
        await(() -> selectedMonth().month == 10 && media().size() == 35);
        key(KeyEvent.KEYCODE_MENU);
        await(() -> drawer().isDrawerOpen(GravityCompat.END));
        int responses = fixture.albumResponses.get();
        fixture.release();
        await(() -> fixture.albumResponses.get() > responses);
        instrumentation.waitForIdleSync();
        assertEquals("Replacement owns the only new request", requests + 2, fixture.albumRequests.get());
        assertEquals(10, ui(() -> selectedMonth().month).intValue());
        assertEquals("1001", ui(() -> media().get(0).getId()));
        assertTrue("Late response cannot move focus outside drawer", ui(() -> activity.findViewById(
                R.id.menu_grid).hasFocus()));
        screenshot("replacement-month-drawer-focus");
        key(KeyEvent.KEYCODE_BACK);
        await(() -> !drawer().isDrawerOpen(GravityCompat.END));
    }

    @Test public void stoppedActivityCancelsScanAndRequiresExplicitContinuation() throws Exception {
        fixture.holdNextAlbum();
        choose(2026, 9);
        assertTrue(fixture.arrived.await(10, TimeUnit.SECONDS));
        cover = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> cover.hasWindowFocus() && Boolean.TRUE.equals(field(fragment(), "monthPaused")));
        assertFalse("Canceled page is no longer marked in flight", ui(() -> paging().isLoading()));
        int requests = fixture.albumRequests.get();
        int responses = fixture.albumResponses.get();
        fixture.release();
        await(() -> fixture.albumResponses.get() > responses);
        ui(() -> { cover.finish(); return null; });
        await(() -> activity.hasWindowFocus() && title().equals("年月浏览已暂停"));
        assertEquals("Returning from background must not restart scan", requests, fixture.albumRequests.get());
        screenshot("background-scan-paused");
        continueWithRemote();
        await(() -> scanPaused() && paging().getOffset() == 175);
        assertEquals("Explicit resume retries original offset then keeps five-page budget",
                requests + 5, fixture.albumRequests.get());
    }

    @Test public void dateWallMonthQueryReplacesSingleDayAndBackRestoresDaySource() throws Exception {
        ui(() -> { fragment().loadPhotosByDate("2026-10-31", 1); return null; });
        await(() -> media().size() == 1 && "3001".equals(media().get(0).getId()) && "3001".equals(focusedId()));
        choose(2026, 9);
        await(() -> media().size() == 1 && "3002".equals(media().get(0).getId()));
        Uri monthRequest = Uri.parse(fixture.lastGalleryTarget);
        assertEquals("2026:09:01 00:00:00", monthRequest.getQueryParameter("start_time"));
        assertEquals("2026:09:30 23:59:59", monthRequest.getQueryParameter("end_time"));
        assertEquals("0", monthRequest.getQueryParameter("offset"));
        assertEquals("index", monthRequest.getQueryParameter("mode"));
        awaitFocusedSyntheticImage();
        screenshot("date-wall-cross-month");
        key(KeyEvent.KEYCODE_BACK);
        await(() -> selectedMonth() == null && "3001".equals(focusedId()));
        Uri restored = Uri.parse(fixture.lastGalleryTarget);
        assertEquals("2026:10:31 00:00:00", restored.getQueryParameter("start_time"));
        assertEquals("2026:10:31 23:59:59", restored.getQueryParameter("end_time"));
        awaitFocusedSyntheticImage();
        screenshot("date-original-day-restored");
    }

    @Test public void matchingPhotosKeepUnknownDateWarningVisible() throws Exception {
        fixture.unknownCaptureDate = true;
        choose(2026, 9);
        await(this::scanPaused);
        continueWithRemote();
        await(() -> media().size() == 35 && !paging().hasMore());
        assertTrue("Known matches do not erase uncertainty", ui(this::footer).contains("日期信息不完整"));
        assertTrue(ui(() -> activity.findViewById(R.id.main_content_container).isShown()));
        screenshot("matches-with-unknown-date-warning");
    }

    @Test public void deletedOriginalIdIsReportedInsteadOfFabricatingRestoration() throws Exception {
        ui(() -> { selectPhoto(2); return null; });
        await(() -> "1003".equals(focusedId()));
        choose(2026, 10);
        await(() -> selectedMonth() != null && media().size() == 35);
        fixture.deletedId = 1003;
        key(KeyEvent.KEYCODE_BACK);
        await(() -> selectedMonth() == null && field(fragment(), "restoringMonthPoint") == null
                && footer().contains("原位置未能恢复"));
        assertFalse("Deleted ID cannot survive as an old media snapshot", ui(() -> containsMedia("1003")));
        assertTrue(ui(this::footer).contains("移动或删除"));
        screenshot("deleted-id-restore-notice");
    }

    @Test public void shortPagesWithoutHasNextPauseRetrySameOffsetAndDetectRepeatedPage() throws Exception {
        fixture.pageCap = 7;
        fixture.omitHasNext = true;
        int before = fixture.albumRequests.get();
        choose(2026, 7);
        await(() -> scanPaused() && paging().getOffset() == 35);
        assertEquals("Short nonempty pages do not imply EOF", before + 5, fixture.albumRequests.get());
        assertEquals("尚未找到匹配内容", ui(this::title));
        fixture.failOnceOffset = 35;
        continueWithRemote();
        await(() -> paging().hasFailed());
        assertEquals(35, ui(() -> paging().getOffset()).intValue());
        assertEquals("35", fixture.lastAlbumOffset);
        continueWithRemote();
        await(() -> scanPaused() && paging().getOffset() == 70);
        List<String> targets;
        synchronized (fixture.albumTargets) { targets = new ArrayList<>(fixture.albumTargets); }
        int attemptsAt35 = 0;
        for (String target : targets) if ("35".equals(Uri.parse(target).getQueryParameter("offset"))) attemptsAt35++;
        assertEquals("Failed raw offset must be retried exactly", 2, attemptsAt35);
        fixture.repeatPage = true;
        choose(2026, 7);
        await(() -> paging().isStalled());
        assertEquals("结果可能不完整", ui(this::title));
        assertEquals("7", fixture.lastAlbumOffset);
        screenshot("short-page-retry-and-repetition-boundary");
    }

    @Test public void monthDetailBackPreservesScopeAndVirtualTopCanContinueAfterStop() throws Exception {
        fixture.targetOffset = 0;
        choose(2026, 9);
        await(() -> media().size() == 35 && "1001".equals(focusedId()));
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MediaDetailActivity.class.getName(), null, false);
        try {
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            cover = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
            assertNotNull("Actual media detail opens", cover);
            await(cover::hasWindowFocus);
            key(KeyEvent.KEYCODE_BACK);
            await(() -> activity.hasWindowFocus() && selectedMonth() != null && selectedMonth().month == 9
                    && Boolean.TRUE.equals(field(cover, "reloadOnStart")));
            assertEquals("album", ui(() -> field(fragment(), "photoMode")));
            cover = null;
        } finally { instrumentation.removeMonitor(monitor); }
        // Establish a query-local evicted range without a long scroll setup; subsequent navigation is real DPAD.
        ui(() -> {
            Field paused = MainFragment.class.getDeclaredField("monthPaused");
            paused.setAccessible(true);
            paused.setBoolean(fragment(), false);
            MediaItem anchor = new MediaItem("1071", "Synthetic 1071", "photo",
                    fixture.baseUrl() + "/image/fixture.jpg", fixture.baseUrl() + "/image/fixture.jpg");
            anchor.setBrowseRawOffset(70);
            Method relocate = MainFragment.class.getDeclaredMethod("relocatePhotoWindow", MediaItem.class, boolean.class);
            relocate.setAccessible(true);
            relocate.invoke(fragment(), anchor, false);
            Method load = MainFragment.class.getDeclaredMethod("loadPhotoPage", boolean.class);
            load.setAccessible(true);
            load.invoke(fragment(), true);
            return null;
        });
        await(() -> media().size() == 35 && "1071".equals(media().get(0).getId()));
        ui(() -> { selectPhoto(0); return null; });
        await(() -> "1071".equals(focusedId()));
        assertTrue(ui(() -> ((SlidingPhotoWindow) field(fragment(), "photoWindow")).hasPrevious()));
        cover = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> cover.hasWindowFocus() && Boolean.TRUE.equals(field(fragment(), "monthPaused")));
        ui(() -> { cover.finish(); return null; });
        await(activity::hasWindowFocus);
        ui(() -> { selectPhoto(0); return null; });
        await(() -> "1071".equals(focusedId()));
        key(KeyEvent.KEYCODE_DPAD_UP);
        await(() -> !Boolean.TRUE.equals(field(fragment(), "monthPaused"))
                && ((SlidingPhotoWindow) field(fragment(), "photoWindow")).firstOffset() == 35);
        assertEquals("35", fixture.lastAlbumOffset);
        screenshot("month-previous-resumes-after-stop");

        activateDrawerAction("year_month");
        await(() -> pickerDialog().isShowing());
        awaitPickerRendered(false);
        screenshot("deep-window-menu-month-entry");
        key(KeyEvent.KEYCODE_BACK);
    }

    @Test public void personSharedAndLocalTypeFavoriteFiltersKeepTheirScopes() throws Exception {
        ui(() -> { fragment().loadPersonTimeline(71, "合成人物"); return null; });
        await(() -> field(fragment(), "savedPersonTimelineItems") != null);
        ui(() -> { fragment().loadPersonPhotosByDate("2026-10-31", 1); return null; });
        await(() -> media() != null && media().size() == 1);
        choose(2026, 9);
        await(() -> "2026:09:01 00:00:00".equals(Uri.parse(fixture.lastPersonTarget).getQueryParameter("startTime")) && media().size() == 1);
        Uri person = Uri.parse(fixture.lastPersonTarget);
        assertEquals("71", person.getQueryParameter("personId"));
        assertEquals("71", person.getQueryParameter("album_id"));
        assertMonth(person, "start_time", "end_time");
        assertMonth(person, "startTime", "endTime");
        ui(() -> {
            Method method = MainFragment.class.getDeclaredMethod("loadPhotosBySharedAlbum", String.class, String.class);
            method.setAccessible(true);
            method.invoke(fragment(), "9001", "合成共享");
            return null;
        });
        await(() -> "shared_album".equals(field(fragment(), "photoMode")) && media().size() == 1);
        choose(2026, 9);
        await(() -> "2026:09:01 00:00:00".equals(Uri.parse(fixture.lastSharedTarget).getQueryParameter("startTime")) && media().size() == 1);
        Uri shared = Uri.parse(fixture.lastSharedTarget);
        assertEquals("9001", shared.getQueryParameter("albumId"));
        assertMonth(shared, "startTime", "endTime");
        assertEquals("0", shared.getQueryParameter("offset"));
        screenshot("shared-month-scope");

        fixture.mixedGallery = true;
        ui(() -> { fragment().loadMediaType("photo"); return null; });
        await(() -> "media_type".equals(field(fragment(), "photoMode")) && media().size() == 2);
        choose(2026, 9);
        await(() -> selectedMonth() != null && media().size() == 2);
        assertTrue(ui(() -> containsMedia("4001") && containsMedia("4003") && !containsMedia("4002")));
        assertNull("Type remains a local filter", Uri.parse(fixture.lastGalleryTarget).getQueryParameter("category"));
        ui(() -> { fragment().loadMediaType("video"); return null; });
        await(() -> media() != null && media().size() == 1 && containsMedia("4002"));
        choose(2026, 9);
        await(() -> selectedMonth() != null && media().size() == 1 && containsMedia("4002"));
        ui(() -> { fragment().loadFavorites(); return null; });
        await(() -> Boolean.TRUE.equals(field(fragment(), "favoritesTimeline")) && field(fragment(), "photoPaging") == null);
        ui(() -> { fragment().loadPhotosByDate("2026-10-31", 3); return null; });
        await(() -> media() != null && media().size() == 2);
        choose(2026, 9);
        await(() -> selectedMonth() != null && media().size() == 2);
        assertTrue("Only current local favorites survive month selection", ui(() -> containsMedia("4001")
                && containsMedia("4002") && !containsMedia("4003")));
        screenshot("favorites-month-local-filter");
    }

    @Test public void recentSourceRejectsMonthExpansion() throws Exception {
        ui(() -> { fragment().loadRecent(); return null; });
        await(() -> field(fragment(), "timelineItems") != null && field(fragment(), "photoPaging") == null);
        ui(() -> { fragment().loadPhotosByDate("2026-10-31", 1); return null; });
        await(() -> media() != null && media().size() == 1);
        String originalRequest = fixture.lastGalleryTarget;
        ui(() -> { activity.focusBrowseMenuButton(); return null; });
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertTrue(ui(() -> activity.findViewById(R.id.btn_year_month).hasFocus()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> !activity.hasWindowFocus());
        instrumentation.waitForIdleSync();
        assertTrue("Unsupported recent scope is explained in the visible dialog",
                activeWindowContains("当前来源不支持年月跳转"));
        assertNull(ui(() -> field(fragment(), "selectedMonth")));
        assertEquals("Unsupported selection must not expand into a gallery month query",
                originalRequest, fixture.lastGalleryTarget);
        screenshot("recent-scope-unsupported");
        key(KeyEvent.KEYCODE_BACK);
    }

    @Test public void stoppingDetailWhileWaitingForOppositePageDoesNotRestartScan() throws Exception {
        fixture.targetOffset = 0;
        choose(2026, 9);
        await(() -> media().size() == 35 && !paging().isLoading());
        rebaseAt70();
        // Start detail before holding the request: slow emulator startup must not
        // consume the production HTTP read timeout before LEFT joins the request.
        openDetail(0);
        fixture.holdNextAlbum();
        ui(() -> { requestForward(); return null; });
        assertTrue("Held forward page reached fixture", fixture.arrived.await(10, TimeUnit.SECONDS));
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        await(() -> field(fragment(), "detailPageCallback") != null
                && ((MediaPageController) field(detail, "pageController")).isLoading());
        int beforeStop = fixture.albumRequests.get();
        cover = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> cover.hasWindowFocus() && Boolean.TRUE.equals(field(detail, "reloadOnStart")));
        screenshot("opposite-page-wait-stopped-detail");
        assertTrue("Stopping detail must preserve monthPaused while releasing the opposite-page callback",
                ui(() -> Boolean.TRUE.equals(field(fragment(), "monthPaused"))));
        assertFalse("Stopping detail cancels its outstanding month metadata", ui(() -> paging().isLoading()));
        assertEquals("Cancellation callback must not start an opposite scan in the background",
                beforeStop, fixture.albumRequests.get());
    }

    @Test public void backDuringMonthRestartRestoresLastIdAfterHeldResponseCompletes() throws Exception {
        fixture.targetOffset = 0;
        choose(2026, 9);
        await(() -> media().size() == 35 && !paging().isLoading());
        openDetail(2);
        await(() -> "1003".equals(field(detail, "displayedMediaId")));
        fixture.holdNextAlbum();
        ui(() -> {
            ((MediaPageController) field(detail, "pageController")).restart(new MediaPageController.Callback() {
                @Override public void onReady(boolean available) { }
                @Override public void onFailure() { }
                @Override public void onPaused(String reason) { }
            });
            return null;
        });
        assertTrue("Month restart has an outstanding first page", fixture.arrived.await(10, TimeUnit.SECONDS));
        await(() -> media().isEmpty() && paging().isLoading());
        key(KeyEvent.KEYCODE_BACK);
        await(() -> activity.hasWindowFocus() && Boolean.TRUE.equals(field(detail, "reloadOnStart")));
        fixture.release();
        await(() -> selectedMonth() != null && containsMedia("1003") && "1003".equals(focusedId()));
        screenshot("back-during-month-restart-restores-last-id");
    }

    @Test public void immediateHomeAfterBackStillCancelsHeldMonthRestart() throws Exception {
        fixture.targetOffset = 0;
        choose(2026, 9);
        await(() -> media().size() == 35 && !paging().isLoading());
        openDetail(2);
        fixture.holdNextAlbum();
        ui(() -> {
            ((MediaPageController) field(detail, "pageController")).restart(new MediaPageController.Callback() {
                @Override public void onReady(boolean available) { }
                @Override public void onFailure() { }
                @Override public void onPaused(String reason) { }
            });
            return null;
        });
        assertTrue(fixture.arrived.await(10, TimeUnit.SECONDS));
        int beforeStop = fixture.albumRequests.get();
        key(KeyEvent.KEYCODE_BACK);
        key(KeyEvent.KEYCODE_HOME);
        await(() -> !activity.hasWindowFocus() && Boolean.TRUE.equals(field(fragment(), "monthPaused")));
        assertFalse("Back followed by Home must cancel the live request", ui(() -> paging().isLoading()));
        int responses = fixture.albumResponses.get();
        fixture.release();
        await(() -> fixture.albumResponses.get() > responses);
        assertEquals("No response/cancel callback may resume metadata in the background",
                beforeStop, fixture.albumRequests.get());
        ui(() -> {
            activity.startActivity(new Intent(activity, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return null;
        });
        await(activity::hasWindowFocus);
        assertTrue("Returning from Home still requires explicit continuation",
                ui(() -> Boolean.TRUE.equals(field(fragment(), "monthPaused"))));
        assertEquals(beforeStop, fixture.albumRequests.get());
        screenshot("back-then-home-pauses-month");
        continueWithRemote();
        await(() -> containsMedia("1003") && "1003".equals(focusedId()));
    }

    @Test public void favoriteWriteBackKeepsMonthAndSharedDetailNeverWritesCollect() throws Exception {
        fixture.mixedGallery = true;
        ui(() -> { activity.retryBrowseMode(BrowseMode.FAVORITES); return null; });
        await(() -> Boolean.TRUE.equals(field(fragment(), "favoritesTimeline")) && field(fragment(), "photoPaging") == null);
        ui(() -> { fragment().loadPhotosByDate("2026-10-31", 3); return null; });
        await(() -> media() != null && media().size() == 2);
        choose(2026, 9);
        await(() -> selectedMonth() != null && media().size() == 2 && containsMedia("4001"));
        openDetail(0);
        key(KeyEvent.KEYCODE_F1);
        await(() -> fixture.collectPosts.get() == 1 && !Boolean.TRUE.equals(field(detail, "collectPending")));
        assertFalse("Synthetic write changed the actual fixture favorite state", fixture.collected4001);
        key(KeyEvent.KEYCODE_BACK);
        await(() -> activity.hasWindowFocus() && selectedMonth() != null && selectedMonth().month == 9
                && media().size() == 1 && containsMedia("4002"));
        assertEquals("favorites", ui(() -> field(fragment(), "photoMode")));
        assertMonth(Uri.parse(fixture.lastGalleryTarget), "start_time", "end_time");
        screenshot("favorite-write-back-keeps-month");

        ui(() -> {
            Method shared = MainFragment.class.getDeclaredMethod("loadPhotosBySharedAlbum", String.class, String.class);
            shared.setAccessible(true);
            shared.invoke(fragment(), "9001", "合成共享");
            return null;
        });
        await(() -> media() != null && media().size() == 1 && containsMedia("5001"));
        choose(2026, 9);
        await(() -> selectedMonth() != null && media().size() == 1);
        openDetail(0);
        assertTrue(ui(() -> detail.getIntent().getBooleanExtra("SHARED_ALBUM_READ_ONLY", false)));
        int posts = fixture.collectPosts.get();
        int reads = fixture.detailReads.get();
        key(KeyEvent.KEYCODE_F1);
        instrumentation.waitForIdleSync();
        assertEquals("Shared details must not write favorite state", posts, fixture.collectPosts.get());
        assertEquals("Shared readonly action must not even fetch mutable favorite state", reads, fixture.detailReads.get());
        assertFalse(ui(() -> Boolean.TRUE.equals(field(detail, "collectPending"))));
        screenshot("shared-month-detail-readonly");
        key(KeyEvent.KEYCODE_BACK);
        await(() -> activity.hasWindowFocus() && selectedMonth() != null && selectedMonth().month == 9);
    }

    @Test public void monthMenuDoesNotReplaceRememberedBrowseModeAfterActivityRecreation() throws Exception {
        fixture.mixedGallery = true;
        ui(() -> { activity.retryBrowseMode(BrowseMode.PHOTOS); return null; });
        await(() -> media() != null && media().size() == 2 && "media_type".equals(field(fragment(), "photoMode")));
        String account = prefs.getString(BrowseSelection.ACCOUNT_KEY, "");
        await(() -> "photo".equals(prefs.getString(BrowseSelection.MODE_PREFIX + account, "")));
        choose(2026, 9);
        await(() -> selectedMonth() != null && media().size() == 2);
        activateDrawerAction("year_month");
        await(() -> pickerDialog().isShowing());
        key(KeyEvent.KEYCODE_BACK);
        await(activity::hasWindowFocus);
        assertEquals("Month action is not a persisted browse destination", "photo",
                prefs.getString(BrowseSelection.MODE_PREFIX + account, ""));
        MainActivity previous = activity;
        ui(() -> { previous.recreate(); return null; });
        await(() -> {
            // ActivityMonitor can observe the old Activity's onPause before the
            // replacement is created on API19; wait for the actual new instance.
            for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (candidate instanceof MainActivity && candidate != previous) {
                    activity = (MainActivity) candidate;
                    return true;
                }
            }
            return false;
        });
        assertNotSame(previous, activity);
        await(() -> activity.hasWindowFocus() && field(fragment(), "rootBrowseMode") == BrowseMode.PHOTOS
                && media() != null && media().size() == 2);
        assertEquals("photo", prefs.getString(BrowseSelection.MODE_PREFIX + account, ""));
        screenshot("recreated-activity-keeps-browse-mode");
    }

    private void rebaseAt70() throws Exception {
        ui(() -> {
            Field paused = MainFragment.class.getDeclaredField("monthPaused");
            paused.setAccessible(true);
            paused.setBoolean(fragment(), false);
            MediaItem anchor = new MediaItem("1071", "Synthetic 1071", "photo",
                    fixture.baseUrl() + "/image/fixture.jpg", fixture.baseUrl() + "/image/fixture.jpg");
            anchor.setBrowseRawOffset(70);
            Method relocate = MainFragment.class.getDeclaredMethod("relocatePhotoWindow", MediaItem.class, boolean.class);
            relocate.setAccessible(true);
            relocate.invoke(fragment(), anchor, false);
            requestForward();
            return null;
        });
        await(() -> media().size() == 35 && "1071".equals(media().get(0).getId()) && !paging().isLoading());
        ui(() -> { selectPhoto(0); return null; });
        await(() -> "1071".equals(focusedId()));
    }

    private void requestForward() throws Exception {
        Method load = MainFragment.class.getDeclaredMethod("loadPhotoPage", boolean.class);
        load.setAccessible(true);
        load.invoke(fragment(), true);
    }

    private void openDetail(int index) throws Exception {
        ui(() -> { selectPhoto(index); return null; });
        String id = ui(() -> media().get(index).getId());
        await(() -> id.equals(focusedId()));
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MediaDetailActivity.class.getName(), null, false);
        try {
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            detail = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
            assertNotNull("Real detail activity must open", detail);
            await(() -> detail.hasWindowFocus() && Boolean.TRUE.equals(field(detail, "canSwitch")));
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private void assertMonth(Uri target, String start, String end) {
        assertEquals("2026:09:01 00:00:00", target.getQueryParameter(start));
        assertEquals("2026:09:30 23:59:59", target.getQueryParameter(end));
    }

    private boolean activeWindowContains(String text) throws Exception {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root != null) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
                boolean present = !found.isEmpty();
                for (AccessibilityNodeInfo node : found) node.recycle();
                if (present) return true;
            } finally { root.recycle(); }
        }
        // API19 may not expose a freshly opened dialog in UiAutomation's active root.
        // Inspect the actual displayed window views, never a copied expected string.
        return ui(() -> {
            Class<?> managerClass = Class.forName("android.view.WindowManagerGlobal");
            Object manager = managerClass.getDeclaredMethod("getInstance").invoke(null);
            Field viewsField = managerClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object views = viewsField.get(manager);
            if (views instanceof Iterable) {
                for (Object view : (Iterable<?>) views) if (displayedText((View) view, text)) return true;
            } else if (views instanceof View[]) {
                for (View view : (View[]) views) if (displayedText(view, text)) return true;
            }
            return false;
        });
    }

    private boolean displayedText(View view, String expected) {
        if (view == null || !view.isShown()) return false;
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (displayedText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private boolean containsMedia(String id) throws Exception {
        for (MediaItem item : media()) if (id.equals(item.getId())) return true;
        return false;
    }

    private String footer() throws Exception {
        ListRow row = (ListRow) field(fragment(), "pageFooter");
        return row == null || row.getAdapter().size() == 0 ? "" : ((MediaItem) row.getAdapter().get(0)).getTitle();
    }

    private void activateDrawerAction(String action) throws Exception {
        key(KeyEvent.KEYCODE_MENU);
        await(() -> drawer().isDrawerOpen(GravityCompat.END));
        int target = ui(() -> {
            MainActivity.MenuAdapter adapter = (MainActivity.MenuAdapter) field(activity, "menuAdapter");
            for (int i = 0; i < adapter.getItemCount(); i++)
                if (action.equals(adapter.get(i).getAction())) return i;
            throw new AssertionError("Drawer must contain " + action);
        });
        int current = ui(() -> ((VerticalGridView) activity.findViewById(R.id.menu_grid)).getSelectedPosition());
        for (int moves = 0; current != target && moves < 30; moves++) {
            key(current < target ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
            final int previous = current;
            await(() -> ((VerticalGridView) activity.findViewById(R.id.menu_grid)).getSelectedPosition() != previous);
            current = ui(() -> ((VerticalGridView) activity.findViewById(R.id.menu_grid)).getSelectedPosition());
        }
        assertEquals("DPAD selects the requested drawer action", target, current);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private void openPickerWithRemote() throws Exception {
        // Establish the existing menu focus anchor, then reach and activate the new action by DPAD.
        ui(() -> { activity.focusBrowseMenuButton(); return null; });
        await(() -> activity.findViewById(R.id.btn_menu).hasFocus());
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        if (!ui(() -> activity.findViewById(R.id.btn_year_month).hasFocus())) key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertTrue("Jump is DPAD reachable from existing menu", ui(() -> activity.findViewById(
                R.id.btn_year_month).hasFocus()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> pickerDialog().isShowing());
    }

    private void continueWithRemote() throws Exception {
        ui(() -> {
            if (activity.findViewById(R.id.browse_state_container).isShown()) {
                fragment().focusBrowseContent();
            } else {
                ArrayObjectAdapter rows = (ArrayObjectAdapter) field(fragment(), "mRowsAdapter");
                int footer = rows.indexOf(field(fragment(), "pageFooter"));
                assertTrue("Visible continuation footer required", footer >= 0);
                fragment().getRowsSupportFragment().setSelectedPosition(footer, false,
                        new ListRowPresenter.SelectItemViewHolderTask(0));
                fragment().getRowsSupportFragment().getVerticalGridView().requestFocus();
            }
            return null;
        });
        await(() -> (stateAction() != null && stateAction().hasFocus()) || "__page_action".equals(focusedId()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private void choose(int year, int month) throws Exception {
        ui(() -> {
            Method method = MainFragment.class.getDeclaredMethod("selectYearMonth", int.class, int.class);
            method.setAccessible(true);
            method.invoke(fragment(), year, month);
            return null;
        });
    }

    private MainFragment fragment() {
        return (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.main_content_container);
    }
    private List<MediaItem> media() throws Exception { return (List<MediaItem>) field(fragment(), "currentMediaList"); }
    private MainPagingState paging() throws Exception { return (MainPagingState) field(fragment(), "photoPaging"); }
    private YearMonthRange selectedMonth() throws Exception { return (YearMonthRange) field(fragment(), "selectedMonth"); }
    private boolean scanPaused() throws Exception { return ((SparseScanBudget) field(fragment(), "photoScan")).isPaused(); }
    private AlertDialog pickerDialog() throws Exception { return (AlertDialog) field(field(fragment(), "monthPicker"), "dialog"); }
    private boolean pickerChoosingMonth() throws Exception { return (Boolean) field(field(fragment(), "monthPicker"), "choosingMonth"); }
    private DrawerLayout drawer() { return activity.findViewById(R.id.drawer_layout); }
    private TextView stateAction() { return activity.getWindow().getDecorView().findViewWithTag("tv_state_action"); }
    private String stateText(String tag) {
        TextView text = activity.getWindow().getDecorView().findViewWithTag(tag);
        return text != null && text.isShown() ? text.getText().toString() : "";
    }
    private String title() { return stateText("tv_state_title"); }
    private String message() { return stateText("tv_state_message"); }
    private String focusedId() {
        View focus = activity.getCurrentFocus();
        while (focus != null) {
            Object tag = focus.getTag(R.id.media_item_id);
            if (tag instanceof String) return (String) tag;
            focus = focus.getParent() instanceof View ? (View) focus.getParent() : null;
        }
        return null;
    }
    private void selectPhoto(int index) throws Exception {
        // A real user gesture cancels queued entry focus before selecting another item.
        fragment().cancelPhotoFocusHandoff();
        PhotoWallLayout layout = (PhotoWallLayout) field(fragment(), "wallLayout");
        fragment().getRowsSupportFragment().setSelectedPosition(index / layout.columns, false,
                new ListRowPresenter.SelectItemViewHolderTask(index % layout.columns));
        fragment().getRowsSupportFragment().getVerticalGridView().requestFocus();
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private <T> T ui(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 30000;
        Throwable last = null;
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (ui(condition)) return; } catch (Throwable error) { last = error; }
            Thread.sleep(50);
        }
        try { screenshot("timeout-state"); }
        catch (Exception | AssertionError screenshotFailure) {
            System.out.println("Timeout screenshot unavailable: " + screenshotFailure.getClass().getSimpleName());
        }
        String diagnostic;
        try {
            diagnostic = ui(() -> activity == null ? "activity=null"
                    : fragment() == null ? "fragment=null; activityFocus=" + activity.hasWindowFocus()
                            + "; finishing=" + activity.isFinishing() + "; destroyed=" + activity.isDestroyed()
                    : title() + "; focus=" + focusedId()
                + "; activityFocus=" + activity.hasWindowFocus() + "; finishing=" + activity.isFinishing()
                + "; month=" + (selectedMonth() == null ? "null" : selectedMonth().label())
                + "; media=" + (media() == null ? -1 : media().size())
                + "; paused=" + field(fragment(), "monthPaused")
                + "; initialFocus=" + field(fragment(), "initialPhotoFocusPending")
                + "; handoffPending=" + field(fragment(), "photoFocusHandoffPending")
                + "; entryHandoff=" + field(fragment(), "photoEntryFocusHandoff")
                + "; handoffTarget=" + field(fragment(), "photoFocusHandoffTargetId")
                + "; handoffGeneration=" + field(fragment(), "photoFocusHandoffGeneration")
                + "; selectedRow=" + fragment().getSelectedPosition()
                + "; resumed=" + fragment().isResumed()
                + "; focusedView=" + (activity.getCurrentFocus() == null ? "null"
                        : activity.getCurrentFocus().getClass().getSimpleName()));
        } catch (Throwable diagnosticFailure) {
            diagnostic = "diagnostic unavailable: " + diagnosticFailure.getClass().getSimpleName();
        }
        throw new AssertionError("Timed out: " + diagnostic, last);
    }
    private void key(int code) {
        long downTime = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0));
        instrumentation.sendKeySync(new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        instrumentation.waitForIdleSync();
    }
    private void awaitPickerRendered(boolean monthStage) throws Exception {
        await(() -> {
            AlertDialog dialog = pickerDialog();
            View decor = dialog.getWindow().getDecorView();
            View focused = dialog.getCurrentFocus();
            if (!dialog.isShowing() || !decor.isShown() || !decor.hasWindowFocus()
                    || decor.getWidth() <= 0 || decor.getHeight() <= 0 || !(focused instanceof TextView)) return false;
            String text = ((TextView) focused).getText().toString();
            return monthStage ? text.matches("[0-9]{1,2} 月") : "选择月份".equals(text);
        });
    }

    /** Real decoded fixture pixels are required before claiming focus over an opaque photo. */
    private void awaitFocusedSyntheticImage() throws Exception {
        await(() -> {
            if (!activity.findViewById(R.id.main_content_container).isShown()
                    || activity.findViewById(R.id.browse_state_container).isShown()) return false;
            View focused = activity.getCurrentFocus();
            while (focused != null && !(focused instanceof androidx.leanback.widget.ImageCardView))
                focused = focused.getParent() instanceof View ? (View) focused.getParent() : null;
            if (focused == null) return false;
            android.graphics.drawable.Drawable drawable = ((androidx.leanback.widget.ImageCardView) focused)
                    .getMainImageView().getDrawable();
            if (!(drawable instanceof android.graphics.drawable.BitmapDrawable)) return false;
            Bitmap source = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            Bitmap readable = source;
            if (Build.VERSION.SDK_INT >= 26 && source.getConfig() == Bitmap.Config.HARDWARE)
                readable = source.copy(Bitmap.Config.ARGB_8888, false);
            if (readable == null) return false;
            try {
                int color = readable.getPixel(Math.min(10, readable.getWidth() - 1),
                        Math.min(10, readable.getHeight() - 1));
                return Color.blue(color) > Color.red(color) + 40 && Color.green(color) > Color.red(color) + 20;
            } finally { if (readable != source) readable.recycle(); }
        });
    }

    private void waitForRenderedFrames() throws Exception {
        CountDownLatch frames = new CountDownLatch(1);
        ui(() -> {
            View decor = activity.getWindow().getDecorView();
            Object picker = field(fragment(), "monthPicker");
            if (picker != null && ((YearMonthPicker) picker).isShowing()) decor = pickerDialog().getWindow().getDecorView();
            else if (cover != null && cover.hasWindowFocus()) decor = cover.getWindow().getDecorView();
            else if (detail != null && detail.hasWindowFocus()) decor = detail.getWindow().getDecorView();
            final View window = decor;
            window.postOnAnimation(() -> window.postOnAnimation(frames::countDown));
            return null;
        });
        assertTrue("Two real display frames must render before capture", frames.await(10, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        // A software compositor can present after UI-thread frame callbacks have completed.
        SystemClock.sleep(400);
    }

    private void screenshot(String scene) throws Exception {
        waitForRenderedFrames();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Actual emulator screenshot required", bitmap);
        Context context = instrumentation.getTargetContext();
        File directory = context.getExternalFilesDir("year-month-evidence");
        if (directory == null || !(directory.isDirectory() || directory.mkdirs()))
            directory = new File(context.getFilesDir(), "year-month-evidence");
        assertTrue("Writable synthetic screenshot directory required", directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, scene + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(context.getApplicationInfo().sourceDir)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : hash.digest()) digest.append(String.format(Locale.US, "%02x", value & 255));
        String evidence = "Scene: " + scene + "\nPackage: " + context.getPackageName()
                + "\nAPI: " + Build.VERSION.SDK_INT + "\nHardware: " + Build.HARDWARE
                + "\nAPK SHA-256: " + digest + "\nData: in-process synthetic fixture only\n";
        try (FileOutputStream output = new FileOutputStream(new File(directory, scene + ".txt"))) {
            output.write(evidence.getBytes("UTF-8"));
        }
    }

    /** Six 35-row pages. September begins at offset 175; July is empty. */
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger albumRequests = new AtomicInteger();
        final AtomicInteger albumResponses = new AtomicInteger();
        final AtomicInteger collectPosts = new AtomicInteger();
        final AtomicInteger detailReads = new AtomicInteger();
        volatile boolean collected4001 = true;
        final List<String> albumTargets = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean holdNext = new AtomicBoolean();
        volatile String lastAlbumOffset = "";
        volatile String lastGalleryTarget = "";
        volatile boolean unknownCaptureDate;
        volatile int pageCap = 35;
        volatile int targetOffset = 175;
        volatile int deletedId = -1;
        volatile int failOnceOffset = -1;
        volatile boolean omitHasNext;
        volatile boolean repeatPage;
        volatile boolean mixedGallery;
        volatile String lastPersonTarget = "";
        volatile String lastSharedTarget = "";
        volatile CountDownLatch gate;
        volatile CountDownLatch arrived = new CountDownLatch(0);
        final byte[] image;

        Fixture() throws Exception {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(38, 83, 113));
            Paint paint = new Paint(); paint.setColor(Color.WHITE); paint.setTextSize(25);
            canvas.drawText("SYNTHETIC MONTH", 30, 100, paint);
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, data);
            bitmap.recycle();
            image = data.toByteArray();
            Thread accept = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        Thread worker = new Thread(() -> serve(socket));
                        worker.setDaemon(true); worker.start();
                    } catch (Exception ignored) { }
                }
            });
            accept.setDaemon(true); accept.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void holdNextAlbum() {
            gate = new CountDownLatch(1);
            arrived = new CountDownLatch(1);
            holdNext.set(true);
        }
        void release() { CountDownLatch held = gate; if (held != null) held.countDown(); }
        void serve(Socket socket) {
            boolean album = false;
            try (Socket connection = socket) {
                connection.setSoTimeout(15000);
                BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String first = input.readLine();
                if (first == null) return;
                String target = first.split(" ")[1];
                String header;
                int bodyLength = 0;
                while ((header = input.readLine()) != null && !header.isEmpty()) {
                    if (header.toLowerCase(Locale.US).startsWith("content-length:"))
                        bodyLength = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                }
                if (bodyLength > 1024) throw new IllegalArgumentException("Fixture body exceeds bound");
                char[] requestBody = new char[bodyLength];
                int read = 0;
                while (read < bodyLength) {
                    int next = input.read(requestBody, read, bodyLength - read);
                    if (next < 0) throw new IllegalArgumentException("Truncated fixture body");
                    read += next;
                }
                Uri uri = Uri.parse(baseUrl() + target);
                String path = uri.getPath();
                byte[] body;
                String type = "application/json";
                if (path.startsWith("/image") || path.startsWith("/p/api/v1/stream/p/")) {
                    body = image; type = "image/jpeg";
                } else if (path.startsWith("/p/api/v1/photo/detail/")) {
                    detailReads.incrementAndGet();
                    int id = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                    int collected = id == 4001 && collected4001 ? 1 : 0;
                    body = ("{\"code\":0,\"data\":{\"info\":{\"id\":" + id
                            + ",\"isCollect\":" + collected + "}}}").getBytes("UTF-8");
                } else if (path.equals("/p/api/v1/photo/collect")) {
                    Uri form = Uri.parse("http://fixture/?" + new String(requestBody));
                    if (!"4001".equals(form.getQueryParameter("id"))) throw new IllegalArgumentException("Unknown fixture id");
                    collected4001 = "1".equals(form.getQueryParameter("collect"));
                    collectPosts.incrementAndGet();
                    body = "{\"code\":0}".getBytes("UTF-8");
                }
                else if (path.equals("/p/api/v1/gallery/timeline")
                        || path.equals("/p/api/v1/ai-person/photoLibrary/timeLine")
                        || path.equals("/p/api/v1/explore/recent_timeline")) {
                    body = "{\"code\":0,\"data\":{\"list\":[{\"year\":2026,\"month\":10,\"day\":31,\"itemCount\":1}]}}".getBytes("UTF-8");
                } else if (path.equals("/p/api/v1/album/photos")) {
                    album = true;
                    int offset = Integer.parseInt(uri.getQueryParameter("offset"));
                    int limit = Integer.parseInt(uri.getQueryParameter("limit"));
                    lastAlbumOffset = String.valueOf(offset);
                    albumTargets.add(uri.toString());
                    albumRequests.incrementAndGet();
                    if (holdNext.compareAndSet(true, false)) {
                        CountDownLatch held = gate;
                        arrived.countDown();
                        held.await(60, TimeUnit.SECONDS);
                    }
                    List<String> items = new ArrayList<>();
                    int total = deletedId > 0 ? 209 : 210;
                    int count = Math.max(0, Math.min(Math.min(limit, pageCap), total - offset));
                    int start = repeatPage ? 0 : offset;
                    for (int i = start; i < start + count; i++) {
                        int sourceIndex = deletedId > 0 && i >= deletedId - 1001 ? i + 1 : i;
                        // An older-than-target row before the target forbids unsafe sort early-exit.
                        String date = sourceIndex >= targetOffset ? "2026-09-15 12:00:00"
                                : sourceIndex == 100 ? "2026-08-15 12:00:00" : "2026-10-31 12:00:00";
                        if (unknownCaptureDate && sourceIndex == 100) date = "invalid-capture-date";
                        items.add(photo(sourceIndex + 1001, date));
                    }
                    if (offset == failOnceOffset) {
                        failOnceOffset = -1;
                        body = "{\"code\":503}".getBytes("UTF-8");
                    } else {
                        String json = page(items, offset + count < total);
                        if (omitHasNext) json = json.replace("\"hasNext\":true,", "").replace("\"hasNext\":false,", "");
                        body = json.getBytes("UTF-8");
                    }
                } else if (path.equals("/p/api/v1/gallery/getList")) {
                    lastGalleryTarget = uri.toString();
                    boolean september = uri.getQueryParameter("start_time").startsWith("2026:09");
                    String date = september ? "2026-09-15 12:00:00" : "2026-10-31 12:00:00";
                    if (mixedGallery) {
                        List<String> items = new ArrayList<>();
                        items.add(photo(4001, date, "photo", collected4001));
                        items.add(photo(4002, date, "video", true));
                        items.add(photo(4003, date, "photo", false));
                        body = page(items, false).getBytes("UTF-8");
                    } else body = page(Collections.singletonList(photo(september ? 3002 : 3001, date)), false).getBytes("UTF-8");
                } else if (path.equals("/p/api/v1/ai-person/photoLibrary/list")
                        || path.equals("/p/api/v1/album/normal/getList")) {
                    if (path.contains("ai-person")) lastPersonTarget = uri.toString();
                    else lastSharedTarget = uri.toString();
                    body = page(Collections.singletonList(photo(5001, "2026-09-15 12:00:00")), false).getBytes("UTF-8");
                } else body = "{\"code\":0,\"data\":{\"version\":\"synthetic\",\"list\":[]}}".getBytes("UTF-8");
                String response = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(response.getBytes("UTF-8"));
                connection.getOutputStream().write(body);
                connection.getOutputStream().flush();
            } catch (Exception ignored) {
                // A canceled old query deliberately closes its socket while held by this fixture.
            } finally { if (album) albumResponses.incrementAndGet(); }
        }
        String photo(int id, String captureDate) { return photo(id, captureDate, "photo", false); }
        String photo(int id, String captureDate, String category, boolean collected) {
            return "{\"id\":" + id + ",\"category\":\"" + category + "\",\"isCollect\":" + (collected ? 1 : 0)
                    + ",\"photoUUID\":\"synthetic-month\",\"fileName\":\"Synthetic " + id
                    + "\",\"photoDateTime\":\"" + captureDate + "\",\"dateTime\":\"2026-07-01 00:00:00\","
                    + "\"additional\":{\"thumbnail\":{\"mUrl\":\"/image/fixture.jpg\",\"sUrl\":\"/image/fixture.jpg\"}}}";
        }
        String page(List<String> items, boolean more) {
            StringBuilder json = new StringBuilder("{\"code\":0,\"data\":{\"hasNext\":").append(more).append(",\"list\":[");
            for (int i = 0; i < items.size(); i++) { if (i > 0) json.append(','); json.append(items.get(i)); }
            return json.append("]}}").toString();
        }
        void assertOrdinaryAlbumContract() {
            synchronized (albumTargets) {
                for (String target : albumTargets) {
                    Uri uri = Uri.parse(target);
                    assertEquals("7001", uri.getQueryParameter("album_id"));
                    assertEquals("date_time", uri.getQueryParameter("sort_by"));
                    assertEquals("desc", uri.getQueryParameter("sort_direction"));
                    assertNull("No guessed ordinary album time filter", uri.getQueryParameter("start_time"));
                    assertNull("No shared endpoint aliases on ordinary album", uri.getQueryParameter("startTime"));
                }
            }
        }
        @Override public void close() throws Exception { release(); server.close(); }
    }
}
