package com.fnphoto.tv;

/** Keeps relative seeks cumulative while the platform player completes asynchronous seeks. */
final class VideoSeekState {
    private boolean inFlight;
    private long sentTarget;
    private long latestTarget;

    /** A non-positive duration is unknown; otherwise normalize and clamp without overflowing. */
    static long target(long position, long duration, long delta) {
        if (duration <= 0) return -1;
        long current = Math.max(0, Math.min(position, duration));
        if (delta > 0 && delta >= duration - current) return duration;
        if (delta < 0 && delta <= -current) return 0;
        return current + delta;
    }

    /** Returns a target to send now, or null when queued behind the outstanding seek. */
    Long request(long position, long duration, long delta) {
        if (duration <= 0) return null;
        return enqueue(target(inFlight ? latestTarget : position, duration, delta));
    }

    Long requestAbsolute(long duration, long target) {
        if (duration <= 0) return null;
        return enqueue(VideoSeekState.target(target, duration, 0));
    }

    private Long enqueue(long target) {
        latestTarget = target;
        if (inFlight) return null;
        inFlight = true;
        sentTarget = target;
        return target;
    }

    /** Call for the one outstanding seek; send a returned target before awaiting another callback. */
    Long completed() {
        if (!inFlight) return null;
        if (latestTarget == sentTarget) {
            reset();
            return null;
        }
        sentTarget = latestTarget;
        return sentTarget;
    }

    void reset() {
        inFlight = false;
        sentTarget = 0;
        latestTarget = 0;
    }

    long displayedPosition(long actual) {
        return inFlight ? latestTarget : actual;
    }
}
