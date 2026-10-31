package com.fnphoto.tv.api;

import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class NasEndpointRouterTest {
    @Test public void movesOnlyKnownNasPathsPreservingQuery() {
        NasEndpointRouter router = new NasEndpointRouter();
        router.remember("account", "http://192.0.2.1:5666", "http://192.0.2.2:5666");
        assertEquals("http://192.0.2.2:5666/p/api/v1/photo/search?keyword=x", router.route("account", "http://192.0.2.2:5666",
            HttpUrl.parse("http://192.0.2.1:5666/p/api/v1/photo/search?keyword=x")).toString());
        HttpUrl external = HttpUrl.parse("https://other.example/p/api/v1/photo/1");
        assertEquals(external, router.route("account", "http://192.0.2.2:5666", external));
        HttpUrl otherPath = HttpUrl.parse("http://192.0.2.1:5666/not-photo");
        assertEquals(otherPath, router.route("account", "http://192.0.2.2:5666", otherPath));
    }
    @Test public void accountChangeForgetsAllPreviousOrigins() {
        NasEndpointRouter router = new NasEndpointRouter();
        router.remember("alice", "http://192.0.2.1:5666", "http://192.0.2.2:5666");
        HttpUrl alice = HttpUrl.parse("http://192.0.2.1:5666/p/api/v1/photo/1");
        assertEquals(alice, router.route("bob", "http://192.0.2.3:5666", alice));
    }
}
