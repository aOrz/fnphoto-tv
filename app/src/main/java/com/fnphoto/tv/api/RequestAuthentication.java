package com.fnphoto.tv.api;

import java.io.IOException;
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;

/** Re-sign the bytes actually sent, not a POST request's (usually absent) query. */
public final class RequestAuthentication {
    private RequestAuthentication() {}

    public static String payload(Request request) throws IOException {
        if ("GET".equalsIgnoreCase(request.method())) return request.url().query();
        RequestBody body = request.body();
        if (body == null) return "";
        String type = body.contentType() == null ? "" : body.contentType().toString();
        // The app's replayable mutations are small form or JSON requests. Never consume
        // an unknown streaming/upload body in order to retry it.
        if (!(body instanceof FormBody) && !type.startsWith("application/json")) {
            throw new IOException("Request body cannot safely be signed for retry");
        }
        if (body.contentLength() < 0 || body.contentLength() > 1024 * 1024) {
            throw new IOException("Request body is too large for authentication retry");
        }
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readUtf8();
    }

    public static Request sign(Request request, String token) throws IOException {
        String authx = FnAuthUtils.generateAuthX(request.url().encodedPath(), request.method(), payload(request));
        if (authx == null) throw new IOException("Unable to sign authentication retry");
        return request.newBuilder().header("accesstoken", token).header("authx", authx).build();
    }
}
