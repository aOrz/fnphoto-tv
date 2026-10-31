package com.fnphoto.tv.player;

import com.fnphoto.tv.testing.LocalHttpServer;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SharedVideoStreamTest {
    private LocalHttpServer server;
    private SharedVideoStream stream;
    private String url;
    private final byte[] bytes = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final AtomicReference<String> range = new AtomicReference<>();
    @Before public void setup() throws Exception {
        server = LocalHttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/full", exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/partial", exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().set("Content-Range", "bytes 3-6/10");
            exchange.sendResponseHeaders(206, 4);
            exchange.getResponseBody().write(bytes, 3, 4); exchange.close();
        });
        server.createContext("/bad-range", exchange -> {
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-3/10");
            exchange.sendResponseHeaders(206, 4);
            exchange.getResponseBody().write(bytes, 0, 4); exchange.close();
        });
        server.createContext("/missing", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.createContext("/unknown", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        stream = new SharedVideoStream(new OkHttpClient());
    }
    @After public void cleanup() { stream.close(); server.stop(0); }
    private Request request(String path) { return new Request.Builder().url(url + path).build(); }
    private String readAll() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[3]; int n;
        while ((n = stream.read(buffer, 0, buffer.length)) != -1) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
    @Test public void fullBodyStreamsAndReportsStatusLengthUriAndHeaders() throws Exception {
        assertEquals(10, stream.open(request("/full"), 0, -1));
        assertNull(range.get()); assertEquals(200, stream.status());
        assertEquals(url + "/full", stream.uri()); assertFalse(stream.headers().isEmpty());
        assertEquals("0123456789", readAll());
        stream.close(); assertNull(stream.uri()); assertEquals(-1, stream.status());
    }
    @Test public void rangeSeekUses206AndHonorsLength() throws Exception {
        assertEquals(4, stream.open(request("/partial"), 3, 4));
        assertEquals("bytes=3-6", range.get()); assertEquals(206, stream.status());
        assertEquals("3456", readAll());
    }
    @Test public void ignoredRangeSkipsIncrementallyAndLimitsRead() throws Exception {
        assertEquals(4, stream.open(request("/full"), 3, 4));
        assertEquals("bytes=3-6", range.get()); assertEquals("3456", readAll());
        assertEquals(7, stream.open(request("/full"), 3, -1));
        assertEquals("bytes=3-", range.get()); assertEquals("3456789", readAll());
    }
    @Test public void invalidPartialResponseAndSeekPastEndFailClosed() throws Exception {
        try { stream.open(request("/bad-range"), 3, 4); fail(); } catch (IOException expected) { }
        try { stream.open(request("/full"), 11, -1); fail(); } catch (EOFException expected) { }
        assertNull(stream.uri());
    }
    @Test public void statusExceptionPreservesHttpStatusAndCanReopen() throws Exception {
        try { stream.open(request("/missing"), 0, -1); fail(); }
        catch (SharedVideoStream.StatusException expected) { assertEquals(404, expected.code); }
        assertEquals(10, stream.open(request("/full"), 0, -1));
        assertEquals("0123456789", readAll());
    }
    @Test public void unknownLengthUsesEofAndKnownLengthDetectsTruncation() throws Exception {
        assertEquals(-1, stream.open(request("/unknown"), 0, -1));
        assertEquals("0123456789", readAll());
        assertEquals(15, stream.open(request("/full"), 0, 15));
        try { readAll(); fail(); } catch (EOFException expected) { }
    }
    @Test public void closeCancelsPendingOpenWithoutDownloadingWholeBody() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/blocked", exchange -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { stream.open(request("/blocked"), 0, -1); }
            catch (Throwable e) { error.set(e); }
        });
        worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        stream.close(); worker.join(2000); release.countDown();
        assertFalse(worker.isAlive()); assertTrue(error.get() instanceof IOException);
    }
    @Test public void protectedClientBlocksCrossOriginRedirectBeforeTargetSeesCredentials() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean contacted = new java.util.concurrent.atomic.AtomicBoolean();
        LocalHttpServer target = LocalHttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/target", exchange -> {
            contacted.set(true); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        target.start();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/target");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        stream.close();
        stream = new SharedVideoStream(new OkHttpClient.Builder()
                .addNetworkInterceptor(new com.fnphoto.tv.api.CredentialRedirectInterceptor()).build());
        try {
            stream.open(request("/redirect").newBuilder().header("accesstoken", "synthetic-only").build(), 0, -1);
            fail();
        } catch (com.fnphoto.tv.api.CredentialRedirectInterceptor.BlockedRedirectException expected) {
            assertFalse(contacted.get());
        } finally { target.stop(0); }
    }
    @Test public void closeCancelsBlockedRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1), reading = new CountDownLatch(1);
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 10);
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        assertEquals(10, stream.open(request("/slow-body"), 0, -1));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            reading.countDown();
            try { stream.read(new byte[10], 0, 10); }
            catch (Throwable e) { error.set(e); }
        });
        worker.start(); assertTrue(reading.await(1, TimeUnit.SECONDS));
        stream.close(); worker.join(2000); release.countDown();
        assertFalse(worker.isAlive()); assertTrue(error.get() instanceof IOException);
    }

    @Test public void credentialsAreFreshAndOnlyAttachedToCurrentNasOrigin() throws Exception {
        Request first = SharedVideoStream.authenticate(request("/full"), url, "first-synthetic");
        Request refreshed = SharedVideoStream.authenticate(request("/full"), url, "refreshed-synthetic");
        assertEquals("first-synthetic", first.header("accesstoken"));
        assertEquals("refreshed-synthetic", refreshed.header("accesstoken"));
        assertNotNull(refreshed.header("authx"));
        for (String other : new String[]{"http://elsewhere.invalid/", url.replace("http:", "https:"),
                "http://127.0.0.1:1/", ""}) {
            try { SharedVideoStream.authenticate(request("/full"), other, "synthetic"); fail(); }
            catch (IOException expected) { }
        }
        try { SharedVideoStream.authenticate(request("/full"), url, ""); fail(); }
        catch (IOException expected) { }
        try { SharedVideoStream.authenticate(new Request.Builder().url(url.replace("http://", "http://user:pass@") + "/full").build(), url, "synthetic"); fail(); }
        catch (IOException expected) { }
    }

}
