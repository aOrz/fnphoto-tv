package com.fnphoto.tv;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class DetailSessionStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void largeLoadedCollectionSurvivesNewStoreWithoutBinderPayload() throws Exception {
        File root = temporary.newFolder();
        DetailSessionStore store = new DetailSessionStore(root);
        String id = store.newId();
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < 5000; i++) items.add(new MediaItem("" + i, "photo", "photo", "thumb", "original"));
        store.save(id, "owner", items, true);
        DetailSessionStore.Snapshot loaded = new DetailSessionStore(root).read(id, "owner");
        assertNotNull(loaded);
        assertEquals(5000, loaded.items.size());
        assertEquals("4999", loaded.items.get(4999).getId());
        assertTrue(loaded.hadMore);
        assertEquals(36, id.length());
    }

    @Test public void otherLoginCannotRestoreAndExplicitFinishDeletesSnapshot() throws Exception {
        DetailSessionStore store = new DetailSessionStore(temporary.newFolder());
        String id = store.newId();
        store.save(id, "first", Collections.singletonList(new MediaItem("1", "x", "photo", "t", "m")), false);
        assertNull(store.read(id, "second"));
        assertNotNull(store.read(id, "first"));
        store.delete(id);
        assertNull(store.read(id, "first"));
    }

    @Test public void missingInvalidCorruptAndExpiredSnapshotsFailSafely() throws Exception {
        File root = temporary.newFolder();
        DetailSessionStore store = new DetailSessionStore(root);
        assertNull(store.read("../../outside", "owner"));
        assertNull(store.read(null, "owner"));
        assertNull(store.read(store.newId(), "owner"));
        String id = store.newId();
        File file = new File(new File(root, "detail_sessions"), id + ".json");
        try (FileWriter writer = new FileWriter(file)) { writer.write("bad json"); }
        assertNull(store.read(id, "owner"));
        store.save(id, "owner", Collections.singletonList(new MediaItem("1", "x", "photo", "t", "m")), false);
        assertTrue(file.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000));
        assertNull(store.read(id, "owner"));
        new DetailSessionStore(root);
        assertFalse(file.exists());
    }

    @Test public void favoriteTargetUsesActualStateNotErrorFallback() {
        assertEquals(1, CollectState.target(0));
        assertEquals(0, CollectState.target(1));
        assertEquals(-1, CollectState.target(2));
        assertEquals(-1, CollectState.target(-1));
    }
    @Test public void restoreRemapsOnlyTrustedNasApiOrigin() throws Exception {
        DetailSessionStore store = new DetailSessionStore(temporary.newFolder());
        String id = store.newId();
        MediaItem photo = new MediaItem("7", "photo", "photo",
                "http://old:5666/p/api/t/7?size=m", "http://old:5666/p/api/o/7");
        photo.setDateStr("2026-10-31");
        photo.setPhotoCount(3);
        photo.setPreviewThumbUrls(java.util.Arrays.asList("http://old:5666/p/api/t/7", "https://cdn/p/api/t/7"));
        store.save(id, "owner", Collections.singletonList(photo), true, "http://old:5666");
        DetailSessionStore.Snapshot snapshot = store.read(id, "owner", "https://new:443");
        assertEquals("https://new/p/api/t/7?size=m", snapshot.items.get(0).getThumbnailUrl());
        assertEquals("https://new/p/api/o/7", snapshot.items.get(0).getMediaUrl());
        assertEquals("2026-10-31", snapshot.items.get(0).getDateStr());
        assertEquals(3, snapshot.items.get(0).getPhotoCount());
        assertEquals("https://new/p/api/t/7", snapshot.items.get(0).getPreviewThumbUrls().get(0));
        assertEquals("https://cdn/p/api/t/7", snapshot.items.get(0).getPreviewThumbUrls().get(1));
        assertEquals("https://new:443", snapshot.sourceEndpoint);
        assertNull(store.read(id, "different-owner", "https://new"));
    }

    @Test public void routingRejectsOtherOriginsAndNonApiPaths() {
        String[] untouched = {"https://old:5666/p/api/x", "http://old:80/p/api/x",
                "http://old.evil:5666/p/api/x", "http://old:5666/private/x", "http://old:5666/p/apix/x",
                "https://cdn/p/api/x", "bad-url"};
        for (String url : untouched) assertEquals(url, DetailSessionStore.route(url, "http://old:5666", "https://new"));
        assertNull(DetailSessionStore.route(null, "http://old", "https://new"));
        assertEquals("http://old/p/api/x", DetailSessionStore.route("http://old/p/api/x", null, "https://new"));
    }
}
