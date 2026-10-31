package com.fnphoto.tv.player;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLException;

/** ExoPlayer failure policy. Switching decoders cannot repair a failed HTTP request. */
public final class VideoPlaybackFailure {
    private final boolean fallback;
    private final String userMessage;

    private VideoPlaybackFailure(boolean fallback, String userMessage) {
        this.fallback = fallback;
        this.userMessage = userMessage;
    }

    public boolean shouldFallback() { return fallback; }

    /** Safe for a persistent error panel: contains no URL, server body, or exception message. */
    public String getUserMessage() { return userMessage; }

    public static VideoPlaybackFailure classify(Throwable failure) {
        return classify(failure, -1);
    }

    /** Explicit type also lets JVM tests exercise renderer policy without Android's SystemClock. */
    public static VideoPlaybackFailure classify(Throwable failure, int exoType) {
        boolean renderer = exoType == ExoPlaybackException.TYPE_RENDERER;
        boolean outOfMemory = false;
        boolean parser = false;
        boolean initialization = false;
        boolean timeout = false;
        boolean tls = false;
        boolean network = false;
        boolean io = false;
        boolean contentType = false;
        Integer httpCode = null;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ExoPlaybackException) {
                int type = ((ExoPlaybackException) cause).type;
                renderer |= type == ExoPlaybackException.TYPE_RENDERER;
            }
            if (cause instanceof HttpDataSource.InvalidResponseCodeException && httpCode == null)
                httpCode = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            contentType |= cause instanceof HttpDataSource.InvalidContentTypeException;
            timeout |= cause instanceof InterruptedIOException;
            tls |= cause instanceof SSLException;
            network |= cause instanceof SocketException || cause instanceof UnknownHostException
                    || cause instanceof HttpDataSource.HttpDataSourceException;
            outOfMemory |= cause instanceof OutOfMemoryError;
            parser |= cause instanceof ParserException;
            initialization |= cause instanceof MediaCodecRenderer.DecoderInitializationException
                    || cause instanceof AudioSink.InitializationException
                    || cause instanceof LinkageError;
            // ParserException itself extends IOException; it is not a transport failure.
            io |= cause instanceof IOException && !(cause instanceof ParserException);
        }
        // Inspect the entire chain before choosing compatibility fallback. A renderer wrapper
        // may still contain an HTTP, timeout, or TLS root cause.
        if (httpCode != null) return http(httpCode);
        if (tls) return failure("安全连接失败，请检查 NAS 证书后重试");
        if (timeout) return failure("视频连接超时或已中断，请检查网络后重试");
        if (contentType) return failure("服务器未返回有效视频内容，请检查 NAS 服务后重试");
        if (network) return failure("无法连接视频服务，请检查网络和 NAS 后重试");
        if (outOfMemory) return failure("播放内存不足，请退出后重试");
        if (io) return failure("视频读取失败，请检查文件和网络后重试");
        if (renderer || parser || initialization)
            return new VideoPlaybackFailure(true, "当前解码器无法播放此视频，可尝试兼容播放器");
        return failure("视频播放失败，请重试或返回列表");
    }

    private static VideoPlaybackFailure http(int code) {
        if (code == 401) return failure("登录已过期（HTTP 401），请重新登录后播放");
        if (code == 403) return failure("无权访问此视频（HTTP 403），请检查账户权限");
        if (code == 404 || code == 410)
            return failure("视频不存在或已移除（HTTP " + code + "），请刷新列表后重试");
        if (code >= 500 && code <= 599)
            return failure("NAS 视频服务暂时不可用（HTTP " + code + "），请稍后重试");
        if (code >= 100 && code <= 599)
            return failure("视频请求失败（HTTP " + code + "），请重试或返回列表");
        return failure("视频请求失败，请重试或返回列表");
    }

    private static VideoPlaybackFailure failure(String message) {
        return new VideoPlaybackFailure(false, message);
    }
}
