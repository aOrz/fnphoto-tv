package com.fnphoto.tv.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class ApiInterceptor implements Interceptor {
    private static final String TAG = "ApiInterceptor";
    private final Context context;
    private final NasEndpointRouter router = new NasEndpointRouter();

    public ApiInterceptor(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String endpoint = prefs.getString("nas_url", "");
        String source = RememberedNasTarget.source(prefs.getString("saved_source", ""), prefs.getString("saved_url", ""));
        String account = accountKey(prefs);
        Request request = chain.request();
        if (request.header("accesstoken") != null) {
            okhttp3.HttpUrl routed = router.route(account, endpoint, request.url());
            if (!routed.equals(request.url())) {
                request = RequestAuthentication.sign(request.newBuilder().url(routed).build(), prefs.getString("api_token", ""));
            }
        }
        final boolean nasRequest = request.header("accesstoken") != null
                && request.url().encodedPath().startsWith("/p/api/")
                && NasEndpointRouter.sameOrigin(request.url(), endpoint);
        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException failure) {
            // A relocated FN ID may never return 401. Only retry read-only requests;
            // an interrupted mutation may already have been applied by the server.
            if (chain.call().isCanceled() || !nasRequest || !"GET".equals(request.method()) || !RememberedNasTarget.requiresDiscovery(source)
                    || !Reauthenticator.reLoginSync(context, request.header("accesstoken"))) throw failure;
            if (!account.equals(accountKey(prefs))) throw failure;
            router.remember(account, endpoint, prefs.getString("nas_url", endpoint));
            Request retry = request.newBuilder().url(router.route(account, prefs.getString("nas_url", endpoint), request.url())).build();
            return chain.proceed(RequestAuthentication.sign(retry, prefs.getString("api_token", "")));
        }

        if (nasRequest && response.code() == 401) {
            String url = request.url().toString();
            Log.w(TAG, "Received 401 for: " + url);
            boolean reloginSuccess = Reauthenticator.reLoginSync(context, request.header("accesstoken"));
            if (reloginSuccess) {
                String newToken = prefs.getString("api_token", "");

                if (!newToken.isEmpty() && account.equals(accountKey(prefs))) {
                    String path = request.url().encodedPath();
                    router.remember(account, endpoint, prefs.getString("nas_url", endpoint));
                    Request routed = request.newBuilder().url(router.route(account, prefs.getString("nas_url", endpoint), request.url())).build();
                    final Request newRequest;
                    try { newRequest = RequestAuthentication.sign(routed, newToken); }
                    catch (IOException unsupportedBody) { return response; }

                    Log.i(TAG, "Retrying request with new token: " + path);
                    // Preserve the readable original 401 body when refresh fails.
                    response.close();
                    return chain.proceed(newRequest);
                }
            } else {
                Log.e(TAG, "Re-login failed, cannot recover from 401");
            }
        }

        return response;
    }

    private String accountKey(SharedPreferences prefs) {
        String source = RememberedNasTarget.source(prefs.getString("saved_source", ""), prefs.getString("nas_url", ""));
        return source + "\n" + prefs.getString("saved_user", "") + "\n" + prefs.getString("login_session", "");
    }
}
