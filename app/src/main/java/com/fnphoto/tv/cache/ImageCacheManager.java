package com.fnphoto.tv.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.bumptech.glide.Glide;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Glide owns image data and size-specific resources; this class only manages cache settings. */
public final class ImageCacheManager {
    private static ImageCacheManager instance;
    private final Context context;
    private final File legacyDirectory;
    private final ExecutorService diskExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    public interface SizeCallback { void onSize(long bytes); }
    public interface CompletionCallback { void onComplete(boolean success); }
    public static synchronized ImageCacheManager getInstance(Context context) {
        if (instance == null) instance = new ImageCacheManager(context.getApplicationContext());
        return instance;
    }
    private ImageCacheManager(Context context) {
        this.context = context;
        legacyDirectory = new File(context.getCacheDir(), "image_cache");
        // Old JPEGs are transformed and keyed by URL only: never reuse them for another size.
        diskExecutor.execute(() -> CacheFiles.deleteContents(legacyDirectory));
    }
    public void getCacheSizeAsync(SizeCallback callback) {
        diskExecutor.execute(() -> {
            long bytes = CacheFiles.size(Glide.getPhotoCacheDir(context)) + CacheFiles.size(legacyDirectory);
            main.post(() -> callback.onSize(bytes));
        });
    }
    public void clearCache(CompletionCallback callback) {
        // Memory cache APIs require the main thread; disk APIs require a worker thread.
        main.post(() -> {
            Glide.get(context).clearMemory();
            diskExecutor.execute(() -> {
                boolean success;
                try {
                    Glide.get(context).clearDiskCache();
                    success = CacheFiles.deleteContents(legacyDirectory);
                } catch (RuntimeException e) {
                    success = false;
                }
                final boolean cleared = success;
                main.post(() -> { if (callback != null) callback.onComplete(cleared); });
            });
        });
    }
}
