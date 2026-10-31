package com.fnphoto.tv;

import com.google.gson.Gson;
import com.fnphoto.tv.paging.MetadataLimits;
import okhttp3.HttpUrl;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;

/** Private, bounded-lifetime disk snapshots; only the random key goes through Binder. */
final class DetailSessionStore {
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    static final int MAX_SESSIONS = 8;
    private final File directory;
    private final Gson gson = new Gson();

    static final class Snapshot {
        String owner;
        String sourceEndpoint;
        List<MediaItem> items;
        boolean hadMore;
    }

    DetailSessionStore(File privateCacheDirectory) {
        directory = new File(privateCacheDirectory, "detail_sessions");
        directory.mkdirs();
        prune();
    }

    private void prune() {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int retained = 0;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (file.getName().endsWith(".tmp") || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
                    || file.length() > MetadataLimits.MAX_METADATA_RESPONSE_BYTES || retained >= MAX_SESSIONS) {
                file.delete();
            } else {
                retained++;
            }
        }
    }

    String newId() { return UUID.randomUUID().toString(); }

    private File file(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Invalid session ID");
        return new File(directory, id + ".json");
    }

    void save(String id, String owner, List<MediaItem> items, boolean hadMore) throws IOException {
        save(id, owner, items, hadMore, null);
    }

    void save(String id, String owner, List<MediaItem> items, boolean hadMore, String sourceEndpoint) throws IOException {
        if (items == null || items.size() > MetadataLimits.MAX_ITEMS)
            throw new IOException("Detail snapshot exceeds the current browsing window");
        Snapshot snapshot = new Snapshot();
        snapshot.owner = owner;
        snapshot.sourceEndpoint = sourceEndpoint;
        snapshot.items = new ArrayList<>(items);
        snapshot.hadMore = hadMore;
        File destination = file(id);
        File temporary = new File(directory, id + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FilterOutputStream(new FileOutputStream(temporary)) {
            private long count;
            @Override public void write(int value) throws IOException {
                reserve(1);
                out.write(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                reserve(length);
                out.write(bytes, offset, length);
            }
            private void reserve(int length) throws IOException {
                if (count + length > MetadataLimits.MAX_METADATA_RESPONSE_BYTES)
                    throw new IOException("Detail snapshot exceeds byte budget");
                count += length;
            }
        }, "UTF-8")) {
            gson.toJson(snapshot, writer);
        } catch (IOException | RuntimeException failure) {
            temporary.delete();
            throw new IOException("Cannot persist bounded detail session", failure);
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Cannot persist detail session");
        }
        prune();
    }

    Snapshot read(String id, String owner) { return read(id, owner, null); }

    Snapshot read(String id, String owner, String currentEndpoint) {
        try {
            File source = file(id);
            if (!source.exists() || source.length() > MetadataLimits.MAX_METADATA_RESPONSE_BYTES
                    || System.currentTimeMillis() - source.lastModified() > MAX_AGE_MS) return null;
            try (Reader reader = new InputStreamReader(new FileInputStream(source), "UTF-8")) {
                Snapshot snapshot = gson.fromJson(reader, Snapshot.class);
                if (snapshot == null || owner == null || !owner.equals(snapshot.owner)
                        || snapshot.items == null || snapshot.items.isEmpty()
                        || snapshot.items.size() > MetadataLimits.MAX_ITEMS) return null;
                for (MediaItem item : snapshot.items) if (item == null || item.getId() == null) return null;
                if (snapshot.sourceEndpoint != null && currentEndpoint != null) {
                    List<MediaItem> routed = new ArrayList<>();
                    for (MediaItem item : snapshot.items) {
                        MediaItem copy = new MediaItem(item.getId(), item.getTitle(), item.getType(),
                                route(item.getThumbnailUrl(), snapshot.sourceEndpoint, currentEndpoint),
                                route(item.getMediaUrl(), snapshot.sourceEndpoint, currentEndpoint));
                        copy.setDateStr(item.getDateStr());
                        copy.setPhotoCount(item.getPhotoCount());
                        if (item.getPreviewThumbUrls() != null) {
                            List<String> previews = new ArrayList<>();
                            for (String url : item.getPreviewThumbUrls())
                                previews.add(route(url, snapshot.sourceEndpoint, currentEndpoint));
                            copy.setPreviewThumbUrls(previews);
                        }
                        routed.add(copy);
                    }
                    snapshot.items = routed;
                    snapshot.sourceEndpoint = currentEndpoint;
                }
                return snapshot;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Rebind only NAS API URLs from the exact origin recorded by this private snapshot. */
    static String route(String url, String trustedSource, String endpoint) {
        if (url == null || trustedSource == null || endpoint == null) return url;
        HttpUrl request = HttpUrl.parse(url);
        HttpUrl source = HttpUrl.parse(trustedSource);
        HttpUrl current = HttpUrl.parse(endpoint);
        if (request == null || source == null || current == null
                || !request.scheme().equals(source.scheme()) || !request.host().equals(source.host())
                || request.port() != source.port() || !request.encodedPath().startsWith("/p/api/")) return url;
        return request.newBuilder().scheme(current.scheme()).host(current.host()).port(current.port()).build().toString();
    }

    void delete(String id) {
        try { file(id).delete(); } catch (IllegalArgumentException ignored) { }
    }
}
