package com.fnphoto.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pixel-level regression for replacing the static folder-card bitmap cache. */
public class FolderCardDrawableTest {
    @Test public void directArtworkMatchesPreviousRasterAtIntrinsicSize() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int[] colors = {0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFF66BB6A,
                0xFFFF7043, 0xFF42A5F5, 0xFFAB47BC, 0xFF26C6DA,
                0xFF78909C, 0xFFFFA726, 0xFF8D6E63, 0xFFEC407A};
        for (int color : colors) {
            Bitmap expected = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
            Canvas oldCanvas = new Canvas(expected);
            oldCanvas.drawColor(color);
            Drawable icon = ContextCompat.getDrawable(context, R.drawable.folder_icon);
            assertNotNull(icon);
            icon.setBounds(390, 180, 570, 360);
            icon.draw(oldCanvas);

            Bitmap actual = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
            FolderCardDrawable direct = new FolderCardDrawable(color,
                    ContextCompat.getDrawable(context, R.drawable.folder_icon));
            direct.setBounds(0, 0, 960, 540);
            direct.draw(new Canvas(actual));
            assertTrue("Original artwork must be preserved for color " + color, expected.sameAs(actual));
            // These are local test-owned bitmaps, never Glide-owned resources.
            expected.recycle();
            actual.recycle();
        }
    }

    @Test public void eachHolderOwnsIndependentBoundsAndAlpha() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FolderCardDrawable first = new FolderCardDrawable(0xFF5C6BC0,
                ContextCompat.getDrawable(context, R.drawable.folder_icon));
        FolderCardDrawable second = new FolderCardDrawable(0xFF5C6BC0,
                ContextCompat.getDrawable(context, R.drawable.folder_icon));
        first.setBounds(0, 0, 960, 540);
        second.setBounds(0, 0, 320, 180);
        first.setAlpha(0);
        Bitmap actual = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        second.draw(new Canvas(actual));
        assertEquals(0xFF5C6BC0, actual.getPixel(0, 0));
        assertEquals(960, first.getBounds().width());
        assertEquals(320, second.getBounds().width());
        actual.recycle();
    }
}
