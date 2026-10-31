package com.fnphoto.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Sizes the shared folder/search tile in the same measure pass as its assigned grid span. */
public final class PhotoGridItemView extends FrameLayout {
    public PhotoGridItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View image = findViewById(R.id.iv_photo);
        if (image != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int width = Math.max(1, MeasureSpec.getSize(widthMeasureSpec)
                    - getPaddingLeft() - getPaddingRight());
            ViewGroup.LayoutParams params = image.getLayoutParams();
            params.height = Math.max(1, Math.round(width * 9f / 16f));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
