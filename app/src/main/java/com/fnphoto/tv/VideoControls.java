package com.fnphoto.tv;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** One focusable transport surface for both playback engines. The seek HUD never takes focus. */
final class VideoControls {
    interface Listener {
        void seekBy(long delta);
        void seekTo(long position);
        void togglePlayback();
        void navigate(boolean next);
        void close();
    }

    private final LinearLayout panel;
    private final TextView hint;
    private final TextView positionText;
    private final TextView status;
    private final SeekBar progress;
    private final Button play;
    private final Button rewind;
    private final Button forward;
    private long duration;
    private boolean scrubbing;

    VideoControls(Context context, FrameLayout parent, Listener listener) {
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 16));
        panel.setBackgroundColor(0xE6202020);
        TextView help = text(context);
        help.setText(R.string.video_controls_help);
        panel.addView(help);
        status = text(context);
        status.setVisibility(View.GONE);
        panel.addView(status);
        positionText = text(context);
        panel.addView(positionText);
        progress = new SeekBar(context);
        progress.setMax(1000);
        // Remote arrows move between labelled actions. Touch and accessibility can scrub.
        progress.setFocusable(false);
        progress.setContentDescription(context.getString(R.string.video_progress));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { scrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                if (duration > 0) listener.seekTo(positionFor(bar.getProgress()));
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && duration > 0) {
                    positionText.setText(time(positionFor(value)) + " / " + time(duration));
                    if (!scrubbing) listener.seekTo(positionFor(value));
                }
            }
        });
        panel.addView(progress, new LinearLayout.LayoutParams(-1, dp(context, 48)));
        LinearLayout actions = new LinearLayout(context);
        panel.addView(actions);
        Button previous = button(context, actions, R.string.video_previous, v -> listener.navigate(false));
        previous.setContentDescription(context.getString(R.string.video_previous_description));
        rewind = button(context, actions, R.string.video_rewind, v -> listener.seekBy(-10000));
        play = button(context, actions, R.string.video_pause, v -> listener.togglePlayback());
        forward = button(context, actions, R.string.video_forward, v -> listener.seekBy(10000));
        Button next = button(context, actions, R.string.video_next, v -> listener.navigate(true));
        next.setContentDescription(context.getString(R.string.video_next_description));
        Button close = button(context, actions, R.string.video_close_controls, v -> listener.close());
        Button[] buttons = {previous, rewind, play, forward, next, close};
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setNextFocusLeftId(buttons[Math.max(0, i - 1)].getId());
            buttons[i].setNextFocusRightId(buttons[Math.min(buttons.length - 1, i + 1)].getId());
            buttons[i].setNextFocusUpId(buttons[i].getId());
            buttons[i].setNextFocusDownId(buttons[i].getId());
        }
        parent.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        panel.setVisibility(View.GONE);

        hint = text(context);
        hint.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12));
        hint.setBackgroundColor(0xD9202020);
        parent.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        hint.setVisibility(View.GONE);
    }

    private long positionFor(int value) { return (long) (duration * (value / 1000.0)); }

    void show() {
        hint.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
        play.requestFocus();
    }

    void hide() { panel.setVisibility(View.GONE); }
    boolean isVisible() { return panel.getVisibility() == View.VISIBLE; }
    boolean hasFocus() { return panel.hasFocus(); }
    void focus() { play.requestFocus(); }

    void hint(CharSequence message) {
        hint.setText(message);
        status.setText(message);
        status.setVisibility(isVisible() ? View.VISIBLE : View.GONE);
        if (!isVisible()) {
            hint.setVisibility(View.VISIBLE);
            hint.bringToFront();
        }
    }
    void hideHint() { hint.setVisibility(View.GONE); }
    void announce(CharSequence message) { hint.announceForAccessibility(message); }

    void update(long position, long duration, boolean playing, boolean seekable) {
        this.duration = duration;
        if (!scrubbing) {
            positionText.setText(time(position) + " / " + (duration > 0 ? time(duration) : "--:--"));
            progress.setProgress(duration > 0 ? (int) (Math.min(position, duration) * 1000.0 / duration) : 0);
        }
        progress.setEnabled(seekable);
        // Keep transport buttons focusable even before prepare, so focus never jumps when buffering.
        play.setText(playing ? R.string.video_pause : R.string.video_play);
        play.setContentDescription(play.getText());
        String unavailable = seekable ? "" : "；" + progress.getContext().getString(R.string.video_seek_unavailable);
        rewind.setContentDescription(rewind.getText() + unavailable);
        forward.setContentDescription(forward.getText() + unavailable);
    }

    static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static TextView text(Context context) {
        TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static Button button(Context context, LinearLayout row, int label, View.OnClickListener click) {
        Button button = new Button(context);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setContentDescription(context.getString(label));
        button.setBackgroundResource(R.drawable.bg_pill_button);
        button.setFocusableInTouchMode(true);
        button.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 48), 1);
        params.setMargins(dp(context, 3), 0, dp(context, 3), 0);
        row.addView(button, params);
        return button;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
