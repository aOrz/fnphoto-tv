package com.fnphoto.tv.cache;

import java.util.ArrayList;
import java.util.List;

/** UI-thread scope: invalidate callbacks before cancelling requests on rebind/recycle. */
public final class ImageRequestScope {
    private final List<Runnable> cancellations = new ArrayList<>();
    private long generation;
    public long generation() { return generation; }
    public boolean isCurrent(long expected) { return generation == expected; }
    public void add(Runnable cancel) { cancellations.add(cancel); }
    public void clear() {
        generation++;
        List<Runnable> old = new ArrayList<>(cancellations);
        cancellations.clear();
        for (Runnable cancel : old) cancel.run();
    }
}
