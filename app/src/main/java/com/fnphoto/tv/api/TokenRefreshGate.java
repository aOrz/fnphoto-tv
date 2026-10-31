package com.fnphoto.tv.api;

/** Collapses concurrent refreshes and briefly backs off failed attempts for the same session. */
final class TokenRefreshGate {
    interface Work { boolean run(); }
    interface Clock { long now(); }
    private final Clock clock;
    private boolean refreshing;
    private String completedKey;
    private boolean result;
    private long completedAt;

    TokenRefreshGate() { this(() -> System.nanoTime() / 1_000_000L); }
    TokenRefreshGate(Clock clock) { this.clock = clock; }

    boolean run(String key, Work work) {
        synchronized (this) {
            while (refreshing) {
                try { wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (key.equals(completedKey)) return result;
            }
            if (key.equals(completedKey) && clock.now() - completedAt < 1000) return result;
            refreshing = true;
        }
        boolean refreshed = false;
        try {
            refreshed = work.run();
            return refreshed;
        } finally {
            synchronized (this) {
                result = refreshed;
                completedKey = key;
                completedAt = clock.now();
                refreshing = false;
                notifyAll();
            }
        }
    }
}
