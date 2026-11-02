package com.fnphoto.tv;

/** Android-free session intent; preferences never restore an active playback session. */
final class SlideshowState {
    static final int[] INTERVALS = {3, 5, 10, 15, 30};
    static final int MAX_SKIPS = 175;
    private int seconds = 5;
    private boolean loop;
    private boolean videos = true;
    private boolean active;
    private boolean paused;
    private boolean advancePending;
    private int skipped;
    private boolean restartedWithoutMedia;
    int seconds() { return seconds; }
    void setSeconds(int value) {
        seconds = 5;
        for (int allowed : INTERVALS) if (allowed == value) seconds = value;
    }
    boolean loop() { return loop; }
    void setLoop(boolean value) { loop = value; }
    boolean videos() { return videos; }
    void setVideos(boolean value) { videos = value; }
    boolean active() { return active; }
    boolean running() { return active && !paused; }
    boolean paused() { return active && paused; }
    void start() { advancePending = false; active = true; paused = false; skipped = 0; restartedWithoutMedia = false; }
    void stop() { advancePending = false; active = false; paused = false; skipped = 0; }
    void pause() { if (active) paused = true; }
    void resume() { if (active) { paused = false; skipped = 0; } }
    // Completion may arrive after Pause; preserve its next-item intent until media/session changes.
    void completed() { if (active) advancePending = true; }
    boolean advancePending() { return advancePending; }
    void mediaChanged() { advancePending = false; }
    boolean accepts(MediaItem item) { return videos || !"video".equals(item.getType()); }
    void displayed() { skipped = 0; restartedWithoutMedia = false; }
    boolean skip() { return ++skipped <= MAX_SKIPS; }
    /** One empty suffix may restart; an entire empty round must not loop forever. */
    boolean beginLoop() {
        if (!running() || !loop || restartedWithoutMedia) return false;
        restartedWithoutMedia = true;
        return true;
    }
}
