package com.fnphoto.tv.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy.LoadErrorInfo;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class VideoLoadErrorPolicyTest {
    private LoadErrorInfo errorInfo(int dataType, IOException error, int errorCount) {
        // The policy does not inspect Android URI/DataSpec fields. Keep real load metadata
        // so tests exercise the LoadErrorInfo callback used by ExoPlayer's loader.
        LoadEventInfo event = new LoadEventInfo(1, null, null, Collections.emptyMap(), 0, 123, 0);
        return new LoadErrorInfo(event, new MediaLoadData(dataType), error, errorCount);
    }

    @Test public void httpResponsesNeverCauseRepeatedAutomaticRequests() {
        LoadErrorHandlingPolicy policy = new VideoLoadErrorPolicy();
        for (int status : new int[]{400, 401, 403, 404, 410, 416, 429, 500, 502, 503}) {
            IOException http = new HttpDataSource.InvalidResponseCodeException(status, Collections.emptyMap(), null);
            assertEquals("HTTP " + status, C.TIME_UNSET,
                    policy.getRetryDelayMsFor(errorInfo(C.DATA_TYPE_MEDIA, http, 1)));
            assertEquals("Wrapped HTTP " + status, C.TIME_UNSET,
                    policy.getRetryDelayMsFor(errorInfo(C.DATA_TYPE_MEDIA, new IOException(http), 4)));
        }
    }

    @Test public void nonHttpErrorsKeepTheExactDefaultPolicy() {
        LoadErrorHandlingPolicy policy = new VideoLoadErrorPolicy();
        DefaultLoadErrorHandlingPolicy defaults = new DefaultLoadErrorHandlingPolicy();
        for (int dataType : new int[]{C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE}) {
            for (IOException error : new IOException[]{new SocketTimeoutException(), new IOException(),
                    new ParserException(), new FileNotFoundException()}) {
                for (int count = 1; count <= 8; count++) {
                    LoadErrorInfo info = errorInfo(dataType, error, count);
                    assertEquals(defaults.getRetryDelayMsFor(info), policy.getRetryDelayMsFor(info));
                }
            }
            assertEquals(defaults.getMinimumLoadableRetryCount(dataType),
                    policy.getMinimumLoadableRetryCount(dataType));
        }
    }

    @Test public void transientNetworkErrorsRetainBoundedBackoffAndFormatErrorsFailImmediately() {
        LoadErrorHandlingPolicy policy = new VideoLoadErrorPolicy();
        IOException timeout = new SocketTimeoutException();
        assertEquals(0, policy.getRetryDelayMsFor(errorInfo(C.DATA_TYPE_MEDIA, timeout, 1)));
        assertEquals(1000, policy.getRetryDelayMsFor(errorInfo(C.DATA_TYPE_MEDIA, timeout, 2)));
        assertEquals(5000, policy.getRetryDelayMsFor(errorInfo(C.DATA_TYPE_MEDIA, timeout, 8)));
        assertEquals(C.TIME_UNSET,
                policy.getRetryDelayMsFor(errorInfo(C.DATA_TYPE_MEDIA, new ParserException(), 1)));
    }

    @Test(timeout = 1000) public void cyclicCausesDoNotHangThePlaybackThread() {
        IOException first = new IOException();
        IOException second = new IOException(first);
        first.initCause(second);
        LoadErrorInfo info = errorInfo(C.DATA_TYPE_MEDIA, first, 2);
        assertEquals(new DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(info),
                new VideoLoadErrorPolicy().getRetryDelayMsFor(info));
    }
}
