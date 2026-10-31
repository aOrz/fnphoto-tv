package com.fnphoto.tv.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

/** One asynchronous, size-aware cache pipeline. Never decode or scan disk on the UI thread. */
public final class CachedImageLoader {
    private CachedImageLoader() {}

    private static GlideUrl model(Context context, String url, String fallbackToken) {
        String token = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE)
                .getString("api_token", fallbackToken == null ? "" : fallbackToken);
        return new GlideUrl(url, new LazyHeaders.Builder().addHeader("accesstoken", token).build());
    }

    public interface ImageLoadCallback {
        void onBitmapLoaded(Bitmap bitmap);
        void onLoadFailed();
        /** Drop all references to Glide-owned bitmaps here. Never recycle them yourself. */
        default void onLoadCleared() {}
    }

    public interface LoadListener {
        void onLoaded();
        void onLoadFailed();
    }

    /** Clear on the main thread when a custom target is no longer displayed/needed. */
    public static final class RequestHandle {
        private RequestManager manager;
        private Target<?> target;
        private RequestHandle(RequestManager manager, Target<?> target) {
            this.manager = manager;
            this.target = target;
        }
        public void clear() {
            if (target != null) {
                Target<?> old = target;
                target = null;
                manager.clear(old);
                manager = null;
            }
        }
    }

    public static RequestHandle loadImage(Context context, String url, String token,
            int width, int height, ImageLoadCallback callback) {
        RequestManager manager = Glide.with(context);
        // A not-yet-measured view must never accidentally request SIZE_ORIGINAL.
        int safeWidth = ImageDimensions.safeWidth(width);
        int safeHeight = ImageDimensions.safeHeight(height);
        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>(safeWidth, safeHeight) {
            @Override public void onResourceReady(@NonNull Bitmap resource,
                    @Nullable Transition<? super Bitmap> transition) {
                callback.onBitmapLoaded(resource);
            }
            @Override public void onLoadFailed(@Nullable Drawable errorDrawable) {
                callback.onLoadFailed();
            }
            @Override public void onLoadCleared(@Nullable Drawable placeholder) {
                callback.onLoadCleared();
            }
        };
        manager.asBitmap().disallowHardwareConfig().load(url == null || url.isEmpty() ? null : model(context, url, token))
                .override(safeWidth, safeHeight).into(target);
        return new RequestHandle(manager, target);
    }

    public static void loadIntoImageView(ImageView view, String url, String token) {
        Glide.with(view.getContext()).load(url == null || url.isEmpty() ? null : model(view.getContext(), url, token))
                .centerCrop().placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery).into(view);
    }

    /** The thumbnail belongs to the same request: it cannot overwrite a completed full image. */
    public static void loadPhotoIntoImageView(ImageView view, String url, String thumbnailUrl,
            String token, @Nullable LoadListener listener) {
        RequestManager manager = Glide.with(view.getContext());
        RequestBuilder<Drawable> full = manager.load(url == null || url.isEmpty() ? null
                : model(view.getContext(), url, token)).fitCenter();
        if (thumbnailUrl != null && !thumbnailUrl.isEmpty() && !thumbnailUrl.equals(url)) {
            full = full.thumbnail(manager.load(model(view.getContext(), thumbnailUrl, token)).fitCenter());
        }
        full.listener(new RequestListener<Drawable>() {
            @Override public boolean onLoadFailed(@Nullable GlideException e, Object model,
                    Target<Drawable> target, boolean isFirstResource) {
                if (listener != null) listener.onLoadFailed();
                return false;
            }
            @Override public boolean onResourceReady(Drawable resource, Object model,
                    Target<Drawable> target, DataSource source, boolean isFirstResource) {
                if (listener != null) listener.onLoaded();
                return false;
            }
        }).into(view);
    }

    public static void clear(ImageView view) {
        if (view == null) return;
        // Application manager can clear a target even during late holder recycling after Activity destruction.
        Glide.with(view.getContext().getApplicationContext()).clear(view);
        view.setImageDrawable(null);
    }

    public static RequestHandle preloadImage(Context context, String url, String token, int width, int height) {
        RequestManager manager = Glide.with(context);
        Target<Drawable> target = manager.load(url == null || url.isEmpty() ? null : model(context, url, token))
                .fitCenter().priority(Priority.LOW)
                .preload(ImageDimensions.safeWidth(width), ImageDimensions.safeHeight(height));
        return new RequestHandle(manager, target);
    }

    public static void clearAllCache(Context context, ImageCacheManager.CompletionCallback callback) {
        ImageCacheManager.getInstance(context).clearCache(callback);
    }
    public static void getCacheSizeAsync(Context context, ImageCacheManager.SizeCallback callback) {
        ImageCacheManager.getInstance(context).getCacheSizeAsync(callback);
    }
}
