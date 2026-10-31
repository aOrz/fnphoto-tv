package com.fnphoto.tv.api;

import java.io.IOException;
import java.net.ProtocolException;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Network interceptor: validate every redirect before OkHttp forwards NAS credentials. */
public final class CredentialRedirectInterceptor implements Interceptor {
    /** A policy rejection, not an expired token or a recoverable endpoint outage. */
    // OkHttp recognizes ProtocolException as non-retryable; a plain IOException
    // can otherwise replay the same rejected redirect as a connection recovery.
    public static final class BlockedRedirectException extends ProtocolException {
        BlockedRedirectException() {
            super("Refusing to forward NAS credentials to a different origin");
        }
    }

    @Override public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);
        if (hasCredentials(request) && isRedirect(response.code())) {
            String location = response.header("Location");
            HttpUrl target = location == null ? null : request.url().resolve(location);
            // Use the actual hop, not Call.request(): a verified NAS rediscovery may
            // have explicitly rerouted the initial request before reaching the network.
            if (target != null && !isSameOrigin(request.url(), target)) {
                response.close();
                throw new BlockedRedirectException();
            }
        }
        return response;
    }

    private static boolean hasCredentials(Request request) {
        return request.header("accesstoken") != null || request.header("authx") != null;
    }

    private static boolean isRedirect(int code) {
        return code == 300 || code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    static boolean isSameOrigin(HttpUrl source, HttpUrl target) {
        return source.scheme().equals(target.scheme()) && source.host().equals(target.host())
                && source.port() == target.port()
                && target.username().isEmpty() && target.password().isEmpty();
    }
}
