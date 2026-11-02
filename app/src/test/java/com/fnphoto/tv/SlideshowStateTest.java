package com.fnphoto.tv;
import org.junit.Test;
import static org.junit.Assert.*;
public class SlideshowStateTest {
    @Test public void defaultsAndInvalidPreferenceAreSafe() {
        SlideshowState s = new SlideshowState();
        assertEquals(5, s.seconds()); assertFalse(s.loop()); assertTrue(s.videos());
        for (int seconds : SlideshowState.INTERVALS) { s.setSeconds(seconds); assertEquals(seconds, s.seconds()); }
        s.setSeconds(-100); assertEquals(5, s.seconds()); assertFalse(s.active());
    }
    @Test public void pauseAndResumeRetainSessionNotTimerIntent() {
        SlideshowState s = new SlideshowState(); s.start(); assertTrue(s.running());
        s.pause(); s.pause(); assertTrue(s.active()); assertFalse(s.running());
        s.resume(); assertTrue(s.running()); s.stop(); s.resume(); assertFalse(s.active());
    }
    @Test public void noMatchingMediaCannotLoopForever() {
        SlideshowState s = new SlideshowState(); s.setLoop(true); s.start();
        assertTrue(s.beginLoop()); assertFalse(s.beginLoop());
        s.displayed(); assertTrue(s.beginLoop()); s.pause(); assertFalse(s.beginLoop());
    }
    @Test public void skipHasFiniteBudgetAndExplicitResumeRenewsIt() {
        SlideshowState s = new SlideshowState(); s.start();
        for (int i=0; i<SlideshowState.MAX_SKIPS; i++) assertTrue(s.skip());
        assertFalse(s.skip()); s.pause(); s.resume(); assertTrue(s.skip());
    }
    @Test public void disablingVideoDoesNotExcludePhoto() {
        SlideshowState s = new SlideshowState(); s.setVideos(false);
        assertFalse(s.accepts(new MediaItem("1", "v", "video", "t", "v")));
        assertTrue(s.accepts(new MediaItem("2", "p", "photo", "t", "p")));
    }
    @Test public void completedVideoKeepsAdvanceIntentAcrossRepeatedPauseAndResume() {
        SlideshowState s = new SlideshowState(); s.start(); s.completed();
        s.pause(); s.pause(); s.resume(); assertTrue(s.advancePending());
        s.mediaChanged(); assertFalse(s.advancePending());
    }
    @Test public void lateCompletionAfterPauseStillAdvancesButNotAfterStop() {
        SlideshowState s = new SlideshowState(); s.start(); s.pause(); s.completed();
        assertTrue(s.advancePending()); s.resume(); assertTrue(s.advancePending());
        s.stop(); s.completed(); assertFalse(s.advancePending());
        s.start(); s.completed(); s.start(); assertFalse(s.advancePending());
    }
}
