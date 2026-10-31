package com.fnphoto.tv.api;

import com.fnphoto.tv.paging.MetadataLimits;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Bound decompressed metadata before Gson allocates an unbounded response graph. */
public final class MetadataResponseInterceptor implements Interceptor {
    public static final class LimitExceededException extends IOException {
        LimitExceededException() { super("元数据响应超过安全上限，请缩小查询范围；未读取全部内容"); }
    }
    @Override public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        if (!isMetadataPath(response.request().url().encodedPath()) || response.body() == null)
            return response;
        return response.newBuilder().body(limit(response.body(), MetadataLimits.MAX_METADATA_RESPONSE_BYTES)).build();
    }

    static boolean isMetadataPath(String path) {
        // Do not apply a JSON budget to photo/video byte streams or third-party downloads.
        return path.startsWith("/p/api/") && (path.contains("/gallery/")
                || path.contains("/album/") || path.contains("/ai-person/")
                || path.contains("/folder_view/") || path.endsWith("/explore/geos")
                || path.endsWith("/search/results") || path.endsWith("/photo/search")
                || path.endsWith("/photo/folder/list"));
    }

    static ResponseBody limit(final ResponseBody body, final long maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        if (body.contentLength() > maxBytes) {
            body.close();
            throw tooLarge();
        }
        final BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
            private long consumed;
            @Override public long read(Buffer sink, long byteCount) throws IOException {
                if (byteCount == 0) return 0;
                long count = super.read(sink, Math.min(byteCount, maxBytes - consumed + 1));
                if (count > 0) consumed += count;
                if (consumed > maxBytes) {
                    close();
                    throw tooLarge();
                }
                return count;
            }
        });
        return new ResponseBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }
            @Override public BufferedSource source() { return source; }
        };
    }

    private static IOException tooLarge() {
        return new LimitExceededException();
    }
}
