package com.fnphoto.tv.player;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;

public class VideoPlaybackFailureTest {
    private HttpDataSource.InvalidResponseCodeException http(int code) {
        return new HttpDataSource.InvalidResponseCodeException(code, "private-token-http://nas",
                Collections.emptyMap(), null);
    }

    @Test public void missingVideoDoesNotStartAnotherPlayer() {
        VideoPlaybackFailure result = VideoPlaybackFailure.classify(new IOException(http(404)));
        assertFalse(result.shouldFallback());
        assertTrue(result.getUserMessage().contains("404"));
        assertTrue(result.getUserMessage().contains("刷新列表"));
        assertFalse(result.getUserMessage().contains("private-token"));
        assertFalse(result.getUserMessage().contains("http://"));
    }

    @Test public void allHttpStatusFailuresOverrideRendererAndParser() {
        for (int status : new int[]{400, 401, 403, 404, 410, 429, 500, 502, 503}) {
            VideoPlaybackFailure result = VideoPlaybackFailure.classify(
                    new ParserException("secret", new IOException(http(status))), ExoPlaybackException.TYPE_RENDERER);
            assertFalse("HTTP " + status, result.shouldFallback());
            assertTrue(result.getUserMessage().contains("" + status));
        }
    }

    @Test public void authenticationAndServerMessagesAreActionable() {
        assertTrue(VideoPlaybackFailure.classify(http(401)).getUserMessage().contains("重新登录"));
        assertTrue(VideoPlaybackFailure.classify(http(403)).getUserMessage().contains("权限"));
        assertTrue(VideoPlaybackFailure.classify(http(503)).getUserMessage().contains("稍后重试"));
    }

    @Test public void networkCausesOverrideRendererCompatibility() {
        Throwable[] causes = {new SocketTimeoutException("secret"), new ConnectException("secret"),
                new UnknownHostException("private-host"), new SSLHandshakeException("private-cert"),
                new HttpDataSource.HttpDataSourceException(new IOException("secret"), null,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN)};
        for (Throwable cause : causes) {
            VideoPlaybackFailure result = VideoPlaybackFailure.classify(new RuntimeException(cause),
                    ExoPlaybackException.TYPE_RENDERER);
            assertFalse(result.shouldFallback());
            assertFalse(result.getUserMessage().contains("secret"));
            assertFalse(result.getUserMessage().contains("private"));
        }
    }

    @Test public void actualParserRendererAndAudioInitializationCanFallback() {
        assertTrue(VideoPlaybackFailure.classify(new ParserException("bad format")).shouldFallback());
        assertTrue(VideoPlaybackFailure.classify(new Exception("decoder"),
                ExoPlaybackException.TYPE_RENDERER).shouldFallback());
        assertTrue(VideoPlaybackFailure.classify(new AudioSink.InitializationException(
                0, 48000, 2, 4096, new Format.Builder().build(), false, null)).shouldFallback());
    }

    @Test public void genericIoUnknownRuntimeAndMemoryErrorsAreNotCompatibility() {
        assertFalse(VideoPlaybackFailure.classify(new IOException("disk read")).shouldFallback());
        assertFalse(VideoPlaybackFailure.classify(new IllegalStateException("unknown init failure")).shouldFallback());
        assertFalse(VideoPlaybackFailure.classify(new OutOfMemoryError(), ExoPlaybackException.TYPE_RENDERER).shouldFallback());
        assertFalse(VideoPlaybackFailure.classify(null).shouldFallback());
        assertFalse(VideoPlaybackFailure.classify(new AssertionError("bug")).shouldFallback());
        assertTrue(VideoPlaybackFailure.classify(new UnsatisfiedLinkError("missing decoder library")).shouldFallback());
        assertFalse(VideoPlaybackFailure.classify(new ExceptionInInitializerError(new OutOfMemoryError())).shouldFallback());
    }

    @Test public void nestedTransportInsideParserAndInvalidContentTypeAreNotFormats() {
        assertFalse(VideoPlaybackFailure.classify(new ParserException("wrapped", new SocketTimeoutException())).shouldFallback());
        assertFalse(VideoPlaybackFailure.classify(new HttpDataSource.InvalidContentTypeException("text/html", null)).shouldFallback());
    }

    @Test public void cyclicCausesTerminateSafely() {
        Throwable first = new Exception();
        Throwable second = new Exception(first);
        first.initCause(second);
        assertFalse(VideoPlaybackFailure.classify(first).shouldFallback());
    }
}
