package com.fnphoto.tv;

import android.content.Context;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Fits a vertical grid to its measured viewport, including its existing safe-area padding. */
final class AdaptiveGridLayoutManager extends GridLayoutManager {
    private final Context context;
    private final int minCardWidthDp;
    private final int maxColumns;

    AdaptiveGridLayoutManager(Context context) {
        this(context, 144, 6);
    }

    AdaptiveGridLayoutManager(Context context, int minCardWidthDp, int maxColumns) {
        super(context, 1);
        this.context = context;
        this.minCardWidthDp = minCardWidthDp;
        this.maxColumns = maxColumns;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        // Reflow only the retained adapter items. Do not reset the adapter, its focus or paging.
        // A predictive pre-layout must use the previous span assignment.
        if (!state.isPreLayout() && getWidth() > 0) {
            PhotoWallLayout layout = PhotoWallLayout.calculate(getWidth(), getPaddingLeft(),
                    getPaddingRight(), 0, context.getResources().getDisplayMetrics().density,
                    minCardWidthDp, maxColumns);
            if (getSpanCount() != layout.columns) setSpanCount(layout.columns);
        }
        super.onLayoutChildren(recycler, state);
    }
}
