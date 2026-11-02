package com.fnphoto.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A two-stage remote-control picker. It holds only one year and twelve month buttons. */
final class YearMonthPicker {
    interface Listener { void onSelected(int year, int month); }

    private final Activity activity;
    private final Listener listener;
    private final int minYear;
    private final int maxYear;
    private final int initialMonth;
    private final LinearLayout content;
    private final AlertDialog dialog;
    private int year;
    private boolean choosingMonth;
    private boolean selected;
    private boolean backOwned;

    static YearMonthPicker show(Activity activity, int year, int month, boolean shared,
            Listener listener) {
        return show(activity, year, month, shared, listener, null);
    }

    static YearMonthPicker show(Activity activity, int year, int month, boolean shared,
            Listener listener, Runnable onDismiss) {
        YearMonthPicker picker = new YearMonthPicker(activity, year, month, shared, listener, onDismiss);
        picker.dialog.show();
        picker.dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (picker.choosingMonth) picker.showYear();
            else picker.dismiss();
        });
        picker.showYear();
        return picker;
    }

    private YearMonthPicker(Activity activity, int initialYear, int month, boolean shared,
            Listener listener, Runnable onDismiss) {
        this.activity = activity;
        this.listener = listener;
        minYear = shared ? 2000 : 1;
        maxYear = shared ? 2099 : 9999;
        year = Math.max(minYear, Math.min(maxYear, initialYear));
        initialMonth = Math.max(1, Math.min(12, month));
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(12));
        dialog = new AlertDialog.Builder(activity)
                .setTitle("跳转年月 · 选择年份")
                .setView(content)
                .setNegativeButton("取消", null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(ignored -> {
            if (onDismiss != null) onDismiss.run();
        });
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (backOwned) return true;
                backOwned = true;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && backOwned) {
                backOwned = false;
                if (choosingMonth) showYear();
                else dismiss();
                return true;
            }
            return false;
        });
    }

    private void showYear() {
        choosingMonth = false;
        dialog.setTitle("跳转年月 · 选择年份");
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText("取消");
        content.removeAllViews();
        TextView heading = text(year + " 年", 26);
        heading.setGravity(Gravity.CENTER);
        content.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView help = text(minYear == 2000 ? "共享相册支持 2000–2099 年" : "先选择年份，再选择月份", 14);
        help.setGravity(Gravity.CENTER);
        content.addView(help);
        LinearLayout steps = row();
        int[] amounts = {-10, -1, 1, 10};
        for (int amount : amounts) {
            TextView step = action((amount > 0 ? "+" : "−") + Math.abs(amount) + " 年");
            step.setOnClickListener(v -> {
                year = Math.max(minYear, Math.min(maxYear, year + amount));
                heading.setText(year + " 年");
            });
            steps.addView(step, cell());
        }
        content.addView(steps);
        TextView next = action("选择月份");
        next.setOnClickListener(v -> showMonths());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        nextParams.topMargin = dp(12);
        content.addView(next, nextParams);
        next.requestFocus();
    }

    private void showMonths() {
        choosingMonth = true;
        dialog.setTitle(year + " 年 · 选择月份");
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText("返回年份");
        content.removeAllViews();
        View selectedMonth = null;
        for (int rowIndex = 0; rowIndex < 4; rowIndex++) {
            LinearLayout months = row();
            for (int column = 0; column < 3; column++) {
                final int month = rowIndex * 3 + column + 1;
                TextView button = action(month + " 月");
                button.setOnClickListener(v -> {
                    if (selected) return;
                    selected = true;
                    // Release the dialog window before changing the query and its focus owner.
                    dismiss();
                    listener.onSelected(year, month);
                });
                months.addView(button, cell());
                if (month == initialMonth) selectedMonth = button;
            }
            content.addView(months);
        }
        if (selectedMonth != null) selectedMonth.requestFocus();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(activity.getResources().getColor(R.color.text_primary));
        view.setTextSize(size);
        return view;
    }

    private TextView action(String value) {
        TextView view = text(value, 17);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_pill_button);
        view.setFocusable(true);
        view.setClickable(true);
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    boolean isShowing() { return dialog.isShowing(); }
    void dismiss() { dialog.dismiss(); }
}
