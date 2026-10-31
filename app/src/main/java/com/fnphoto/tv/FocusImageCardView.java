package com.fnphoto.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.core.content.ContextCompat;
import androidx.leanback.widget.ImageCardView;

/** Keeps the remote's focus ring above opaque thumbnails, including on API 19. */
final class FocusImageCardView extends ImageCardView {
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF focusBounds = new RectF();
    private final float density;
    private final int focusColor;

    FocusImageCardView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        focusColor = ContextCompat.getColor(context, R.color.focus_ring);
        focusPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // Leanback retains selection when focus moves into the drawer. Only the
        // actual focused view gets a ring; no extra focus listener or zoom is needed.
        if (!isFocused() || !isEnabled()) return;
        float inset = 3.5f * density;
        focusBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = 11f * density;
        // A dark keyline also keeps the light ring legible over white photos.
        focusPaint.setColor(0xFF232732);
        focusPaint.setStrokeWidth(5f * density);
        canvas.drawRoundRect(focusBounds, radius, radius, focusPaint);
        focusPaint.setColor(focusColor);
        focusPaint.setStrokeWidth(3f * density);
        canvas.drawRoundRect(focusBounds, radius, radius, focusPaint);
    }
}
