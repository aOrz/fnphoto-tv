package com.fnphoto.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Folder artwork without a full-card bitmap or a process-lifetime raster cache. */
final class FolderCardDrawable extends Drawable {
    private final Paint background = new Paint();
    private final Drawable icon;

    FolderCardDrawable(int color, Drawable icon) {
        background.setColor(color);
        this.icon = icon == null ? null : icon.mutate();
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.drawRect(bounds, background);
        if (icon != null) {
            // Match the previous 960 x 540 artwork's centered 180 x 180 icon.
            int half = Math.min(bounds.width() * 3 / 16, bounds.height() / 3) / 2;
            icon.setBounds(bounds.centerX() - half, bounds.centerY() - half,
                    bounds.centerX() + half, bounds.centerY() + half);
            icon.draw(canvas);
        }
    }

    @Override public int getIntrinsicWidth() { return 960; }
    @Override public int getIntrinsicHeight() { return 540; }
    @Override public void setAlpha(int alpha) {
        background.setAlpha(alpha);
        if (icon != null) icon.setAlpha(alpha);
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) {
        background.setColorFilter(filter);
        if (icon != null) icon.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
