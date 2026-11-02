package com.fnphoto.tv;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.leanback.widget.ImageCardView;
import androidx.leanback.widget.BaseCardView;
import androidx.leanback.widget.Presenter;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;

/** Synthetic-only regression for labels after real DPAD, layout, drawer and recycling. */
public class AlbumTitleReproductionTest extends PhotoWallDeviceTest {
    private Instrumentation inst;
    private MainActivity host;
    private File out;
    private int observedAlbums;
    private final StringBuilder violations = new StringBuilder();
    @Test public void observeAlbumTitles() throws Exception {
        inst = InstrumentationRegistry.getInstrumentation();
        Field field = PhotoWallDeviceTest.class.getDeclaredField("activity");
        field.setAccessible(true); host = (MainActivity) field.get(this);
        out = new File(inst.getTargetContext().getExternalFilesDir(null), "album-title-fixed");
        out.mkdirs();
        inst.runOnMainSync(() -> fragment().loadAlbums());
        Thread.sleep(6000); inst.waitForIdleSync();
        capture("01-initial");
        verifyDetectorRejectsHiddenTitle();
        inst.runOnMainSync(() -> fragment().focusBrowseContent());
        Thread.sleep(1500); capture("02-first-focused");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); capture("03-right");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); capture("04-right-again");
        inst.runOnMainSync(() -> host.getWindow().getDecorView().requestLayout());
        Thread.sleep(1500); capture("05-after-request-layout");
        inst.runOnMainSync(() -> host.openBrowseMenu());
        Thread.sleep(1500); capture("06-drawer");
        key(KeyEvent.KEYCODE_BACK); capture("07-drawer-back");
        for(int i=0;i<5;i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        capture("08-scrolled-down");
        for(int i=0;i<5;i++) key(KeyEvent.KEYCODE_DPAD_UP);
        capture("09-scrolled-back");
        key(KeyEvent.KEYCODE_DPAD_LEFT); capture("10-left");
        inst.runOnMainSync(() -> fragment().loadSharedAlbums());
        Thread.sleep(6000); inst.waitForIdleSync(); capture("11-shared-initial");
        inst.runOnMainSync(() -> fragment().focusBrowseContent());
        Thread.sleep(1500); key(KeyEvent.KEYCODE_DPAD_RIGHT); capture("12-shared-right");
    }
    @Test public void pooledCardResetsPolicyAndFallbackWithoutChangingPhotoFocus() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Field field = PhotoWallDeviceTest.class.getDeclaredField("activity");
        field.setAccessible(true);
        MainActivity activity = (MainActivity) field.get(this);
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                CardPresenter presenter = new CardPresenter("http://127.0.0.1");
                Presenter.ViewHolder holder = presenter.onCreateViewHolder(
                        (ViewGroup) activity.getWindow().getDecorView());
                ImageCardView card = (ImageCardView) holder.view;
                View info = card.findViewById(androidx.leanback.R.id.info_field);
                presenter.onBindViewHolder(holder, new MediaItem("1", null, "album", null, null));
                assertEquals("未命名相册", card.getTitleText());
                assertEquals(BaseCardView.CARD_REGION_VISIBLE_ALWAYS, card.getInfoVisibility());
                card.getOnFocusChangeListener().onFocusChange(card, false);
                assertEquals(View.VISIBLE, info.getVisibility());
                presenter.onUnbindViewHolder(holder);
                presenter.onBindViewHolder(holder, new MediaItem("2", "photo.jpg", "photo", null, null));
                assertEquals("photo.jpg", card.getTitleText());
                assertEquals(BaseCardView.CARD_REGION_VISIBLE_ACTIVATED, card.getInfoVisibility());
                card.getOnFocusChangeListener().onFocusChange(card, true);
                assertEquals(View.VISIBLE, info.getVisibility());
                card.getOnFocusChangeListener().onFocusChange(card, false);
                assertEquals(View.INVISIBLE, info.getVisibility());
                presenter.onUnbindViewHolder(holder);
                presenter.onBindViewHolder(holder, new MediaItem("3", " ", "shared_album", null, null));
                assertEquals("未命名共享相册", card.getTitleText());
                assertEquals(BaseCardView.CARD_REGION_VISIBLE_ALWAYS, card.getInfoVisibility());
                card.getOnFocusChangeListener().onFocusChange(card, false);
                assertEquals(View.VISIBLE, info.getVisibility());
                presenter.onUnbindViewHolder(holder);
            } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static float effectiveAlpha(View view) {
        float alpha = 1f;
        while (view != null) {
            alpha *= view.getAlpha();
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return alpha;
    }
    private ImageCardView firstAlbum(View view) {
        if (view instanceof ImageCardView && "1".equals(view.getTag(R.id.media_item_id))
                && AlbumDisplay.isAlbum((String) view.getTag(R.id.media_item_type))) return (ImageCardView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageCardView found = firstAlbum(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private void verifyDetectorRejectsHiddenTitle() {
        AtomicReference<Boolean> rejected = new AtomicReference<>(false);
        inst.runOnMainSync(() -> {
            ImageCardView card = firstAlbum(host.getWindow().getDecorView());
            if (card == null) return;
            View title = card.findViewById(androidx.leanback.R.id.title_text);
            int originalVisibility = title.getVisibility();
            try {
                title.setVisibility(View.GONE);
                violations.setLength(0);
                inspect(card, new StringBuilder());
                rejected.set(violations.length() > 0);
            } finally { title.setVisibility(originalVisibility); }
        });
        assertTrue("Visibility detector must reject a GONE title on a visible album", rejected.get());
    }
    private MainFragment fragment() { return (MainFragment) host.getSupportFragmentManager().findFragmentById(R.id.main_content_container); }
    private void key(int code) throws Exception { inst.sendKeyDownUpSync(code); inst.waitForIdleSync(); Thread.sleep(900); }
    private void capture(String name) throws Exception {
        inst.waitForIdleSync();
        violations.setLength(0);
        observedAlbums = 0;
        StringBuilder state = new StringBuilder(name + "\n");
        inst.runOnMainSync(() -> inspect(host.getWindow().getDecorView(), state));
        try(FileWriter writer = new FileWriter(new File(out, name+".txt"))) { writer.write(state.toString()); }
        Bitmap bitmap = inst.getUiAutomation().takeScreenshot();
        if(bitmap==null) throw new AssertionError("No device screenshot");
        try(FileOutputStream stream = new FileOutputStream(new File(out,name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); }
        bitmap.recycle();
        assertTrue(name + " must contain album cards", observedAlbums > 0);
        assertEquals(name + " hidden album labels", "", violations.toString());
    }
    private void inspect(View view, StringBuilder text) {
        if(view instanceof ImageCardView) {
            ImageCardView card=(ImageCardView)view;
            View info=card.findViewById(androidx.leanback.R.id.info_field);
            TextView title=card.findViewById(androidx.leanback.R.id.title_text);
            Rect bounds=new Rect(); boolean visible=title!=null&&title.getGlobalVisibleRect(bounds);
            String type = (String) card.getTag(R.id.media_item_type);
            Rect localCardBounds = new Rect();
            // A partially clipped next row may have its image on-screen but its
            // label below the viewport. Require readability for fully visible cards.
            boolean fullyVisibleCard = card.getLocalVisibleRect(localCardBounds)
                    && localCardBounds.top <= 0 && localCardBounds.left <= 0
                    && localCardBounds.bottom >= card.getHeight()
                    && localCardBounds.right >= card.getWidth();
            if (AlbumDisplay.isAlbum(type) && fullyVisibleCard) {
                observedAlbums++;
                if (info == null || info.getVisibility() != View.VISIBLE
                        || title == null || !title.isShown() || !visible || bounds.isEmpty()
                        || title.getText() == null || title.getText().toString().trim().isEmpty()
                        || effectiveAlpha(title) <= 0.01f) {
                    violations.append(card.getTag(R.id.media_item_id)).append(' ');
                }
            }
            text.append("id=").append(card.getTag(R.id.media_item_id)).append(" title=").append(card.getTitleText())
                .append(" focus=").append(card.isFocused()).append(" activated=").append(card.isActivated())
                .append(" infoVisibility=").append(info==null?-1:info.getVisibility())
                .append(" titleShown=").append(title!=null&&title.isShown()).append(" globalVisible=").append(visible)
                .append(" bounds=").append(bounds).append('\n');
        }
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++) inspect(group.getChildAt(i),text); }
    }
}
