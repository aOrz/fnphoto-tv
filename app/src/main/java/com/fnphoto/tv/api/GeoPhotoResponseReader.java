package com.fnphoto.tv.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded reader for the existing, unpaged location-search contract. This is NOT
 * invented server pagination: limits protect this client and are never sent to the NAS.
 */
public final class GeoPhotoResponseReader {
    private GeoPhotoResponseReader() {}

    public enum Completeness { SERVER_REPORTED_END, UNKNOWN, SERVER_HAS_MORE, CLIENT_LIMIT }

    public static final class Result {
        public final List<FnHttpApi.GalleryPhoto> photos;
        public final int rawCount;
        public final Integer reportedTotal;
        public final Boolean hasNext;
        public final Completeness completeness;

        private Result(List<FnHttpApi.GalleryPhoto> photos, int rawCount,
                Integer total, Boolean hasNext, boolean limited) {
            this.photos = Collections.unmodifiableList(photos);
            this.rawCount = rawCount;
            this.reportedTotal = total;
            this.hasNext = hasNext;
            completeness = limited ? Completeness.CLIENT_LIMIT
                    : Boolean.TRUE.equals(hasNext) || (total != null && total > rawCount)
                    ? Completeness.SERVER_HAS_MORE
                    : total != null && total < rawCount ? Completeness.UNKNOWN
                    : Boolean.FALSE.equals(hasNext) ? Completeness.SERVER_REPORTED_END
                    : Completeness.UNKNOWN;
        }
    }

    public static final class LimitExceededException extends IOException {
        public LimitExceededException() { super("Location response exceeds client byte limit"); }
    }

    /** JSON serialization also ensures quoted city/country names cannot change the filter. */
    public static String requestBody(String country, String city) {
        JsonObject sub = new JsonObject();
        sub.addProperty("filterName", country == null ? "" : country);
        sub.addProperty("filterValue", city == null ? "" : city);
        JsonArray subs = new JsonArray();
        subs.add(sub);
        JsonObject filter = new JsonObject();
        filter.addProperty("filterName", "photo_location");
        filter.addProperty("filterValue", country == null ? "" : country);
        filter.add("subFilters", subs);
        JsonArray filters = new JsonArray();
        filters.add(filter);
        JsonObject body = new JsonObject();
        body.addProperty("keyword", "");
        body.add("filters", filters);
        body.add("antiFilters", new JsonArray());
        return body.toString();
    }

    /**
     * Closes the source on success/failure, retains at most maxItems unique photos,
     * and reads at most maxBytes + 1 bytes (the extra byte detects overflow).
     * An oversized/truncated/invalid envelope is an error, never a successful partial page.
     */
    public static Result read(InputStream source, int maxItems, long maxBytes) throws IOException {
        if (source == null) throw new IOException("Missing location response body");
        if (maxItems < 1 || maxBytes < 1 || maxBytes == Long.MAX_VALUE)
            throw new IllegalArgumentException("Positive finite limits required");
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new LimitedStream(source, maxBytes), "UTF-8"))) {
            reader.setLenient(false);
            FnHttpApi.BaseResponse status = new FnHttpApi.BaseResponse();
            Data data = null;
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (("code".equals(name) || "errno".equals(name) || "result".equals(name)
                        || "data".equals(name)) && !fields.add(name))
                    throw new IOException("Duplicate response field: " + name);
                switch (name) {
                    case "code": status.code = nullableInt(reader); break;
                    case "errno": {
                        Integer value = nullableInt(reader);
                        status.errno = value == null ? Integer.MIN_VALUE : value;
                        break;
                    }
                    case "result": status.result = nullableString(reader); break;
                    case "data": data = readData(reader, maxItems); break;
                    default: reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing response data");
            if (!status.isSuccessful()) throw new IOException("Location API rejected the request");
            if (data == null || !data.sawList) throw new IOException("Missing location result list");
            return new Result(data.photos, data.rawCount, data.total, data.hasNext, data.limited);
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Malformed location response", e);
        }
    }

    private static final class Data {
        final List<FnHttpApi.GalleryPhoto> photos = new ArrayList<>();
        final Set<Integer> ids = new HashSet<>();
        int rawCount;
        Integer total;
        Boolean hasNext;
        boolean sawList;
        boolean limited;
    }

    private static Data readData(JsonReader reader, int maxItems) throws IOException {
        Data data = new Data();
        Set<String> fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (("list".equals(name) || "total".equals(name) || "hasNext".equals(name))
                    && !fields.add(name)) throw new IOException("Duplicate location data field: " + name);
            if ("list".equals(name)) {
                reader.beginArray();
                data.sawList = true;
                while (reader.hasNext()) {
                    data.rawCount++;
                    if (data.photos.size() >= maxItems) {
                        data.limited = true;
                        reader.skipValue();
                    } else {
                        FnHttpApi.GalleryPhoto photo = readPhoto(reader);
                        if (data.ids.add(photo.id)) data.photos.add(photo);
                    }
                }
                reader.endArray();
            } else if ("total".equals(name)) {
                data.total = nullableInt(reader);
                if (data.total != null && data.total < 0) throw new IOException("Negative location total");
            } else if ("hasNext".equals(name)) {
                if (reader.peek() == JsonToken.NULL) reader.nextNull();
                else data.hasNext = reader.nextBoolean();
            } else {
                // count is not an established total: do not infer completeness from it.
                reader.skipValue();
            }
        }
        reader.endObject();
        return data;
    }

    private static FnHttpApi.GalleryPhoto readPhoto(JsonReader reader) throws IOException {
        FnHttpApi.GalleryPhoto photo = new FnHttpApi.GalleryPhoto();
        boolean sawId = false;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "id": photo.id = reader.nextInt(); sawId = true; break;
                case "fileName": photo.fileName = nullableString(reader); break;
                case "category": photo.category = nullableString(reader); break;
                case "additional": photo.additional = readAdditional(reader); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        if (!sawId || photo.id <= 0) throw new IOException("Missing or invalid location photo ID");
        return photo;
    }

    private static FnHttpApi.GalleryPhotoAdditional readAdditional(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
        FnHttpApi.GalleryPhotoAdditional value = new FnHttpApi.GalleryPhotoAdditional();
        reader.beginObject();
        while (reader.hasNext()) {
            if ("thumbnail".equals(reader.nextName())) value.thumbnail = readThumbnail(reader);
            else reader.skipValue();
        }
        reader.endObject();
        return value;
    }

    private static FnHttpApi.GalleryThumbnail readThumbnail(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
        FnHttpApi.GalleryThumbnail value = new FnHttpApi.GalleryThumbnail();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "mUrl": value.mUrl = nullableString(reader); break;
                case "sUrl": value.sUrl = nullableString(reader); break;
                case "originalUrl": value.originalUrl = nullableString(reader); break;
                case "videoUrl": value.videoUrl = nullableString(reader); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        return value;
    }

    private static Integer nullableInt(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
        return reader.nextInt();
    }

    private static String nullableString(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
        return reader.nextString();
    }

    private static final class LimitedStream extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedStream(InputStream input, long limit) { super(input); this.limit = limit; }
        private void checkInterrupted() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Location read interrupted");
        }
        private void consumed(int read) throws LimitExceededException {
            if (read > 0 && (count += read) > limit) throw new LimitExceededException();
        }
        @Override public int read() throws IOException {
            checkInterrupted();
            int value = in.read();
            if (value >= 0) consumed(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkInterrupted();
            int allowed = (int) Math.min(length, limit - count + 1);
            int read = in.read(buffer, offset, allowed);
            consumed(read);
            return read;
        }
    }
}
