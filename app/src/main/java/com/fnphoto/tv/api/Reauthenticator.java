package com.fnphoto.tv.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        String key = RememberedNasTarget.source(prefs.getString("saved_source", ""), prefs.getString("saved_url", "")) + "\n" + prefs.getString("saved_user", "")
                + "\n" + prefs.getString("login_session", "") + "\n" + (failedToken == null ? "" : failedToken);
        return REFRESH_GATE.run(key, () -> {
            String latest = prefs.getString("api_token", "");
            return (!latest.isEmpty() && !latest.equals(failedToken)) || performLogin(context);
        });
    }

    private static boolean performLogin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String source = RememberedNasTarget.source(prefs.getString("saved_source", ""), prefs.getString("saved_url", ""));
        String user = prefs.getString("saved_user", "");
        String pass = prefs.getString("saved_pass", "");
        String session = prefs.getString("login_session", "");

        if (source.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Log.e(TAG, "No saved credentials for re-login");
            return false;
        }

        final String url;
        try {
            url = RememberedNasTarget.resolve(source, Reauthenticator::discoverEndpoint);
        } catch (java.io.IOException e) {
            Log.w(TAG, "NAS rediscovery failed", e);
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
            if (!latch.await(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { wsClient.close(); return false; }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Re-login interrupted", e);
            wsClient.close();
            return false;
        }

        if (success[0] && resultJson[0] != null) {
            try {
                JSONObject json = resultJson[0];
                String newToken = json.optString("token", "");
                String newSecret = json.optString("secret", "");
                String newBackId = json.optString("backId", "");

                if (!newToken.isEmpty() && !newSecret.isEmpty()
                        && session.equals(prefs.getString("login_session", ""))
                        && user.equals(prefs.getString("saved_user", ""))) {
                    prefs.edit()
                        .putString("nas_url", url)
                        .putString("saved_url", url)
                        .putString("api_token", newToken)
                        .putString("secret", newSecret)
                        .putString("backId", newBackId)
                        .apply();

                    FnProtocolUtils.setBackId(newBackId);
                    FnWebSocketClient.retainAuthenticatedSession(wsClient);
                    Log.i(TAG, "Re-login successful");
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse re-login result", e);
            }
        }

        wsClient.close();
        return false;
    }

    private static String discoverEndpoint(String fnId) throws java.io.IOException {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> endpoint = new AtomicReference<>();
        new FnConnectApi().fetchNasList(fnId, new FnConnectApi.NasListCallback() {
            @Override public void onSuccess(FnConnectApi.NasListResponse response) {
                try { endpoint.set(FnConnectApi.findReachableAddrSync(response.addresses)); }
                finally { ready.countDown(); }
            }
            @Override public void onError(String error) { ready.countDown(); }
        });
        try {
            if (!ready.await(45, TimeUnit.SECONDS)) throw new java.io.IOException("FN ID discovery timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("FN ID discovery interrupted", e);
        }
        return endpoint.get();
    }
}
