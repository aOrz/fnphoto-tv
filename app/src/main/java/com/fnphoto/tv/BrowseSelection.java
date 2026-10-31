package com.fnphoto.tv;

import java.net.URI;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Locale;

/** Account-scoped preference policy. Failed/obsolete requests never replace a working choice. */
final class BrowseSelection {
    static final String ACCOUNT_KEY = "browse_account_v1";
    static final String MODE_PREFIX = "browse_mode_v1_";

    interface Store {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
    }

    private final Store store;
    private final String account;
    private BrowseMode requested;
    private boolean restoring;
    private boolean remember = true;
    private boolean closed;

    BrowseSelection(Store store, String account) {
        this.store = store;
        this.account = account == null ? "" : account;
    }

    BrowseMode initial() {
        String stored = account.isEmpty() ? null : store.get(MODE_PREFIX + account);
        BrowseMode mode = BrowseMode.fromId(stored);
        if (mode == null) {
            if (stored != null && !stored.isEmpty()) store.remove(MODE_PREFIX + account);
            mode = BrowseMode.GALLERY;
        }
        requested = mode;
        restoring = true;
        remember = true;
        return mode;
    }

    boolean select(String id) {
        BrowseMode mode = BrowseMode.fromId(id);
        if (closed || mode == null) return false;
        requested = mode;
        restoring = false;
        remember = true;
        return true;
    }

    void succeeded(BrowseMode mode, String currentAccount) {
        if (closed || mode != requested || account.isEmpty() || !account.equals(currentAccount)) return;
        restoring = false;
        if (remember) store.put(MODE_PREFIX + account, mode.id);
    }

    /** A startup failure falls back once, without overwriting the saved choice on network errors. */
    BrowseMode failed(BrowseMode mode) {
        if (closed || mode != requested || !restoring) return null;
        restoring = false;
        if (mode == BrowseMode.GALLERY) return null;
        remember = false;
        requested = BrowseMode.GALLERY;
        return requested;
    }

    void logout() {
        closed = true; // A late response cannot restore the preference after logout.
        if (!account.isEmpty()) store.remove(MODE_PREFIX + account);
    }

    /** Stable across token refresh and FN ID endpoint rediscovery; contains no password/token. */
    static String accountId(String source, String username) {
        if (source == null || source.trim().isEmpty() || username == null || username.trim().isEmpty()) return "";
        String target = source.trim();
        try {
            // FN IDs are retained as entered instead of using their changing transport endpoint.
            if (target.contains("://") || target.contains(".") || target.contains(":")) {
                if (!target.contains("://")) target = "http://" + target;
                URI uri = new URI(target);
                String scheme = uri.getScheme().toLowerCase(Locale.US);
                if ("ws".equals(scheme)) scheme = "http";
                if ("wss".equals(scheme)) scheme = "https";
                if (uri.getHost() != null) {
                    int port = uri.getPort();
                    if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
                    String path = uri.getPath() == null ? "" : uri.getPath();
                    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                    target = new URI(scheme, null, uri.getHost().toLowerCase(Locale.US), port, path, null, null).toString();
                }
            }
            String user = username.trim();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (target.length() + ":" + target + user.length() + ":" + user).getBytes(Charset.forName("UTF-8")));
            StringBuilder result = new StringBuilder();
            for (byte value : hash) result.append(String.format(Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return ""; // Missing/invalid identity must never become a shared preference bucket.
        }
    }
}
