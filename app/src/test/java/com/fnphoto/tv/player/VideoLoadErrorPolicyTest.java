package com.fnphoto.tv.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class VideoLoadErrorPolicyTest {
    @Test public void httpResponsesNeverCauseRepeatedAutomaticRequests() {
        VideoLoadErrorPolicy policy = new VideoLoadErrorPolicy();
        for (int status : new int[]{400, 401, 403, 404, 410, 429, 500, 502, 503}) {
            IOException http = new HttpDataSource.InvalidResponseCodeException(status, Collections.emptyMap(), null);
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(1, 0, http, 1));
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(1, 0, new IOException(http), 4));
        }
    }

    @Test public void nonHttpErrorsKeepTheExactDefaultPolicy() {
        VideoLoadErrorPolicy policy = new VideoLoadErrorPolicy();
        DefaultLoadErrorHandlingPolicy defaults = new DefaultLoadErrorHandlingPolicy();
        for (IOException error : new IOException[]{new SocketTimeoutException(), new IOException(), new ParserException()}) {
            for (int count = 1; count <= 8; count++) {
                assertEquals(defaults.getRetryDelayMsFor(1, 123, error, count),
                        policy.getRetryDelayMsFor(1, 123, error, count));
            }
        }
        assertEquals(defaults.getMinimumLoadableRetryCount(1), policy.getMinimumLoadableRetryCount(1));
        assertEquals(defaults.getMinimumLoadableRetryCount(7), policy.getMinimumLoadableRetryCount(7));
    }
}
