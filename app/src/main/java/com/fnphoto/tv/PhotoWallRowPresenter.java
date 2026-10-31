package com.fnphoto.tv;

import android.view.ViewGroup;

import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.RowPresenter;

/** Keeps a complete row stationary while the remote moves focus between its cards. */
final class PhotoWallRowPresenter extends ListRowPresenter {
    interface WidthListener { void onWidthChanged(int width); }
    private final WidthListener widthListener;

    PhotoWallRowPresenter(WidthListener widthListener) {
        this.widthListener = widthListener;
    }
    // 48dp TV safe area plus 24dp for half of the default 14% focus zoom (max 320dp cards).
    static final int SIDE_PADDING_DP = 72;
    static final int GAP_DP = 12;

    @Override protected RowPresenter.ViewHolder createRowViewHolder(ViewGroup parent) {
        ListRowPresenter.ViewHolder original = (ListRowPresenter.ViewHolder) super.createRowViewHolder(parent);
        HorizontalGridView grid = original.getGridView();
        float density = parent.getResources().getDisplayMetrics().density;
        int padding = Math.round(SIDE_PADDING_DP * density);
        grid.setPadding(padding, grid.getPaddingTop(), padding, grid.getPaddingBottom());
        grid.setHorizontalSpacing(Math.round(GAP_DP * density));
        grid.setFadingLeftEdge(false);
        grid.setFadingRightEdge(false);
        // Prefer the row edges over the focus keyline. When all cards fit, Leanback's
        // low/high scroll bounds coincide, including short final rows and one-card footers.
        grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE);
        grid.setWindowAlignmentPreferKeyLineOverLowEdge(false);
        grid.setWindowAlignmentPreferKeyLineOverHighEdge(false);
        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left > 0 && right - left != oldRight - oldLeft) {
                widthListener.onWidthChanged(right - left);
            }
        });
        // ListRowPresenter caches padding in its holder and reapplies it during selection.
        return new ListRowPresenter.ViewHolder(original.view, grid, this);
    }
}
