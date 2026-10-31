package com.fnphoto.tv.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class RememberedNasTargetTest {
    @Test public void legacyEndpointStillWorks() throws Exception {
        String source = RememberedNasTarget.source("", "http://192.0.2.1:5666");
        assertEquals("http://192.0.2.1:5666", RememberedNasTarget.resolve(source, id -> { throw new AssertionError(); }));
    }
    @Test public void rememberedFnIdIsRediscoveredRatherThanSavedIp() throws Exception {
        String source = RememberedNasTarget.source("my-nas", "http://192.0.2.1:5666");
        assertEquals("my-nas", source);
        assertEquals("http://192.0.2.2:5666", RememberedNasTarget.resolve(source, id -> {
            assertEquals("my-nas", id); return "http://192.0.2.2:5666";
        }));
    }
    @Test(expected = java.io.IOException.class) public void failedDiscoveryNeverFallsBackToStaleEndpoint() throws Exception {
        RememberedNasTarget.resolve("my-nas", id -> null);
    }
}
