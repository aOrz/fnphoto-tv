package com.fnphoto.tv;

import java.util.EnumMap;

/**
 * Routes remote keys before either a player or a focused button can consume them.
 * Keep this instance across media/player changes so the rest of an owned press
 * cannot leak into the next screen. Reset it when the window loses focus.
 */
final class VideoRemoteInput {
    enum Mode { BROWSE, VIDEO, CONTROLS }
    enum Key {
        LEFT, RIGHT, UP, DOWN, CENTER, MENU, BACK,
        PLAY, PAUSE, TOGGLE, FAST_FORWARD, REWIND, NEXT, PREVIOUS, OTHER
    }
    enum Action {
        PASS, CONSUME, SEEK_BACK, SEEK_FORWARD, SHOW_CONTROLS, HIDE_CONTROLS,
        TOGGLE_PLAY, PLAY, PAUSE, NEXT, PREVIOUS, EXIT, PHOTO_SLIDESHOW
    }

    private static final long SEEK_REPEAT_MS = 250;
    private final EnumMap<Key, Press> presses = new EnumMap<>(Key.class);

    private static final class Press {
        final Action action;
        final Mode mode;
        final long generation;
        long lastActionTime;
        boolean interrupted;

        Press(Action action, Mode mode, long generation, long eventTime) {
            this.action = action;
            this.mode = mode;
            this.generation = generation;
            lastActionTime = eventTime;
        }
    }

    Action onKey(Key key, boolean down, int repeatCount, long eventTime,
            Mode mode, long generation) {
        Press press = presses.get(key);
        if (!down) {
            presses.remove(key);
            // An orphan release never performs an action. Preserve normal button
            // releases for keys this mode does not own (including after reset).
            Action action = press != null ? press.action : actionFor(key, mode);
            return action == Action.PASS ? Action.PASS : Action.CONSUME;
        }

        if (repeatCount == 0) {
            Action action = actionFor(key, mode);
            presses.put(key, new Press(action, mode, generation, eventTime));
            return action;
        }

        if (press == null) {
            // Focus may have been lost while the physical key stayed down. An
            // orphan repeat must not start playback, navigation or a new seek.
            Action action = actionFor(key, mode) == Action.PASS
                    ? Action.PASS : Action.CONSUME;
            presses.put(key, new Press(action, mode, generation, eventTime));
            return action;
        }

        // Let the view that received the initial DOWN receive the whole sequence.
        if (press.action == Action.PASS) return Action.PASS;

        if (press.mode != mode || press.generation != generation) press.interrupted = true;
        if (!press.interrupted && isSeek(press.action)
                && eventTime >= press.lastActionTime
                && eventTime - press.lastActionTime >= SEEK_REPEAT_MS) {
            press.lastActionTime = eventTime;
            return press.action;
        }
        return Action.CONSUME;
    }

    void reset() {
        presses.clear();
    }

    private static boolean isSeek(Action action) {
        return action == Action.SEEK_BACK || action == Action.SEEK_FORWARD;
    }

    private static Action actionFor(Key key, Mode mode) {
        switch (key) {
            case PLAY: return Action.PLAY;
            case PAUSE: return mode == Mode.BROWSE ? Action.CONSUME : Action.PAUSE;
            case TOGGLE: return mode == Mode.BROWSE ? Action.PLAY : Action.TOGGLE_PLAY;
            case FAST_FORWARD: return mode == Mode.BROWSE ? Action.PHOTO_SLIDESHOW : Action.SEEK_FORWARD;
            case REWIND: return mode == Mode.BROWSE ? Action.CONSUME : Action.SEEK_BACK;
            case NEXT: return Action.NEXT;
            case PREVIOUS: return Action.PREVIOUS;
            default: break;
        }
        if (mode == Mode.BROWSE) return Action.PASS;
        if (mode == Mode.CONTROLS) {
            return key == Key.MENU || key == Key.BACK ? Action.HIDE_CONTROLS : Action.PASS;
        }
        switch (key) {
            case LEFT: return Action.SEEK_BACK;
            case RIGHT: return Action.SEEK_FORWARD;
            case UP:
            case DOWN:
            case MENU: return Action.SHOW_CONTROLS;
            case CENTER: return Action.TOGGLE_PLAY;
            case BACK: return Action.EXIT;
            default: return Action.PASS;
        }
    }
}
