package com.fnphoto.tv.api;

import java.util.HashSet;
import java.util.Set;
import okhttp3.HttpUrl;

/** Maps only previously authenticated origins for the same remembered NAS account. */
public final class NasEndpointRouter {
    private String identity;
    private final Set<String> origins = new HashSet<>();

    public synchronized HttpUrl route(String account, String currentEndpoint, HttpUrl request) {
        HttpUrl current = HttpUrl.parse(currentEndpoint);
        if (current == null) return request;
        if (!account.equals(identity)) {
            origins.clear();
            identity = account;
        }
        origins.add(origin(current));
        if (!origins.contains(origin(request)) || !request.encodedPath().startsWith("/p/api/")) return request;
        return request.newBuilder().scheme(current.scheme()).host(current.host()).port(current.port()).build();
    }

    public synchronized void remember(String account, String previous, String current) {
        HttpUrl oldUrl = HttpUrl.parse(previous);
        HttpUrl newUrl = HttpUrl.parse(current);
        if (oldUrl == null || newUrl == null) return;
        if (!account.equals(identity)) {
            origins.clear();
            identity = account;
        }
        origins.add(origin(oldUrl));
        origins.add(origin(newUrl));
    }

    public static boolean sameOrigin(HttpUrl request, String endpoint) {
        HttpUrl other = HttpUrl.parse(endpoint);
        return other != null && origin(request).equals(origin(other));
    }

    private static String origin(HttpUrl url) {
        return url.scheme() + "://" + url.host() + ":" + url.port();
    }
}
