package com.fnphoto.tv.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class GeoPhotoResponseReaderTest {
    private static final class Source extends ByteArrayInputStream {
        boolean closed;
        Source(String value) { super(value.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() throws IOException { closed = true; super.close(); }
        int consumed() { return pos; }
    }
    private GeoPhotoResponseReader.Result read(String json, int max) throws IOException {
        return GeoPhotoResponseReader.read(new Source(json), max, 2 * 1024 * 1024);
    }
    private String photo(int id) {
        return "{\"id\":" + id + ",\"fileName\":\"a.jpg\",\"category\":\"photo\"}";
    }
    @Test public void safelySerializesQuotedCountryAndCityWithoutNewParameters() {
        String country = "C\"\\\n", city = "x\"],\"offset\":999";
        JsonObject body = new JsonParser().parse(GeoPhotoResponseReader.requestBody(country, city)).getAsJsonObject();
        assertEquals(3, body.entrySet().size());
        JsonObject filter = body.getAsJsonArray("filters").get(0).getAsJsonObject();
        assertEquals(country, filter.get("filterValue").getAsString());
        assertEquals(city, filter.getAsJsonArray("subFilters").get(0).getAsJsonObject().get("filterValue").getAsString());
        assertFalse(body.has("offset"));
        assertFalse(body.has("limit"));
    }
    @Test public void absenceOfPagingMetadataNeverMeansComplete() throws Exception {
        GeoPhotoResponseReader.Result result = read("{\"code\":0,\"data\":{\"list\":[],\"count\":0}}", 2);
        assertEquals(GeoPhotoResponseReader.Completeness.UNKNOWN, result.completeness);
    }
    @Test public void explicitEndIsOnlyServerReportedNotIndependentProof() throws Exception {
        assertEquals(GeoPhotoResponseReader.Completeness.SERVER_REPORTED_END,
                read("{\"code\":0,\"data\":{\"list\":[],\"hasNext\":false}}", 2).completeness);
    }
    @Test public void reportedMoreAndConflictingTotalRemainIncomplete() throws Exception {
        assertEquals(GeoPhotoResponseReader.Completeness.SERVER_HAS_MORE,
                read("{\"code\":0,\"data\":{\"list\":[],\"hasNext\":true}}", 2).completeness);
        assertEquals(GeoPhotoResponseReader.Completeness.SERVER_HAS_MORE,
                read("{\"code\":0,\"data\":{\"list\":[],\"hasNext\":false,\"total\":10}}", 2).completeness);
    }
    @Test public void impossibleSmallerTotalDoesNotClaimServerEnd() throws Exception {
        assertEquals(GeoPhotoResponseReader.Completeness.UNKNOWN,
                read("{\"code\":0,\"data\":{\"list\":[" + photo(1)
                        + "],\"hasNext\":false,\"total\":0}}", 2).completeness);
    }
    @Test public void largeResponseRetainsOnlyConfiguredUniqueItems() throws Exception {
        StringBuilder json = new StringBuilder("{\"code\":0,\"data\":{\"list\":[");
        for (int i = 1; i <= 10000; i++) { if (i > 1) json.append(','); json.append(photo(i)); }
        json.append("],\"hasNext\":false,\"total\":10000}}");
        GeoPhotoResponseReader.Result result = read(json.toString(), 100);
        assertEquals(100, result.photos.size());
        assertEquals(10000, result.rawCount);
        assertEquals(GeoPhotoResponseReader.Completeness.CLIENT_LIMIT, result.completeness);
        assertEquals(100, result.photos.get(99).id);
        try { result.photos.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void duplicatesDoNotConsumeRetainedBudgetBeforeCap() throws Exception {
        GeoPhotoResponseReader.Result result = read("{\"code\":0,\"data\":{\"list\":["
                + photo(1) + "," + photo(1) + "," + photo(2) + "]}}", 2);
        assertEquals(2, result.photos.size());
        assertEquals(3, result.rawCount);
    }
    @Test public void statusAfterDataStillValidatedBeforeDelivery() throws Exception {
        Source source = new Source("{\"data\":{\"list\":[" + photo(1) + "]},\"code\":401}");
        try { GeoPhotoResponseReader.read(source, 5, 1000); fail(); } catch (IOException expected) { }
        assertTrue(source.closed);
    }
    @Test public void rejectsMissingContradictoryAndDuplicatedStatus() throws Exception {
        String[] json = {"{\"data\":{\"list\":[]}}", "{\"code\":0,\"errno\":1,\"data\":{\"list\":[]}}",
                "{\"code\":0,\"code\":0,\"data\":{\"list\":[]}}", "{\"code\":0,\"data\":{\"list\":null}}"};
        for (String value : json) {
            try { read(value, 2); fail(value); } catch (IOException expected) { }
        }
    }
    @Test public void rejectsMalformedTruncatedAndTrailingPayloadsAndCloses() throws Exception {
        String[] values = {"{\"code\":0,\"data\":{\"list\":[", "{\"code\":0,\"data\":{\"list\":[]}} {}",
                "{\"code\":0,\"data\":{\"list\":[{}]}}"};
        for (String value : values) {
            Source source = new Source(value);
            try { GeoPhotoResponseReader.read(source, 2, 1000); fail(value); } catch (IOException expected) { }
            assertTrue(source.closed);
        }
    }
    @Test public void byteLimitCountsUtf8BytesAndClosesWithoutDeliveringPartialItems() throws Exception {
        Source source = new Source("{\"code\":0,\"data\":{\"list\":[" + photo(1) + "],\"extra\":\"北京北京北京\"}}");
        try { GeoPhotoResponseReader.read(source, 10, 90); fail(); }
        catch (GeoPhotoResponseReader.LimitExceededException expected) { }
        assertTrue(source.closed);
        assertEquals(91, source.consumed());
    }
    @Test public void exactByteLimitSucceedsAndCloses() throws Exception {
        String json = "{\"code\":0,\"data\":{\"list\":[]}}";
        Source source = new Source(json);
        GeoPhotoResponseReader.read(source, 1, json.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(source.closed);
    }
    @Test public void threadInterruptionClosesAndDoesNotBecomeEmptySuccess() throws Exception {
        Source source = new Source("{\"code\":0,\"data\":{\"list\":[]}}");
        Thread.currentThread().interrupt();
        try {
            try { GeoPhotoResponseReader.read(source, 2, 1000); fail(); }
            catch (InterruptedIOException expected) { }
            assertTrue(source.closed);
        } finally { Thread.interrupted(); }
    }
    @Test public void onlyDisplayFieldsAreRetained() throws Exception {
        GeoPhotoResponseReader.Result result = read("{\"code\":0,\"data\":{\"list\":[{\"id\":1,\"description\":\"discard\","
                + "\"additional\":{\"tags\":[1,2,3],\"thumbnail\":{\"mUrl\":\"/m\",\"videoUrl\":\"/v\"}}}]}}", 2);
        FnHttpApi.GalleryPhoto photo = result.photos.get(0);
        assertNull(photo.description);
        assertNull(photo.additional.tags);
        assertEquals("/m", photo.additional.thumbnail.mUrl);
        assertEquals("/v", photo.additional.thumbnail.videoUrl);
    }
}
