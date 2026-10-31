package com.fnphoto.tv;

/** Main-thread ownership of at most one current handoff and one photo ahead. */
final class DetailPreloadScope {
    private Entry current;
    private Entry next;

    private static final class Entry {
        final String url;
        final Runnable cancel;
        Entry(String url, Runnable cancel) {
            this.url = url;
            this.cancel = cancel;
        }
        boolean matches(String value) { return value != null && value.equals(url); }
    }

    /** Keep the selected photo alive while its new, potentially unmeasured view attaches. */
    void select(String url) {
        Entry oldCurrent = current;
        Entry oldNext = next;
        current = oldCurrent != null && oldCurrent.matches(url) ? oldCurrent
                : oldNext != null && oldNext.matches(url) ? oldNext : null;
        next = null;
        if (oldCurrent != null && oldCurrent != current) oldCurrent.cancel.run();
        if (oldNext != null && oldNext != current) oldNext.cancel.run();
    }

    boolean contains(String url) {
        return current != null && current.matches(url) || next != null && next.matches(url);
    }

    void setNext(String url, Runnable cancel) {
        if (url == null || url.isEmpty() || contains(url)) {
            cancel.run();
            return;
        }
        Entry oldNext = next;
        next = new Entry(url, cancel);
        if (oldNext != null) oldNext.cancel.run();
    }

    /** A preview is not completion: retain the handoff until the full request settles. */
    void complete(String url) {
        if (current != null && current.matches(url)) {
            Entry oldCurrent = current;
            current = null;
            oldCurrent.cancel.run();
        }
    }

    void clear() { select(null); }
}
