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

    public ApiInterceptor(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        if (response.code() == 401) {
            String url = request.url().toString();
            Log.w(TAG, "Received 401 for: " + url);
            boolean reloginSuccess = Reauthenticator.reLoginSync(context, request.header("accesstoken"));
            if (reloginSuccess) {
                SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
                String newToken = prefs.getString("api_token", "");

                if (!newToken.isEmpty()) {
                    String path = request.url().encodedPath();
                    String method = request.method();
                    String query = request.url().encodedQuery();
                    String newAuthx = FnAuthUtils.generateAuthX(path, method, query);

                    Request newRequest = request.newBuilder()
                        .header("accesstoken", newToken)
                        .header("authx", newAuthx != null ? newAuthx : "")
                        .build();

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
}
