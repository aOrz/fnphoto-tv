package com.fnphoto.tv;

/** A key press during asynchronous prepare records intent instead of calling an illegal player state. */
final class DetailPlaybackState {
    private boolean prepared;
    private boolean playWhenReady = true;
    void reset() { prepared = false; playWhenReady = true; }
    void prepared() { prepared = true; }
    void toggle() { playWhenReady = !playWhenReady; }
    void setPlayWhenReady(boolean play) { playWhenReady = play; }
    boolean isPlayRequested() { return playWhenReady; }
    boolean isPrepared() { return prepared; }
    boolean shouldPlay() { return prepared && playWhenReady; }
}
