package com.fnphoto.tv.api;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetadataResponseInterceptorTest {
    private ResponseBody unknownLength(String text) {
        Buffer buffer = new Buffer().writeUtf8(text);
        return new ResponseBody() {
            @Override public MediaType contentType() { return MediaType.parse("application/json"); }
            @Override public long contentLength() { return -1; }
            @Override public BufferedSource source() { return buffer; }
        };
    }

    @Test public void normalAndExactBudgetBodiesRemainReadable() throws Exception {
        assertEquals("1234", MetadataResponseInterceptor.limit(unknownLength("1234"), 4).string());
        assertEquals("{}", MetadataResponseInterceptor.limit(ResponseBody.create(null, "{}"), 4).string());
    }

    @Test public void declaredOversizeFailsBeforeDecoding() throws Exception {
        try {
            MetadataResponseInterceptor.limit(ResponseBody.create(null, "12345"), 4);
            fail("expected explicit bounded response failure");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("未读取全部")); }
    }

    @Test public void unknownChunkedOrDecompressedLengthCannotBypassBudget() throws Exception {
        ResponseBody body = MetadataResponseInterceptor.limit(unknownLength("12345"), 4);
        try { body.string(); fail("expected limit"); }
        catch (IOException expected) { }
    }

    @Test public void repeatedSmallReadsShareOneBudget() throws Exception {
        ResponseBody body = MetadataResponseInterceptor.limit(unknownLength("12345"), 4);
        try {
            while (!body.source().exhausted()) body.source().readByte();
            fail("must count cumulatively");
        } catch (IOException expected) { }
    }

    @Test public void metadataBudgetDoesNotCutOffActualPhotoOrVideoStreams() {
        assertTrue(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/gallery/getList"));
        assertTrue(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/search/results"));
        assertTrue(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/folder_view/getFileList"));
        assertFalse(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/stream/v/5"));
        assertFalse(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/stream/s/5"));
        assertFalse(MetadataResponseInterceptor.isMetadataPath("/elsewhere/gallery/list"));
    }
    @Test public void sharedAlbumDirectoryAndContentAreBoundedButStreamsAreNot() {
        assertTrue(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/album_grant/list_to_me"));
        assertTrue(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/album/normal/getList"));
        assertFalse(MetadataResponseInterceptor.isMetadataPath("/p/api/v1/stream/v/10010"));
    }
}
