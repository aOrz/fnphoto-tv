package com.fnphoto.tv.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Reauthenticator {
    private static final String TAG = "Reauthenticator";
    private static final long LOGIN_TIMEOUT_SECONDS = 30;
    private static final TokenRefreshGate REFRESH_GATE = new TokenRefreshGate();

    public static boolean reLoginSync(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        return reLoginSync(context, prefs.getString("api_token", ""));
    }

    public static boolean reLoginSync(Context context, String failedToken) {
        SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String current = prefs.getString("api_token", "");
        if (!current.isEmpty() && !current.equals(failedToken)) return true;
        String key = prefs.getString("saved_url", "") + "\n" + prefs.getString("saved_user", "")
                + "\n" + (failedToken == null ? "" : failedToken);
        return REFRESH_GATE.run(key, () -> {
            String latest = prefs.getString("api_token", "");
            return (!latest.isEmpty() && !latest.equals(failedToken)) || performLogin(context);
        });
    }

    private static boolean performLogin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String url = prefs.getString("saved_url", "");
        String user = prefs.getString("saved_user", "");
        String pass = prefs.getString("saved_pass", "");

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Log.e(TAG, "No saved credentials for re-login");
            return false;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        final JSONObject[] resultJson = {null};

        FnWebSocketClient wsClient = new FnWebSocketClient();
        wsClient.startLogin(url, user, pass, new FnWebSocketClient.LoginCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                resultJson[0] = response;
                success[0] = true;
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Re-login error: " + error);
                latch.countDown();
            }
        });

        try {
            latch.await(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Re-login interrupted", e);
            return false;
        }

        if (success[0] && resultJson[0] != null) {
            try {
                JSONObject json = resultJson[0];
                String newToken = json.optString("token", "");
                String newSecret = json.optString("secret", "");
                String newBackId = json.optString("backId", "");

                if (!newToken.isEmpty() && !newSecret.isEmpty()) {
                    prefs.edit()
                        .putString("api_token", newToken)
                        .putString("secret", newSecret)
                        .putString("backId", newBackId)
                        .apply();

                    FnProtocolUtils.setBackId(newBackId);
                    Log.i(TAG, "Re-login successful");
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse re-login result", e);
            }
        }

        return false;
    }
}
