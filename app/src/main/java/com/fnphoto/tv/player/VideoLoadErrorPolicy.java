package com.fnphoto.tv.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Fail HTTP responses promptly; the visible retry action owns another request. */
public final class VideoLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {
    @Override public long getRetryDelayMsFor(int dataType, long loadDurationMs,
            IOException exception, int errorCount) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) return C.TIME_UNSET;
        }
        return super.getRetryDelayMsFor(dataType, loadDurationMs, exception, errorCount);
    }
}
