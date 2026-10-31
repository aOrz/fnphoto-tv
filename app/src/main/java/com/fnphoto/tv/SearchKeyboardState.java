package com.fnphoto.tv;

import java.util.HashMap;
import java.util.Map;

/** Explicit keyboard editing owns remote keys until dismissed; browsing does not use the IME. */
final class SearchKeyboardState {
    private boolean requested;
    private boolean visible;
    private int requestGeneration;
    private final Map<Long, KeyOwner> keys = new HashMap<>();
    private final Map<Long, Long> keyboardOpenKeys = new HashMap<>();

    enum OpenKey { PASS, CONSUME, OPEN }

    int request() { requested = true; return ++requestGeneration; }
    void dismiss() { requested = false; requestGeneration++; }

    boolean rejectRequest(int generation) {
        if (generation != requestGeneration) return false;
        dismiss();
        return true;
    }

    void observeVisibility(boolean nowVisible) {
        if (visible && !nowVisible) dismiss();
        visible = nowVisible;
    }

    boolean routesRemoteKeys() { return !requested && !visible; }
    boolean shouldHideInitialKeyboard() { return !requested; }

    /** Lock both halves (including repeats/canceled up) to the owner of the first down. */
    boolean routeKey(int deviceId, int keyCode, long downTime, boolean down, boolean up) {
        long key = ((long) deviceId << 32) | (keyCode & 0xffffffffL);
        KeyOwner owner = keys.get(key);
        if (down) {
            if (owner == null || owner.downTime != downTime) {
                owner = new KeyOwner(downTime, routesRemoteKeys());
                keys.put(key, owner);
            }
            return owner.browse;
        }
        if (up && owner != null && owner.downTime == downTime) {
            keys.remove(key);
            return owner.browse;
        }
        return false; // Never intercept an orphan up from the IME/another window.
    }

    /** Keep both halves of the editor opening gesture together; open only on its valid UP. */
    OpenKey handleKeyboardOpenKey(int deviceId, int keyCode, long downTime,
            boolean down, boolean up, boolean canceled) {
        long key = ((long) deviceId << 32) | (keyCode & 0xffffffffL);
        Long held = keyboardOpenKeys.get(key);
        boolean matches = held != null && held == downTime;
        if (down && !matches) keyboardOpenKeys.remove(key);
        if (down && (matches || routesRemoteKeys())) {
            keyboardOpenKeys.put(key, downTime);
            return OpenKey.CONSUME;
        }
        if (up && matches) {
            keyboardOpenKeys.remove(key);
            return canceled ? OpenKey.CONSUME : OpenKey.OPEN;
        }
        // An orphan browse UP must not activate TextView's default keyboard handling either.
        return up && routesRemoteKeys() ? OpenKey.CONSUME : OpenKey.PASS;
    }

    void clearKeys() { keys.clear(); keyboardOpenKeys.clear(); }

    private static final class KeyOwner {
        final long downTime;
        final boolean browse;
        KeyOwner(long downTime, boolean browse) {
            this.downTime = downTime;
            this.browse = browse;
        }
    }
}
