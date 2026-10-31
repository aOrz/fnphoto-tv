package com.fnphoto.tv.player;

import android.content.Context;
import android.net.Uri;
import com.fnphoto.tv.api.HttpClientProvider;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Request;

/** Shared-album video only. HTTP streaming uses the same protected client as shared images. */
public final class SharedAlbumHttpDataSourceFactory implements DataSource.Factory {
    private final Context context;
    private final String userAgent;
    public SharedAlbumHttpDataSourceFactory(Context context, String userAgent) {
        this.context = context.getApplicationContext();
        this.userAgent = userAgent;
    }
    @Override public DataSource createDataSource() { return new Source(context, userAgent); }

    private static final class Source extends BaseDataSource implements HttpDataSource {
        private final Context context;
        private final String userAgent;
        private final SharedVideoStream stream;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private DataSpec spec;
        private boolean opened;
        Source(Context context, String userAgent) {
            super(true);
            this.context = context; this.userAgent = userAgent;
            stream = new SharedVideoStream(HttpClientProvider.getClient(context));
        }
        @Override public long open(DataSpec dataSpec) throws HttpDataSourceException {
            spec = dataSpec;
            transferInitializing(dataSpec);
            try {
                if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null)
                    throw new IOException("Shared media permits only GET");
                Request.Builder request = new Request.Builder().url(dataSpec.uri.toString()).get();
                for (Map.Entry<String, String> entry : properties.entrySet()) request.header(entry.getKey(), entry.getValue());
                for (Map.Entry<String, String> entry : dataSpec.httpRequestHeaders.entrySet()) request.header(entry.getKey(), entry.getValue());
                request.header("User-Agent", userAgent);
                // Read on every open, including ExoPlayer seeks/retries after token refresh.
                android.content.SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
                String token = prefs.getString("api_token", "");
                String endpoint = prefs.getString("nas_url", "");
                long length = stream.open(SharedVideoStream.authenticate(request.build(), endpoint, token), dataSpec.position, dataSpec.length);
                opened = true;
                transferStarted(dataSpec);
                return length;
            } catch (SharedVideoStream.StatusException error) {
                throw new InvalidResponseCodeException(error.code, error.message, error.headers, dataSpec);
            } catch (IOException error) {
                throw new HttpDataSourceException(error, dataSpec, HttpDataSourceException.TYPE_OPEN);
            } catch (IllegalArgumentException error) {
                throw new HttpDataSourceException(new IOException("Invalid shared media request", error), dataSpec, HttpDataSourceException.TYPE_OPEN);
            }
        }
        @Override public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
            try {
                int count = stream.read(buffer, offset, length);
                if (count > 0) bytesTransferred(count);
                return count;
            } catch (IOException error) {
                throw new HttpDataSourceException(error, spec, HttpDataSourceException.TYPE_READ);
            }
        }
        @Override public void close() {
            stream.close();
            if (opened) { opened = false; transferEnded(); }
        }
        @Override public Uri getUri() { return stream.uri() == null ? null : Uri.parse(stream.uri()); }
        @Override public int getResponseCode() { return stream.status(); }
        @Override public Map<String, List<String>> getResponseHeaders() { return stream.headers(); }
        @Override public void setRequestProperty(String name, String value) { properties.put(name, value); }
        @Override public void clearRequestProperty(String name) { properties.remove(name); }
        @Override public void clearAllRequestProperties() { properties.clear(); }
    }
}
