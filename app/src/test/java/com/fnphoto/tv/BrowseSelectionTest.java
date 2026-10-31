package com.fnphoto.tv;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class BrowseSelectionTest {
    private static class MemoryStore implements BrowseSelection.Store {
        final Map<String, String> data = new HashMap<>();
        @Override public String get(String key) { return data.get(key); }
        @Override public void put(String key, String value) { data.put(key, value); }
        @Override public void remove(String key) { data.remove(key); }
    }

    private final MemoryStore store = new MemoryStore();
    private final String account = BrowseSelection.accountId("nas.example:5666", "alice");
    private String key() { return BrowseSelection.MODE_PREFIX + account; }
    private BrowseSelection selection() { return new BrowseSelection(store, account); }

    @Test public void firstLaunchStartsGalleryWithoutPersistingAnUnverifiedSelection() {
        assertEquals(BrowseMode.GALLERY, selection().initial());
        assertTrue(store.data.isEmpty());
    }

    @Test public void everySupportedModeRestoresAcrossNewInstances() {
        for (BrowseMode mode : BrowseMode.values()) {
            BrowseSelection first = selection();
            first.initial();
            assertTrue(first.select(mode.id));
            first.succeeded(mode, account);
            assertEquals(mode, selection().initial());
        }
    }

    @Test public void failedManualSelectionKeepsLastWorkingType() {
        store.put(key(), "albums");
        BrowseSelection selection = selection();
        selection.initial();
        selection.select("people");
        assertNull(selection.failed(BrowseMode.PEOPLE));
        assertEquals("albums", store.get(key()));
        selection.succeeded(BrowseMode.PEOPLE, account); // Explicit retry later succeeds.
        assertEquals("people", store.get(key()));
    }

    @Test public void unsupportedEntriesAndCancelledMediaDialogDoNotReplaceSelection() {
        store.put(key(), "favorites");
        BrowseSelection selection = selection();
        assertEquals(BrowseMode.FAVORITES, selection.initial());
        for (String action : new String[]{"tags", "smart", "map", "search", "settings", "logout", "media_types", "unknown"}) {
            assertFalse(selection.select(action));
            assertEquals("favorites", store.get(key()));
        }
        // Opening/dismissing a media dialog makes no select call; only photo/video IDs qualify.
        selection.succeeded(BrowseMode.FAVORITES, account);
        assertEquals("favorites", store.get(key()));
    }

    @Test public void unknownOrNoLongerImplementedStoredTypesFallBackAndAreRemoved() {
        for (String invalid : new String[]{"tags", "future_type", "media_types", "图库", "3"}) {
            store.put(key(), invalid);
            assertEquals(BrowseMode.GALLERY, selection().initial());
            assertNull(store.get(key()));
        }
    }

    @Test public void failedRestoreFallsBackOnlyOnceAndKeepsOriginalPreference() {
        store.put(key(), "albums");
        BrowseSelection selection = selection();
        selection.initial();
        assertEquals(BrowseMode.GALLERY, selection.failed(BrowseMode.ALBUMS));
        assertNull(selection.failed(BrowseMode.GALLERY));
        selection.succeeded(BrowseMode.GALLERY, account);
        assertEquals("albums", store.get(key()));
        assertEquals(BrowseMode.ALBUMS, selection().initial());
    }

    @Test public void manualChoiceAfterStartupFailureCanReplacePreference() {
        store.put(key(), "albums");
        BrowseSelection selection = selection();
        selection.initial();
        selection.failed(BrowseMode.ALBUMS);
        selection.select("gallery");
        selection.succeeded(BrowseMode.GALLERY, account);
        assertEquals("gallery", store.get(key()));
    }

    @Test public void obsoleteSuccessOrFailureCannotChangeNewerNavigation() {
        BrowseSelection selection = selection();
        selection.initial();
        selection.select("favorites");
        selection.select("people");
        selection.succeeded(BrowseMode.FAVORITES, account);
        assertNull(selection.failed(BrowseMode.FAVORITES));
        assertTrue(store.data.isEmpty());
        selection.succeeded(BrowseMode.PEOPLE, account);
        assertEquals("people", store.get(key()));
    }

    @Test public void manualNavigationPreventsLateStartupFailureFromForcingGallery() {
        store.put(key(), "albums");
        BrowseSelection selection = selection();
        selection.initial();
        selection.select("people");
        assertNull(selection.failed(BrowseMode.ALBUMS));
        assertNull(selection.failed(BrowseMode.PEOPLE));
    }

    @Test public void accountAndNasAreIsolated() {
        store.put(key(), "albums");
        String bob = BrowseSelection.accountId("nas.example:5666", "bob");
        String otherNas = BrowseSelection.accountId("other.example:5666", "alice");
        assertNotEquals(account, bob);
        assertNotEquals(account, otherNas);
        assertEquals(BrowseMode.GALLERY, new BrowseSelection(store, bob).initial());
        assertEquals(BrowseMode.GALLERY, new BrowseSelection(store, otherNas).initial());
        assertEquals(BrowseMode.ALBUMS, selection().initial());
    }

    @Test public void changingAccountDuringLoadRejectsOldSuccess() {
        BrowseSelection selection = selection();
        selection.initial();
        selection.select("albums");
        selection.succeeded(BrowseMode.ALBUMS, BrowseSelection.accountId("nas.example:5666", "bob"));
        assertTrue(store.data.isEmpty());
    }

    @Test public void logoutClearsOnlyThisAccountAndBlocksLateCallbacks() {
        store.put(key(), "albums");
        String other = BrowseSelection.accountId("nas.example:5666", "bob");
        store.put(BrowseSelection.MODE_PREFIX + other, "people");
        BrowseSelection selection = selection();
        selection.initial();
        selection.logout();
        selection.succeeded(BrowseMode.ALBUMS, account);
        assertNull(store.get(key()));
        assertFalse(selection.select("albums"));
        assertEquals("people", store.get(BrowseSelection.MODE_PREFIX + other));
    }

    @Test public void missingIdentityNeverUsesAGlobalPreferenceBucket() {
        BrowseSelection selection = new BrowseSelection(store, "");
        assertEquals(BrowseMode.GALLERY, selection.initial());
        selection.select("albums");
        selection.succeeded(BrowseMode.ALBUMS, "");
        assertTrue(store.data.isEmpty());
        assertEquals("", BrowseSelection.accountId("", "alice"));
        assertEquals("", BrowseSelection.accountId("nas.example", ""));
        assertEquals("", BrowseSelection.accountId(null, null));
    }

    @Test public void stableIdentityUsesSourceAndUsernameNotResolvedEndpointOrToken() {
        assertEquals(BrowseSelection.accountId("HTTP://NAS.EXAMPLE:80/", " alice "),
                BrowseSelection.accountId("nas.example", "alice"));
        assertEquals(BrowseSelection.accountId("wss://NAS.EXAMPLE:443/", "alice"),
                BrowseSelection.accountId("https://nas.example", "alice"));
        String fnIdAccount = BrowseSelection.accountId("my-fn-id", "alice");
        assertEquals(fnIdAccount, BrowseSelection.accountId("my-fn-id", "alice"));
        assertEquals(64, fnIdAccount.length());
        assertFalse(fnIdAccount.contains("alice"));
        assertNotEquals(fnIdAccount, BrowseSelection.accountId("192.0.2.1:5666", "alice"));
    }

    @Test public void mediaCategoriesShareMenuPositionButNotPersistedType() {
        assertEquals("media_types", BrowseMode.PHOTOS.menuAction());
        assertEquals("media_types", BrowseMode.VIDEOS.menuAction());
        assertNotEquals(BrowseMode.PHOTOS.id, BrowseMode.VIDEOS.id);
    }

    @Test public void sharedDirectoryIsRememberedOnlyAfterItsSuccessfulResponse() {
        store.put(key(), "albums");
        BrowseSelection selection = selection();
        selection.initial();
        assertEquals(BrowseMode.SHARED, BrowseMode.fromId("shared"));
        assertEquals("shared", BrowseMode.SHARED.menuAction());
        assertTrue(selection.select("shared"));
        assertEquals("albums", store.get(key()));
        assertNull(selection.failed(BrowseMode.SHARED));
        assertEquals("albums", store.get(key()));
        // A validated empty directory invokes the same success callback as a populated directory.
        selection.succeeded(BrowseMode.SHARED, account);
        assertEquals("shared", store.get(key()));
        assertEquals(BrowseMode.SHARED, selection().initial());
    }

    @Test public void sharedRestoreFailureFallsBackWithoutErasingPreference() {
        store.put(key(), "shared");
        BrowseSelection selection = selection();
        assertEquals(BrowseMode.SHARED, selection.initial());
        assertEquals(BrowseMode.GALLERY, selection.failed(BrowseMode.SHARED));
        selection.succeeded(BrowseMode.GALLERY, account);
        assertEquals("shared", store.get(key()));
        assertEquals(BrowseMode.SHARED, selection().initial());
    }

    @Test public void lateSharedResponseDoesNotOverrideNewerSelectionOrLogout() {
        BrowseSelection selection = selection();
        selection.initial();
        selection.select("shared");
        selection.select("people");
        selection.succeeded(BrowseMode.SHARED, account);
        assertTrue(store.data.isEmpty());
        selection.succeeded(BrowseMode.PEOPLE, account);
        assertEquals("people", store.get(key()));
        selection.select("shared");
        selection.logout();
        selection.succeeded(BrowseMode.SHARED, account);
        assertNull(store.get(key()));
    }

    @Test public void sharedPreferenceIsIsolatedFromOtherAccountAndUnsupportedDialogs() {
        store.put(key(), "shared");
        BrowseSelection selection = selection();
        selection.initial();
        for (String action : new String[]{"tags", "smart", "media_types", "search", "settings"}) {
            assertFalse(selection.select(action));
        }
        assertEquals("shared", store.get(key()));
        String bob = BrowseSelection.accountId("nas.example:5666", "bob");
        assertEquals(BrowseMode.GALLERY, new BrowseSelection(store, bob).initial());
    }

}
