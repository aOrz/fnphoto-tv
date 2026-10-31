package com.fnphoto.tv;

import org.junit.Test;

import static com.fnphoto.tv.VideoRemoteInput.Action.*;
import static com.fnphoto.tv.VideoRemoteInput.Key.*;
import static com.fnphoto.tv.VideoRemoteInput.Mode.*;
import static org.junit.Assert.assertEquals;

public class VideoRemoteInputTest {
    private final VideoRemoteInput input = new VideoRemoteInput();

    private VideoRemoteInput.Action down(VideoRemoteInput.Key key,
            VideoRemoteInput.Mode mode) {
        return input.onKey(key, true, 0, 1000, mode, 1);
    }

    @Test public void videoSurfaceHasOneMeaningForEachDirection() {
        assertEquals(SEEK_BACK, down(LEFT, VIDEO));
        assertEquals(SEEK_FORWARD, down(RIGHT, VIDEO));
        assertEquals(SHOW_CONTROLS, down(UP, VIDEO));
        assertEquals(SHOW_CONTROLS, down(DOWN, VIDEO));
        assertEquals(SHOW_CONTROLS, down(MENU, VIDEO));
        assertEquals(TOGGLE_PLAY, down(CENTER, VIDEO));
        assertEquals(EXIT, down(BACK, VIDEO));
    }

    @Test public void controlsKeepNativeButtonFocusAndActivation() {
        for (VideoRemoteInput.Key key : new VideoRemoteInput.Key[] { LEFT, RIGHT, UP, DOWN, CENTER }) {
            assertEquals(PASS, down(key, CONTROLS));
            assertEquals(PASS, input.onKey(key, true, 1, 1500, CONTROLS, 1));
            assertEquals(PASS, input.onKey(key, false, 0, 1600, CONTROLS, 1));
        }
        assertEquals(HIDE_CONTROLS, down(BACK, CONTROLS));
        assertEquals(HIDE_CONTROLS, down(MENU, CONTROLS));
    }

    @Test public void browseKeepsPhotoNavigationZoomInfoAndSlideshowKeys() {
        for (VideoRemoteInput.Key key : new VideoRemoteInput.Key[] {
                LEFT, RIGHT, UP, DOWN, CENTER, MENU, BACK, OTHER }) {
            assertEquals(PASS, down(key, BROWSE));
            assertEquals(PASS, input.onKey(key, false, 0, 1001, BROWSE, 1));
        }
    }

    @Test public void browseTransportSeparatesPlaybackAndPhotoSlideshowShortcut() {
        assertEquals(VideoRemoteInput.Action.PLAY, down(VideoRemoteInput.Key.PLAY, BROWSE));
        assertEquals(VideoRemoteInput.Action.PLAY, down(TOGGLE, BROWSE));
        assertEquals(CONSUME, down(VideoRemoteInput.Key.PAUSE, BROWSE));
        assertEquals(PHOTO_SLIDESHOW, down(FAST_FORWARD, BROWSE));
        assertEquals(CONSUME, input.onKey(FAST_FORWARD, true, 1, 1500, BROWSE, 1));
        assertEquals(CONSUME, input.onKey(FAST_FORWARD, false, 0, 1600, BROWSE, 1));
        assertEquals(CONSUME, down(REWIND, BROWSE));
        assertEquals(VideoRemoteInput.Action.NEXT, down(VideoRemoteInput.Key.NEXT, BROWSE));
        assertEquals(VideoRemoteInput.Action.PREVIOUS, down(VideoRemoteInput.Key.PREVIOUS, BROWSE));
    }

    @Test public void mediaKeysHaveExplicitSemanticsRegardlessOfControlFocus() {
        for (VideoRemoteInput.Mode mode : new VideoRemoteInput.Mode[] { VIDEO, CONTROLS }) {
            assertEquals(VideoRemoteInput.Action.PLAY, down(VideoRemoteInput.Key.PLAY, mode));
            assertEquals(VideoRemoteInput.Action.PAUSE, down(VideoRemoteInput.Key.PAUSE, mode));
            assertEquals(TOGGLE_PLAY, down(TOGGLE, mode));
            assertEquals(SEEK_FORWARD, down(FAST_FORWARD, mode));
            assertEquals(SEEK_BACK, down(REWIND, mode));
            assertEquals(VideoRemoteInput.Action.NEXT, down(VideoRemoteInput.Key.NEXT, mode));
            assertEquals(VideoRemoteInput.Action.PREVIOUS, down(VideoRemoteInput.Key.PREVIOUS, mode));
        }
    }

    @Test public void seekRepeatIsThrottledFromLastAcceptedStep() {
        assertEquals(SEEK_FORWARD, down(RIGHT, VIDEO));
        assertEquals(CONSUME, input.onKey(RIGHT, true, 1, 1249, VIDEO, 1));
        assertEquals(SEEK_FORWARD, input.onKey(RIGHT, true, 2, 1250, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(RIGHT, true, 3, 1499, VIDEO, 1));
        assertEquals(SEEK_FORWARD, input.onKey(RIGHT, true, 4, 1500, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(RIGHT, false, 0, 1510, VIDEO, 1));
    }

    @Test public void allSeekKeysRepeatWithoutChangingIntoMediaNavigation() {
        for (VideoRemoteInput.Key key : new VideoRemoteInput.Key[] { LEFT, RIGHT, REWIND, FAST_FORWARD }) {
            VideoRemoteInput.Action expected = key == LEFT || key == REWIND ? SEEK_BACK : SEEK_FORWARD;
            assertEquals(expected, down(key, VIDEO));
            assertEquals(expected, input.onKey(key, true, 5, 1500, VIDEO, 1));
            assertEquals(CONSUME, input.onKey(key, false, 0, 1600, VIDEO, 1));
        }
    }

    @Test public void discreteActionsNeverRepeatOrExecuteOnRelease() {
        for (VideoRemoteInput.Key key : new VideoRemoteInput.Key[] {
                CENTER, UP, DOWN, MENU, BACK, VideoRemoteInput.Key.PLAY,
                VideoRemoteInput.Key.PAUSE, TOGGLE, VideoRemoteInput.Key.NEXT,
                VideoRemoteInput.Key.PREVIOUS }) {
            down(key, VIDEO);
            assertEquals(CONSUME, input.onKey(key, true, 1, 1500, VIDEO, 1));
            assertEquals(CONSUME, input.onKey(key, false, 0, 1600, VIDEO, 1));
        }
    }

    @Test public void heldSeekCannotNavigateAfterChangingToPhoto() {
        assertEquals(SEEK_FORWARD, down(RIGHT, VIDEO));
        assertEquals(CONSUME, input.onKey(RIGHT, true, 1, 1500, BROWSE, 2));
        assertEquals(CONSUME, input.onKey(RIGHT, false, 0, 1600, BROWSE, 2));
        assertEquals(PASS, input.onKey(RIGHT, true, 0, 1700, BROWSE, 2));
    }

    @Test public void heldSeekCannotMoveControlsFocusOrResumeAfterModeChangesBack() {
        assertEquals(SEEK_BACK, down(LEFT, VIDEO));
        assertEquals(CONSUME, input.onKey(LEFT, true, 1, 1500, CONTROLS, 1));
        assertEquals(CONSUME, input.onKey(LEFT, true, 2, 2000, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(LEFT, false, 0, 2100, CONTROLS, 1));
    }

    @Test public void heldSeekCannotReachAnotherVideoGeneration() {
        assertEquals(SEEK_FORWARD, down(FAST_FORWARD, VIDEO));
        assertEquals(CONSUME, input.onKey(FAST_FORWARD, true, 1, 1500, VIDEO, 2));
        assertEquals(CONSUME, input.onKey(FAST_FORWARD, true, 2, 2000, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(FAST_FORWARD, false, 0, 2100, VIDEO, 2));
    }

    @Test public void openingAndClosingControlsConsumeTheSameKeysRelease() {
        assertEquals(SHOW_CONTROLS, down(DOWN, VIDEO));
        assertEquals(CONSUME, input.onKey(DOWN, false, 0, 1100, CONTROLS, 1));
        assertEquals(HIDE_CONTROLS, down(BACK, CONTROLS));
        assertEquals(CONSUME, input.onKey(BACK, true, 1, 1500, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(BACK, false, 0, 1600, VIDEO, 1));
        assertEquals(EXIT, input.onKey(BACK, true, 0, 1700, VIDEO, 1));
    }

    @Test public void initialPassKeepsEntireSequenceEvenAcrossModesAndMedia() {
        assertEquals(PASS, down(RIGHT, BROWSE));
        assertEquals(PASS, input.onKey(RIGHT, true, 1, 1500, VIDEO, 2));
        assertEquals(PASS, input.onKey(RIGHT, false, 0, 1600, VIDEO, 2));
        assertEquals(PASS, down(CENTER, CONTROLS));
        assertEquals(PASS, input.onKey(CENTER, false, 0, 1700, VIDEO, 3));
    }

    @Test public void rapidSeparatePressesEachSeekImmediatelyEvenWithoutPreviousRelease() {
        assertEquals(SEEK_BACK, down(LEFT, VIDEO));
        assertEquals(SEEK_BACK, input.onKey(LEFT, true, 0, 1050, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(LEFT, false, 0, 1060, VIDEO, 1));
        assertEquals(SEEK_BACK, input.onKey(LEFT, true, 0, 1070, VIDEO, 1));
    }

    @Test public void overlappingKeySequencesKeepTheirOwnOwnershipAndSeekClock() {
        assertEquals(SEEK_BACK, down(LEFT, VIDEO));
        assertEquals(SEEK_FORWARD, input.onKey(RIGHT, true, 0, 1100, VIDEO, 1));
        assertEquals(SEEK_BACK, input.onKey(LEFT, true, 1, 1250, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(RIGHT, true, 1, 1250, VIDEO, 1));
        assertEquals(SEEK_FORWARD, input.onKey(RIGHT, true, 2, 1350, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(LEFT, false, 0, 1400, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(RIGHT, false, 0, 1400, VIDEO, 1));
    }

    @Test public void orphanReleasesNeverExecuteActions() {
        assertEquals(CONSUME, input.onKey(RIGHT, false, 0, 1000, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(VideoRemoteInput.Key.NEXT, false, 0, 1000, BROWSE, 1));
        assertEquals(PASS, input.onKey(CENTER, false, 0, 1000, CONTROLS, 1));
    }

    @Test public void orphanRepeatsCannotStartAnOwnedAction() {
        assertEquals(CONSUME, input.onKey(RIGHT, true, 1, 1000, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(RIGHT, true, 2, 1500, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(CENTER, true, 1, 1500, VIDEO, 1));
        assertEquals(CONSUME, input.onKey(CENTER, false, 0, 1501, CONTROLS, 1));
        assertEquals(PASS, input.onKey(CENTER, true, 1, 1500, CONTROLS, 1));
    }

    @Test public void windowFocusResetDropsOldOwnershipButDoesNotExecuteHeldRepeats() {
        assertEquals(SEEK_FORWARD, down(RIGHT, VIDEO));
        input.reset();
        assertEquals(PASS, input.onKey(RIGHT, false, 0, 1100, CONTROLS, 1));
        assertEquals(CONSUME, input.onKey(LEFT, true, 1, 1500, VIDEO, 1));
        assertEquals(SEEK_BACK, input.onKey(LEFT, true, 0, 1600, VIDEO, 1));
    }

    @Test public void backwardsEventTimeCannotTriggerARepeat() {
        assertEquals(SEEK_BACK, down(LEFT, VIDEO));
        assertEquals(CONSUME, input.onKey(LEFT, true, 1, 999, VIDEO, 1));
        assertEquals(SEEK_BACK, input.onKey(LEFT, true, 2, 1250, VIDEO, 1));
    }

    @Test public void unrecognizedKeysAlwaysPassThrough() {
        for (VideoRemoteInput.Mode mode : VideoRemoteInput.Mode.values()) {
            assertEquals(PASS, down(OTHER, mode));
            assertEquals(PASS, input.onKey(OTHER, true, 1, 1500, mode, 1));
            assertEquals(PASS, input.onKey(OTHER, false, 0, 1600, mode, 1));
        }
    }
}
