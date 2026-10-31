package com.fnphoto.tv.player;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** Small streaming transport; the supplied client owns per-hop credential protection. */
final class SharedVideoStream {
    static final class StatusException extends IOException {
        final int code;
        final String message;
        final Map<String, List<String>> headers;
        StatusException(Response response) {
            super("HTTP " + response.code());
            code = response.code(); message = response.message(); headers = response.headers().toMultimap();
        }
    }
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)");
    private final Call.Factory client;
    private final Object readLock = new Object();
    private volatile Call call;
    private volatile Response response;
    private volatile InputStream input;
    private long remaining;
    private int status = -1;
    private String uri;
    private Map<String, List<String>> headers = Collections.emptyMap();

    SharedVideoStream(Call.Factory client) { this.client = client; }

    static Request authenticate(Request request, String endpoint, String token) throws IOException {
        if (endpoint == null || token == null || token.isEmpty()
                || !com.fnphoto.tv.api.NasEndpointRouter.sameOrigin(request.url(), endpoint)
                || !request.url().username().isEmpty() || !request.url().password().isEmpty())
            throw new IOException("Shared video URL is not the current authenticated NAS origin");
        return com.fnphoto.tv.api.RequestAuthentication.sign(request, token);
    }

    long open(Request request, long position, long length) throws IOException {
        close();
        if (position < 0 || length < -1 || length == 0 || (length > 0 && position > Long.MAX_VALUE - length))
            throw new IOException("Invalid stream range");
        Request.Builder builder = request.newBuilder().header("Accept-Encoding", "identity");
        if (position != 0 || length != -1) {
            builder.header("Range", "bytes=" + position + "-" + (length == -1 ? "" : position + length - 1));
        } else builder.removeHeader("Range");
        Call active = client.newCall(builder.build());
        call = active;
        try {
            Response opened = active.execute();
            response = opened;
            if (active.isCanceled()) throw new IOException("Stream canceled");
            status = opened.code(); uri = opened.request().url().toString(); headers = opened.headers().toMultimap();
            if (!opened.isSuccessful()) throw new StatusException(opened);
            if (opened.body() == null) throw new IOException("Missing response body");
            long skipped = status == 200 ? position : 0;
            long available = opened.body().contentLength();
            if (status == 206) {
                Matcher range = CONTENT_RANGE.matcher(opened.header("Content-Range", ""));
                if (!range.matches()) throw new IOException("Missing or invalid Content-Range");
                try {
                    long start = Long.parseLong(range.group(1)), end = Long.parseLong(range.group(2));
                    if (start != position || end < start || end == Long.MAX_VALUE)
                        throw new IOException("Unexpected Content-Range");
                    long rangeLength = end - start + 1;
                    if (available != -1 && available != rangeLength)
                        throw new IOException("Content-Length disagrees with Content-Range");
                    if (available == -1) available = rangeLength;
                } catch (NumberFormatException error) { throw new IOException("Invalid Content-Range", error); }
            } else if (status != 200) {
                throw new IOException("Unexpected successful stream response: " + status);
            }
            if (available >= 0 && skipped > available) throw new EOFException("Seek beyond response body");
            InputStream openedInput = opened.body().byteStream();
            input = openedInput;
            // A server may ignore Range. Skip incrementally, never buffer the entire video.
            byte[] discard = new byte[4096];
            synchronized (readLock) {
                while (skipped > 0) {
                    if (active.isCanceled() || Thread.currentThread().isInterrupted()) throw new IOException("Stream canceled");
                    int count = openedInput.read(discard, 0, (int) Math.min(skipped, discard.length));
                    if (count == -1) throw new EOFException("EOF while seeking");
                    skipped -= count;
                }
            }
            remaining = length != -1 ? length : available == -1 ? -1 : available - (status == 200 ? position : 0);
            return remaining;
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    int read(byte[] buffer, int offset, int length) throws IOException {
        synchronized (readLock) {
            if (length == 0) return 0;
            InputStream current = input;
            if (current == null) throw new IOException("Stream is not open");
            if (remaining == 0) return -1;
            int count = current.read(buffer, offset, remaining == -1 ? length : (int) Math.min(length, remaining));
            if (count == -1 && remaining > 0) throw new EOFException("Truncated video response");
            if (count > 0 && remaining != -1) remaining -= count;
            return count;
        }
    }

    void close() {
        Call active = call;
        call = null;
        if (active != null) active.cancel();
        // Cancel first to unblock a read; never close Okio while another thread reads it.
        synchronized (readLock) {
            Response opened = response;
            response = null;
            input = null;
            if (opened != null) opened.close();
        }
        uri = null; status = -1; headers = Collections.emptyMap();
    }
    int status() { return status; }
    String uri() { return uri; }
    Map<String, List<String>> headers() { return headers; }
}
