package com.fnphoto.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Readable, remote-operable status for a screen without content. Never takes focus while binding. */
public final class TvStateView extends LinearLayout {
    private final ProgressBar progress;
    private final TextView title;
    private final TextView message;
    private final TextView action;

    public TvStateView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(48), dp(12), dp(48), dp(12));
        setFocusable(false);

        progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        LayoutParams progressParams = new LayoutParams(dp(32), dp(32));
        progressParams.bottomMargin = dp(12);
        addView(progress, progressParams);
        progress.setVisibility(GONE);

        title = text(context, 24, "#F2F4F8");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTag("tv_state_title");
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        message = text(context, 18, "#A8AFBF");
        message.setTag("tv_state_message");
        LayoutParams messageParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(8);
        addView(message, messageParams);

        action = text(context, 18, "#F2F4F8");
        action.setTag("tv_state_action");
        action.setMinHeight(dp(52));
        action.setMinWidth(dp(180));
        action.setPadding(dp(24), dp(10), dp(24), dp(10));
        action.setBackgroundResource(R.drawable.bg_pill_button);
        action.setFocusable(true);
        LayoutParams actionParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(16);
        addView(action, actionParams);
        setVisibility(GONE);
    }

    public void bind(String heading, String explanation, String actionLabel, Runnable onAction) {
        title.setText(heading);
        message.setText(explanation);
        boolean actionable = actionLabel != null && onAction != null;
        action.setText(actionLabel);
        action.setOnClickListener(actionable ? view -> onAction.run() : null);
        action.setFocusable(actionable);
        action.setClickable(actionable);
        action.setVisibility(actionable ? VISIBLE : GONE);
        setLoading(false);
        setVisibility(VISIBLE);
    }

    public void setLoading(boolean loading) {
        progress.setVisibility(loading ? VISIBLE : GONE);
    }

    public boolean focusAction() {
        return isShown() && action.isShown() && action.isFocusable() && action.requestFocus();
    }

    public boolean isActionFocused() { return action.isFocused(); }

    private TextView text(Context context, int size, String color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
