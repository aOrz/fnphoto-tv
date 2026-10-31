package com.fnphoto.tv;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.fnphoto.tv.api.FnAuthUtils;
import com.fnphoto.tv.api.FnHttpApi;
import com.fnphoto.tv.api.HttpClientProvider;
import com.fnphoto.tv.cache.CachedImageLoader;
import com.fnphoto.tv.player.AuthenticatedHttpDataSourceFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MediaDetailActivity extends FragmentActivity {
    private static final String TAG = "MediaDetailActivity";
    private static final long DEBOUNCE_TIME = 300;
    private static final long SLIDESHOW_INTERVAL = 5000;
    private static final float ZOOM_STEP = 0.25f;
    private static final float MAX_SCALE = 5.0f;
    private static final float MIN_SCALE = 0.5f;

    private FrameLayout container;
    private ImageView imageView;
    private PlayerView playerView;
    private SimpleExoPlayer player;
    private android.media.MediaPlayer fallbackPlayer;
    private final DetailLoadState loadState = new DetailLoadState();
    private final DetailLoadState navigationState = new DetailLoadState();
    private final DetailPlaybackState fallbackPlayback = new DetailPlaybackState();
    private final List<CachedImageLoader.RequestHandle> preloads = new ArrayList<>();
    private MediaPageController pageController;
    private Call<FnHttpApi.PhotoDetailResponse> detailCall;
    private boolean reloadOnStart;
    private View infoOverlay;
    private TextView tvInfoContent;
    private TextView tvSlideshowIndicator;
    private ProgressBar loadingIndicator;

    private List<MediaItem> mediaList;
    private int currentIndex;
    private Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Handler slideshowHandler = new Handler(Looper.getMainLooper());
    private boolean canSwitch = true;
    private boolean isVideoPlaying = false;
    private MediaItem currentVideoItem;
    private boolean slideshowActive = false;
    private boolean currentPhotoReady;
    private boolean infoVisible = false;
    private float currentScale = 1.0f;
    private boolean isZoomed = false;
    private FnHttpApi api;
    private String baseUrl;
    private String token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        container = new FrameLayout(this);
        container.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(container);

        pageController = new MediaPageController(MediaListHolder.get(), MediaListHolder.getPageSource());
        mediaList = pageController.getItems();
        MediaListHolder.clear();
        currentIndex = getIntent().getIntExtra("CURRENT_INDEX", 0);

        if (mediaList == null || mediaList.isEmpty()) {
            Toast.makeText(this, "没有可显示的媒体", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("nas_url", "");
        token = prefs.getString("api_token", "");

        if (baseUrl != null && !baseUrl.isEmpty()) {
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl + "/")
                    .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                    .client(HttpClientProvider.getClient(this))
                    .build();
            api = retrofit.create(FnHttpApi.class);
        }

        showCurrentMedia();
    }

    private void showCurrentMedia() {
        if (currentIndex < 0 || currentIndex >= mediaList.size()) return;

        MediaItem item = mediaList.get(currentIndex);
        slideshowHandler.removeCallbacks(slideshowRunnable);
        currentPhotoReady = false;
        Log.d(TAG, "Showing media: " + item.getTitle() + " type: " + item.getType());

        loadState.next();
        navigationState.next();
        clearImageRequests();
        releasePlayers();
        isVideoPlaying = false;
        currentVideoItem = null;
        currentScale = 1.0f;
        isZoomed = false;

        container.removeAllViews();
        hideInfoOverlay();
        showLoading();

        if (slideshowActive) showSlideshowIndicator(true);
        if ("video".equals(item.getType())) {
            if (slideshowActive) startVideoPlayback(item);
            else showVideoPreview(item);
        } else {
            showPhoto(item);
        }
    }

    private void showLoading() {
        if (loadingIndicator == null) {
            loadingIndicator = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
            loadingIndicator.setIndeterminate(true);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER;
            loadingIndicator.setLayoutParams(params);
        }
        if (loadingIndicator.getParent() == null) {
            container.addView(loadingIndicator);
        }
        loadingIndicator.setVisibility(View.VISIBLE);
        loadingIndicator.bringToFront();
    }

    private void hideLoading() {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.GONE);
        }
    }

    private ImageView createMediaImageView() {
        ImageView view = new ImageView(this);
        view.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        container.addView(view);
        if (loadingIndicator != null) loadingIndicator.bringToFront();
        imageView = view;
        return view;
    }

    private void showPhoto(MediaItem item) {
        final ImageView target = createMediaImageView();
        final long generation = loadState.next();
        String url = item.getMediaUrl();
        if (url == null || url.isEmpty()) url = item.getThumbnailUrl();
        CachedImageLoader.loadPhotoIntoImageView(target, url, item.getThumbnailUrl(), token,
                new CachedImageLoader.LoadListener() {
                    @Override public void onLoaded() {
                        if (!loadState.isCurrent(generation) || imageView != target) return;
                        hideLoading();
                        currentPhotoReady = true;
                        schedulePhotoSlideshow();
                    }
                    @Override public void onLoadFailed() {
                        if (!loadState.isCurrent(generation) || imageView != target) return;
                        hideLoading();
                        currentPhotoReady = true;
                        schedulePhotoSlideshow();
                        Toast.makeText(MediaDetailActivity.this, "图片加载失败", Toast.LENGTH_SHORT).show();
                    }
                });
        target.setOnClickListener(v -> finish());
        preloadAdjacentPhotos();
    }

    private void preloadAdjacentPhotos() {
        // One bounded, lower-priority photo ahead. Never fetch a video as an image.
        if (mediaList == null || currentIndex + 1 >= mediaList.size()) return;
        MediaItem item = mediaList.get(currentIndex + 1);
        if (!DetailLoadState.shouldPreload(item)) return;
        int width = Math.min(1920, getResources().getDisplayMetrics().widthPixels);
        int height = Math.min(1080, getResources().getDisplayMetrics().heightPixels);
        preloads.add(CachedImageLoader.preloadImage(this, item.getMediaUrl(), token, width, height));
    }

    private void clearImageRequests() {
        if (imageView != null) {
            CachedImageLoader.clear(imageView);
            imageView = null;
        }
        for (CachedImageLoader.RequestHandle handle : preloads) handle.clear();
        preloads.clear();
        if (detailCall != null) {
            detailCall.cancel();
            detailCall = null;
        }
    }

    private void showVideoPreview(MediaItem item) {
        currentVideoItem = item;
        String previewUrl = item.getThumbnailUrl();
        if (previewUrl == null || previewUrl.isEmpty()) {
            startVideoPlayback(item);
            return;
        }
        final ImageView target = createMediaImageView();
        final long generation = loadState.next();
        // Overlay a small view instead of allocating another full-screen ARGB bitmap.
        TextView playButton = new TextView(this);
        playButton.setText("▶");
        playButton.setTextSize(48);
        playButton.setGravity(Gravity.CENTER);
        playButton.setTextColor(Color.WHITE);
        playButton.setBackgroundColor(0x66000000);
        int buttonSize = (int) (88 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER);
        container.addView(playButton, params);
        CachedImageLoader.loadPhotoIntoImageView(target, previewUrl, null, token,
                new CachedImageLoader.LoadListener() {
                    @Override public void onLoaded() {
                        if (loadState.isCurrent(generation) && imageView == target) hideLoading();
                    }
                    @Override public void onLoadFailed() {
                        if (loadState.isCurrent(generation) && imageView == target) hideLoading();
                    }
                });
        View.OnClickListener play = v -> {
            if (loadState.isCurrent(generation) && !isVideoPlaying) startVideoPlayback(item);
        };
        target.setOnClickListener(play);
        playButton.setOnClickListener(play);
    }

    private void releasePlayers() {
        fallbackPlayback.reset();
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (fallbackPlayer != null) {
            android.media.MediaPlayer old = fallbackPlayer;
            fallbackPlayer = null;
            old.setOnPreparedListener(null);
            old.setOnErrorListener(null);
            old.setOnCompletionListener(null);
            old.release();
        }
        playerView = null;
    }

    private void startVideoPlayback(MediaItem item) {
        loadState.next();
        clearImageRequests();
        releasePlayers();
        isVideoPlaying = true;
        currentVideoItem = item;

        container.removeAllViews();
        showLoading();
        if (slideshowActive) showSlideshowIndicator(true);

        String videoId = item.getId();
        String videoUrl = baseUrl + "/p/api/v1/stream/v/" + videoId;
        Log.d(TAG, "Playing video: " + videoUrl);

        if (!tryExoPlayer(videoUrl)) {
            setupMediaPlayer(videoUrl);
        }
    }

    private boolean tryExoPlayer(String videoUrl) {
        try {
            playerView = new PlayerView(this);
            playerView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            container.addView(playerView);

            player = new SimpleExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            AuthenticatedHttpDataSourceFactory dataSourceFactory =
                    new AuthenticatedHttpDataSourceFactory(this, "ExoPlayer");

            final SimpleExoPlayer requestedPlayer = player;
            final long generation = loadState.next();
            player.addListener(new Player.DefaultEventListener() {
                @Override
                public void onPlayerError(com.google.android.exoplayer2.ExoPlaybackException error) {
                    if (!loadState.isCurrent(generation) || player != requestedPlayer) return;
                    Log.e(TAG, "ExoPlayer error, falling back to MediaPlayer", error);
                    hideLoading();
                    if (player != null) {
                        player.release();
                        player = null;
                    }
                    if (playerView != null) {
                        container.removeView(playerView);
                        playerView = null;
                    }
                    showLoading();
                    setupMediaPlayer(videoUrl);
                }

                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    if (!loadState.isCurrent(generation) || player != requestedPlayer) return;
                    if (playbackState == Player.STATE_READY) hideLoading();
                    if (playbackState == Player.STATE_ENDED) {
                        if (slideshowActive) {
                            switchToNext();
                        }
                    }
                }
            });

            android.net.Uri uri = android.net.Uri.parse(videoUrl);
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(uri);
            player.prepare(mediaSource);
            player.setPlayWhenReady(true);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ExoPlayer init failed, falling back to MediaPlayer", e);
            if (player != null) {
                player.release();
                player = null;
            }
            if (playerView != null) {
                container.removeView(playerView);
                playerView = null;
            }
            return false;
        }
    }

    private void setupMediaPlayer(String videoUrl) {
        try {
            android.view.SurfaceView sv = new android.view.SurfaceView(this);
            sv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            container.addView(sv);

            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            fallbackPlayer = mp;
            fallbackPlayback.reset();
            final long generation = loadState.next();
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("accesstoken", token != null ? token : "");
            mp.setDataSource(this, android.net.Uri.parse(videoUrl), headers);

            sv.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    if (!loadState.isCurrent(generation) || fallbackPlayer != mp) return;
                    mp.setDisplay(holder);
                    mp.prepareAsync();
                }
                @Override
                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {}
                @Override
                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    if (fallbackPlayer == mp) {
                        fallbackPlayer = null;
                        fallbackPlayback.reset();
                        mp.release();
                    }
                }
            });

            mp.setOnPreparedListener(mp2 -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return;
                hideLoading();
                fallbackPlayback.prepared();
                if (fallbackPlayback.shouldPlay()) mp2.start();
            });
            mp.setOnErrorListener((mp2, what, extra) -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return true;
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                hideLoading();
                Toast.makeText(MediaDetailActivity.this,
                        "视频播放失败", Toast.LENGTH_LONG).show();
                releasePlayers();
                isVideoPlaying = false;
                if (slideshowActive) stopSlideshow();
                return true;
            });
            mp.setOnCompletionListener(mp2 -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return;
                if (slideshowActive) {
                    switchToNext();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "MediaPlayer init failed", e);
            releasePlayers();
            isVideoPlaying = false;
            if (slideshowActive) stopSlideshow();
            hideLoading();
            Toast.makeText(MediaDetailActivity.this,
                    "视频播放失败", Toast.LENGTH_LONG).show();
        }
    }

    private void applyZoom() {
        if (imageView != null && !isVideoPlaying) {
            Matrix matrix = new Matrix();
            float pivotX = imageView.getWidth() / 2f;
            float pivotY = imageView.getHeight() / 2f;
            matrix.postScale(currentScale, currentScale, pivotX, pivotY);
            imageView.setScaleType(ImageView.ScaleType.MATRIX);
            imageView.setImageMatrix(matrix);
        }
    }

    private void switchToPrevious() {
        if (!canSwitch || slideshowActive) return;
        if (currentIndex > 0) {
            currentIndex--;
            showCurrentMedia();
            debounceSwitch();
        } else {
            Toast.makeText(this, "已经是第一个", Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToNext() {
        if (!canSwitch && !slideshowActive) return;
        if (currentIndex < mediaList.size() - 1) {
            currentIndex++;
            showCurrentMedia();
            if (!slideshowActive) debounceSwitch();
        } else if (pageController != null && pageController.hasMore()) {
            if (pageController.isLoading()) return;
            final long generation = loadState.current();
            final long navigation = navigationState.current();
            showLoading();
            pageController.ensureNext(currentIndex, new MediaPageController.Callback() {
                @Override public void onReady(boolean nextAvailable) {
                    if (!loadState.isCurrent(generation) || !navigationState.isCurrent(navigation)) return;
                    hideLoading();
                    if (nextAvailable) switchToNext();
                    else finishNavigation();
                }
                @Override public void onFailure() {
                    if (!loadState.isCurrent(generation) || !navigationState.isCurrent(navigation)) return;
                    hideLoading();
                    if (slideshowActive) stopSlideshow();
                    Toast.makeText(MediaDetailActivity.this, "加载下一页失败，按右键重试", Toast.LENGTH_LONG).show();
                }
            });
        } else {
            finishNavigation();
        }
    }

    private void finishNavigation() {
            if (slideshowActive) {
                stopSlideshow();
                Toast.makeText(this, "幻灯片播放结束", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已经是最后一个", Toast.LENGTH_SHORT).show();
            }
    }

    private void debounceSwitch() {
        canSwitch = false;
        debounceHandler.postDelayed(() -> canSwitch = true, DEBOUNCE_TIME);
    }

    private void toggleSlideshow() {
        if (slideshowActive) {
            stopSlideshow();
        } else {
            startSlideshow();
        }
    }

    private void startSlideshow() {
        if (mediaList.size() <= 1 && (pageController == null || !pageController.hasMore())) {
            Toast.makeText(this, "需要至少2张照片才能播放幻灯片", Toast.LENGTH_SHORT).show();
            return;
        }
        slideshowActive = true;
        canSwitch = true;
        debounceHandler.removeCallbacksAndMessages(null);
        showSlideshowIndicator(true);
        if (currentVideoItem != null && !isVideoPlaying) startVideoPlayback(currentVideoItem);
        else schedulePhotoSlideshow();
        Toast.makeText(this, "幻灯片已开始", Toast.LENGTH_SHORT).show();
    }

    private void stopSlideshow() {
        navigationState.next();
        if (pageController != null && pageController.isLoading()) hideLoading();
        slideshowActive = false;
        slideshowHandler.removeCallbacks(slideshowRunnable);
        showSlideshowIndicator(false);
        Toast.makeText(this, "幻灯片已停止", Toast.LENGTH_SHORT).show();
    }

    private void schedulePhotoSlideshow() {
        slideshowHandler.removeCallbacks(slideshowRunnable);
        if (slideshowActive && !isVideoPlaying && currentPhotoReady) {
            slideshowHandler.postDelayed(slideshowRunnable, SLIDESHOW_INTERVAL);
        }
    }

    private final Runnable slideshowRunnable = new Runnable() {
        @Override public void run() {
            if (slideshowActive && !isVideoPlaying) switchToNext();
            // The next photo schedules its own full interval after loading. Videos advance on ended.
        }
    };

    private void showSlideshowIndicator(boolean show) {
        if (show) {
            if (tvSlideshowIndicator == null) {
                tvSlideshowIndicator = new TextView(this);
                tvSlideshowIndicator.setText("▶ 幻灯片播放中");
                tvSlideshowIndicator.setTextColor(Color.WHITE);
                tvSlideshowIndicator.setTextSize(16);
                tvSlideshowIndicator.setBackgroundColor(Color.parseColor("#80000000"));
                tvSlideshowIndicator.setPadding(24, 12, 24, 12);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.topMargin = 24;
                tvSlideshowIndicator.setLayoutParams(params);
            }
            if (tvSlideshowIndicator.getParent() == null) {
                container.addView(tvSlideshowIndicator);
            }
            tvSlideshowIndicator.setVisibility(View.VISIBLE);
        } else {
            if (tvSlideshowIndicator != null) {
                tvSlideshowIndicator.setVisibility(View.GONE);
            }
        }
    }

    private void showInfoOverlay() {
        if (infoOverlay != null && infoOverlay.getVisibility() == View.VISIBLE) {
            hideInfoOverlay();
            return;
        }

        if (isVideoPlaying) {
            Toast.makeText(this, "视频播放中无法查看信息", Toast.LENGTH_SHORT).show();
            return;
        }

        MediaItem item = mediaList.get(currentIndex);
        int photoId;
        try {
            photoId = Integer.parseInt(item.getId());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "无法获取照片信息", Toast.LENGTH_SHORT).show();
            return;
        }

        if (infoOverlay == null) {
            infoOverlay = getLayoutInflater().inflate(R.layout.photo_info_overlay, null);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            infoOverlay.setLayoutParams(params);
            infoOverlay.setBackgroundColor(Color.parseColor("#AA000000"));
            tvInfoContent = infoOverlay.findViewById(R.id.tv_info_content);
        }

        if (infoOverlay.getParent() == null) {
            container.addView(infoOverlay);
        }

        tvInfoContent.setText("加载中...");
        infoOverlay.setVisibility(View.VISIBLE);
        infoOverlay.bringToFront();
        infoVisible = true;

        loadPhotoDetail(photoId);
    }

    private void loadPhotoDetail(int photoId) {
        if (api == null || token == null) {
            tvInfoContent.setText("API未初始化");
            return;
        }

        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/detail/" + photoId, "GET", null);

        if (detailCall != null) detailCall.cancel();
        final long generation = loadState.current();
        detailCall = api.getPhotoDetail(token, authx, photoId);
        detailCall.enqueue(new Callback<FnHttpApi.PhotoDetailResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.PhotoDetailResponse> call,
                                   Response<FnHttpApi.PhotoDetailResponse> response) {
                if (!loadState.isCurrent(generation) || !infoVisible || call.isCanceled()) return;
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.PhotoDetailResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.info != null) {
                        displayPhotoInfo(result.data.info);
                    } else {
                        tvInfoContent.setText("无法获取照片信息");
                    }
                } else {
                    tvInfoContent.setText("请求失败: HTTP " + response.code());
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.PhotoDetailResponse> call, Throwable t) {
                if (!loadState.isCurrent(generation) || !infoVisible || call.isCanceled()) return;
                tvInfoContent.setText("网络错误: " + t.getMessage());
            }
        });
    }

    private void displayPhotoInfo(FnHttpApi.PhotoDetailInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("📷 照片信息\n\n");
        sb.append("文件名: ").append(nullToStr(info.fileName)).append("\n");
        sb.append("类型: ").append(nullToStr(info.category)).append("\n");
        sb.append("尺寸: ").append(info.width).append(" × ").append(info.height).append("px").append("\n");

        if (info.fileSize > 0) {
            String sizeStr;
            if (info.fileSize > 1024 * 1024) {
                sizeStr = String.format(Locale.US, "%.1f MB", info.fileSize / (1024f * 1024f));
            } else if (info.fileSize > 1024) {
                sizeStr = String.format(Locale.US, "%.1f KB", info.fileSize / 1024f);
            } else {
                sizeStr = info.fileSize + " B";
            }
            sb.append("文件大小: ").append(sizeStr).append("\n");
        }

        if (info.photoDateTime != null && !info.photoDateTime.isEmpty()) {
            sb.append("拍摄时间: ").append(info.photoDateTime).append("\n");
        } else if (info.dateTime != null && !info.dateTime.isEmpty()) {
            sb.append("上传时间: ").append(info.dateTime).append("\n");
        }

        if (info.make != null && !info.make.isEmpty()) {
            sb.append("相机: ").append(info.make);
            if (info.model != null && !info.model.isEmpty()) {
                sb.append(" ").append(info.model);
            }
            sb.append("\n");
        }

        if (info.fNumber != null && !info.fNumber.isEmpty()) {
            sb.append("光圈: F/").append(info.fNumber).append("\n");
        }
        if (info.exposureTime != null && !info.exposureTime.isEmpty()) {
            sb.append("快门: ").append(info.exposureTime).append("\n");
        }
        if (info.isoSpeedRatings != null && !info.isoSpeedRatings.isEmpty()) {
            sb.append("ISO: ").append(info.isoSpeedRatings).append("\n");
        }
        if (info.focalLength != null && !info.focalLength.isEmpty()) {
            sb.append("焦距: ").append(info.focalLength).append("mm\n");
        }
        if (info.mp != null && !info.mp.isEmpty()) {
            sb.append("像素: ").append(info.mp).append("\n");
        }
        if (info.geo != null && !info.geo.isEmpty()) {
            sb.append("地理位置: ").append(info.geo).append("\n");
        }

        sb.append("\n 按 INFO 键关闭");
        tvInfoContent.setText(sb.toString());
    }

    private void hideInfoOverlay() {
        if (detailCall != null) {
            detailCall.cancel();
            detailCall = null;
        }
        if (infoOverlay != null) {
            infoOverlay.setVisibility(View.GONE);
        }
        infoVisible = false;
    }

    private void toggleCollect() {
        MediaItem item = mediaList.get(currentIndex);
        if (!"photo".equals(item.getType()) && !"video".equals(item.getType())) {
            Toast.makeText(this, "只能收藏照片或视频", Toast.LENGTH_SHORT).show();
            return;
        }

        if (api == null || token == null || baseUrl == null) {
            Toast.makeText(this, "API未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        int photoId;
        try {
            photoId = Integer.parseInt(item.getId());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "无法获取媒体ID", Toast.LENGTH_SHORT).show();
            return;
        }

        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/collect", "POST", "id=" + photoId + "&collect=1");
        String body = "id=" + photoId + "&collect=1";

        api.toggleCollect(token, authx, photoId, 1).enqueue(new Callback<FnHttpApi.BaseResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.BaseResponse> call,
                                   Response<FnHttpApi.BaseResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.BaseResponse result = response.body();
                    if (result.errno == 0) {
                        Toast.makeText(MediaDetailActivity.this,
                                "已收藏 ❤️", Toast.LENGTH_SHORT).show();
                    } else {
                        // Try to un-collect
                        toggleUnCollect(photoId);
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.BaseResponse> call, Throwable t) {
                Log.e(TAG, "收藏失败", t);
                Toast.makeText(MediaDetailActivity.this, "收藏失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void toggleUnCollect(int photoId) {
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/collect", "POST", "id=" + photoId + "&collect=0");

        api.toggleCollect(token, authx, photoId, 0).enqueue(new Callback<FnHttpApi.BaseResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.BaseResponse> call,
                                   Response<FnHttpApi.BaseResponse> response) {
                if (response.isSuccessful()) {
                    Toast.makeText(MediaDetailActivity.this,
                            "已取消收藏 ♡", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.BaseResponse> call, Throwable t) {
                Log.e(TAG, "取消收藏失败", t);
            }
        });
    }

    private String nullToStr(String str) {
        return str != null && !str.isEmpty() && !"null".equals(str) ? str : "-";
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (infoVisible) {
            if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_BACK) {
                hideInfoOverlay();
                return true;
            }
            return true;
        }

        if (slideshowActive) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                stopSlideshow();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                stopSlideshow();
                finish();
                return true;
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                switchToPrevious();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                switchToNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!isVideoPlaying && currentScale < MAX_SCALE) {
                    currentScale = Math.min(currentScale + ZOOM_STEP, MAX_SCALE);
                    applyZoom();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!isVideoPlaying && currentScale > MIN_SCALE) {
                    currentScale = Math.max(currentScale - ZOOM_STEP, MIN_SCALE);
                    applyZoom();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                handleOkKey();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player != null) {
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                } else if (fallbackPlayer != null) {
                    toggleFallbackPlayback();
                }
                return true;
            case KeyEvent.KEYCODE_INFO:
                showInfoOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                startSlideshow();
                return true;
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_F2:
                toggleCollect();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void toggleFallbackPlayback() {
        fallbackPlayback.toggle();
        if (fallbackPlayer == null || !fallbackPlayback.isPrepared()) return;
        if (fallbackPlayback.shouldPlay()) fallbackPlayer.start();
        else fallbackPlayer.pause();
    }

    private void handleOkKey() {
        if (player != null && isVideoPlaying) {
            boolean isPlaying = player.getPlayWhenReady();
            player.setPlayWhenReady(!isPlaying);
            String message = isPlaying ? "已暂停" : "继续播放";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } else if (fallbackPlayer != null && isVideoPlaying) {
            toggleFallbackPlayback();
        } else if (currentVideoItem != null && !isVideoPlaying) {
            startVideoPlayback(currentVideoItem);
        } else if (!isVideoPlaying) {
            toggleSlideshow();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (reloadOnStart && mediaList != null && !mediaList.isEmpty()) {
            reloadOnStart = false;
            showCurrentMedia();
        }
    }

    @Override
    protected void onStop() {
        loadState.next();
        navigationState.next();
        clearImageRequests();
        releasePlayers();
        slideshowActive = false;
        slideshowHandler.removeCallbacksAndMessages(null);
        debounceHandler.removeCallbacksAndMessages(null);
        canSwitch = true;
        reloadOnStart = true;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        loadState.close();
        navigationState.close();
        if (pageController != null) pageController.close();
        clearImageRequests();
        releasePlayers();
        slideshowHandler.removeCallbacksAndMessages(null);
        debounceHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}