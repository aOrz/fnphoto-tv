package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Navigation generation and cancellation bookkeeping, independent of Android for regression tests. */
final class MainRequestScope {
    private final Map<Object, Runnable> cancellations = new IdentityHashMap<>();
    private long generation;
    private boolean closed;

    long generation() { return generation; }
    boolean isCurrent(long expected) { return !closed && expected == generation; }

    void track(Object request, Runnable cancel) {
        if (closed) cancel.run();
        else cancellations.put(request, cancel);
    }

    boolean complete(Object request, long expected) {
        boolean tracked = cancellations.remove(request) != null;
        return tracked && isCurrent(expected);
    }

    void next() {
        generation++;
        ArrayList<Runnable> pending = new ArrayList<>(cancellations.values());
        cancellations.clear();
        // Invalidate first: cancellation itself can deliver a callback.
        for (Runnable cancel : pending) cancel.run();
    }

    void close() { closed = true; next(); }
}
