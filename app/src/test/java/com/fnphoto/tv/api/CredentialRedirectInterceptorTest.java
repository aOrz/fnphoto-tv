package com.fnphoto.tv.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback HTTP hops with synthetic headers; no NAS, account or network secrets. */
public class CredentialRedirectInterceptorTest {
    private LoopbackServer source;
    private LoopbackServer target;
    private String sourceUrl;
    private String targetUrl;
    private final AtomicInteger sourceHits = new AtomicInteger();
    private final AtomicInteger targetHits = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(302);
    private final AtomicReference<String> destination = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private OkHttpClient client;

    @Before public void setUp() throws Exception {
        source = new LoopbackServer(true);
        target = new LoopbackServer(false);
        sourceUrl = source.url();
        targetUrl = target.url();
        destination.set(targetUrl + "/target");
        source.start();
        target.start();
        client = new OkHttpClient.Builder().addNetworkInterceptor(new CredentialRedirectInterceptor())
                .callTimeout(3, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build();
    }

    /** Plain java.net keeps these loopback tests compatible with Android's test bootclasspath. */
    private final class LoopbackServer {
        private final ServerSocket listener;
        private final boolean isSource;
        private final Thread worker;
        private volatile boolean stopped;
        private volatile Throwable failure;

        LoopbackServer(boolean isSource) throws IOException {
            this.isSource = isSource;
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            worker = new Thread(() -> {
                while (!stopped) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String first = input.readLine();
                        if (first == null) continue;
                        String path = first.split(" ")[1];
                        String token = null;
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(java.util.Locale.US).startsWith("accesstoken:")) {
                                token = line.substring(line.indexOf(':') + 1).trim();
                            }
                        }
                        String location = null;
                        int code = 200;
                        if (isSource) {
                            sourceHits.incrementAndGet();
                            if ("/start".equals(path)) { location = destination.get(); code = status.get(); }
                            else if ("/middle".equals(path)) { location = targetUrl + "/target"; code = 302; }
                            else receivedToken.set(token);
                        } else { targetHits.incrementAndGet(); receivedToken.set(token); }
                        String reply = "HTTP/1.1 " + code + " Test\r\nContent-Length: 0\r\nConnection: close\r\n"
                                + (location == null ? "" : "Location: " + location + "\r\n") + "\r\n";
                        socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    } catch (IOException error) { if (!stopped) failure = error; }
                }
            }, "credential-redirect-loopback");
            worker.setDaemon(true);
        }

        String url() { return "http://127.0.0.1:" + listener.getLocalPort(); }
        void start() { worker.start(); }
        void stop() throws Exception {
            stopped = true;
            listener.close();
            worker.join(3000);
            assertFalse("Loopback worker stopped", worker.isAlive());
            if (failure != null) throw new AssertionError("Loopback server failed", failure);
        }
    }

    @After public void tearDown() throws Exception {
        if (source != null) source.stop();
        if (target != null) target.stop();
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private Request authenticated(String path) {
        return new Request.Builder().url(sourceUrl + path)
                .header("accesstoken", "SYNTHETIC-NOT-A-SECRET")
                .header("authx", "SYNTHETIC-SIGNATURE").build();
    }

    private void assertBlocked(Request request) throws Exception {
        try (Response ignored = client.newCall(request).execute()) {
            fail("Expected cross-origin credential redirect to be blocked");
        } catch (CredentialRedirectInterceptor.BlockedRedirectException expected) {
            assertFalse(expected.getMessage().contains("SYNTHETIC"));
        }
        assertEquals("The second origin must never receive a request", 0, targetHits.get());
    }

    @Test public void allSupportedRedirectCodesBlockCredentialsBeforeNextOrigin() throws Exception {
        for (int code : new int[] {300, 301, 302, 303, 307, 308}) {
            status.set(code);
            assertBlocked(authenticated("/start"));
        }
        assertEquals(6, sourceHits.get());
    }

    @Test public void sameOriginRedirectStillWorksWithCredentials() throws Exception {
        destination.set("/ok");
        try (Response response = client.newCall(authenticated("/start")).execute()) {
            assertEquals(200, response.code());
            assertEquals(sourceUrl + "/ok", response.request().url().toString());
        }
        assertEquals("SYNTHETIC-NOT-A-SECRET", receivedToken.get());
        assertEquals(0, targetHits.get());
    }

    @Test public void everyHopIsCheckedAfterAnAllowedSameOriginRedirect() throws Exception {
        destination.set("/middle");
        assertBlocked(authenticated("/start"));
        assertEquals(2, sourceHits.get());
    }

    @Test public void tokenOnlyAndSignatureOnlyAreBothProtected() throws Exception {
        assertBlocked(authenticated("/start").newBuilder().removeHeader("authx").build());
        assertBlocked(authenticated("/start").newBuilder().removeHeader("accesstoken").build());
    }

    @Test public void publicRequestsCanStillRedirectWithoutCredentials() throws Exception {
        try (Response response = client.newCall(new Request.Builder().url(sourceUrl + "/start").build()).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals(1, targetHits.get());
        assertNull(receivedToken.get());
    }

    @Test public void explicitEndpointRerouteUsesActualHopNotOriginalCallOrigin() throws Exception {
        destination.set("/ok");
        client = client.newBuilder().addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .url(sourceUrl + "/start").build())).build();
        // Models verified NAS rediscovery by the existing application interceptor.
        Request original = authenticated("/start").newBuilder().url(targetUrl + "/original").build();
        try (Response response = client.newCall(original).execute()) { assertEquals(200, response.code()); }
        assertEquals(2, sourceHits.get());
        assertEquals(0, targetHits.get());
    }

    @Test public void schemeHostPortAndUserInfoAreAllGuarded() {
        HttpUrl origin = HttpUrl.parse("https://nas.example:5667/path");
        assertTrue(CredentialRedirectInterceptor.isSameOrigin(origin, origin.resolve("/other")));
        for (String other : new String[] {"http://nas.example:5667/path", "https://other.example:5667/path",
                "https://nas.example/path", "https://user@nas.example:5667/path"}) {
            assertFalse(other, CredentialRedirectInterceptor.isSameOrigin(origin, HttpUrl.parse(other)));
        }
    }
}
