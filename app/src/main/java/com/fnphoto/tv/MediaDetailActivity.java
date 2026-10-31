package com.fnphoto.tv;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
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
import android.widget.Button;
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
import com.fnphoto.tv.player.SharedAlbumHttpDataSourceFactory;
import com.fnphoto.tv.player.VideoLoadErrorPolicy;
import com.fnphoto.tv.player.VideoPlaybackFailure;
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
    private static final long VIDEO_WAIT_TIMEOUT_MS = 15000;
    private static final float ZOOM_STEP = 0.25f;
    private static final float MAX_SCALE = 5.0f;
    private static final float MIN_SCALE = 0.5f;

    private FrameLayout container;
    private ImageView imageView;
    private PlayerView playerView;
    private SimpleExoPlayer player;
    private android.media.MediaPlayer fallbackPlayer;
    private final VideoRemoteInput remoteInput = new VideoRemoteInput();
    private final VideoSeekState fallbackSeek = new VideoSeekState();
    private VideoControls videoControls;
    private View videoSurface;
    private boolean fallbackSeekable = true;
    private boolean videoReadyHintShown;
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideVideoHint = () -> {
        if (videoControls != null) videoControls.hideHint();
    };
    private final Runnable refreshVideoControls = new Runnable() {
        @Override public void run() {
            if (videoControls == null) return;
            updateVideoControls();
            controlsHandler.postDelayed(this, 500);
        }
    };
    private final DetailLoadState loadState = new DetailLoadState();
    private final DetailLoadState navigationState = new DetailLoadState();
    private final DetailPlaybackState fallbackPlayback = new DetailPlaybackState();
    private final DetailPreloadScope preloads = new DetailPreloadScope();
    private MediaPageController pageController;
    private Call<FnHttpApi.PhotoDetailResponse> detailCall;
    private boolean reloadOnStart;
    private Call<?> collectCall;
    private boolean collectPending;
    private DetailSessionStore sessionStore;
    private String sessionId;
    private String sessionOwner;
    private String mediaSourceEndpoint;
    private boolean restoredWithoutPaging;
    private static final class RetainedDetail {
        final MediaPageController controller;
        final boolean restoredWithoutPaging;
        final String owner;
        final String sourceEndpoint;
        RetainedDetail(MediaPageController controller, boolean restoredWithoutPaging, String owner, String sourceEndpoint) {
            this.controller = controller;
            this.restoredWithoutPaging = restoredWithoutPaging;
            this.owner = owner;
            this.sourceEndpoint = sourceEndpoint;
        }
    }
    private View infoOverlay;
    private TextView tvInfoContent;
    private TextView tvSlideshowIndicator;
    private ProgressBar loadingIndicator;

    private List<MediaItem> mediaList;
    private int currentIndex;
    private Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Handler slideshowHandler = new Handler(Looper.getMainLooper());
    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private Runnable playbackTimeout;
    private String videoErrorMessage;
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
        container.setFocusableInTouchMode(true);
        container.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(container);

        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("nas_url", "");
        token = prefs.getString("api_token", "");
        // Scope snapshots to the login session without writing credentials to the file.
        sessionOwner = activeSessionOwner();
        mediaSourceEndpoint = baseUrl;
        sessionStore = new DetailSessionStore(getCacheDir());
        sessionId = savedInstanceState != null ? savedInstanceState.getString("DETAIL_SESSION") : null;
        RetainedDetail retained = (RetainedDetail) getLastCustomNonConfigurationInstance();
        if (retained != null && sessionOwner.equals(retained.owner)) {
            pageController = retained.controller;
            restoredWithoutPaging = retained.restoredWithoutPaging;
            mediaSourceEndpoint = retained.sourceEndpoint;
        } else if (savedInstanceState != null) {
            DetailSessionStore.Snapshot snapshot = sessionStore.read(sessionId, sessionOwner, baseUrl);
            pageController = new MediaPageController(snapshot != null ? snapshot.items : null, null);
            restoredWithoutPaging = snapshot != null && snapshot.hadMore;
        } else {
            pageController = new MediaPageController(MediaListHolder.get(), MediaListHolder.getPageSource());
            MediaListHolder.clear();
        }
        if (retained != null && !sessionOwner.equals(retained.owner)) retained.controller.close();
        if (sessionId == null) sessionId = sessionStore.newId();
        mediaList = pageController.getItems();
        currentIndex = savedInstanceState != null ? savedInstanceState.getInt("DETAIL_INDEX", 0)
                : getIntent().getIntExtra("CURRENT_INDEX", 0);
        if (mediaList.isEmpty()) {
            Toast.makeText(this, "浏览会话已过期，请返回列表重新打开", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        currentIndex = Math.max(0, Math.min(currentIndex, mediaList.size() - 1));
        if (restoredWithoutPaging) Toast.makeText(this,
                "已恢复已加载照片；更多照片请返回列表加载", Toast.LENGTH_LONG).show();

        if (baseUrl != null && !baseUrl.isEmpty()) {
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl + "/")
                    .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                    .client(HttpClientProvider.getClient(this))
                    .build();
            api = retrofit.create(FnHttpApi.class);
        }

        String savedError = savedInstanceState != null
                ? savedInstanceState.getString("VIDEO_ERROR_MESSAGE") : null;
        if (savedError != null && "video".equals(mediaList.get(currentIndex).getType())) {
            currentVideoItem = mediaList.get(currentIndex);
            showVideoError(savedError);
        } else {
            showCurrentMedia();
        }
    }

    private void showCurrentMedia() {
        if (currentIndex < 0 || currentIndex >= mediaList.size()) return;

        MediaItem item = mediaList.get(currentIndex);
        slideshowHandler.removeCallbacks(slideshowRunnable);
        currentPhotoReady = false;
        Log.d(TAG, "Showing media: " + item.getTitle() + " type: " + item.getType());

        loadState.next();
        navigationState.next();
        clearImageRequests("photo".equals(item.getType()) ? photoUrl(item) : null);
        releasePlayers();
        isVideoPlaying = false;
        currentVideoItem = null;
        videoErrorMessage = null;
        currentScale = 1.0f;
        isZoomed = false;

        container.removeAllViews();
        // A photo reached from touch-operated video also needs a focus owner, so its first
        // LEFT/RIGHT reaches the existing browsing handler instead of just leaving touch mode.
        container.requestFocus();
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
        final String url = photoUrl(item);
        CachedImageLoader.loadPhotoIntoImageView(target, url, item.getThumbnailUrl(), token,
                new CachedImageLoader.LoadListener() {
                    @Override public void onPreviewLoaded() {
                        if (!loadState.isCurrent(generation) || imageView != target) return;
                        hideLoading();
                    }
                    @Override public void onLoaded() {
                        if (!loadState.isCurrent(generation) || imageView != target) return;
                        preloads.complete(url);
                        hideLoading();
                        currentPhotoReady = true;
                        schedulePhotoSlideshow();
                    }
                    @Override public void onLoadFailed() {
                        if (!loadState.isCurrent(generation) || imageView != target) return;
                        preloads.complete(url);
                        hideLoading();
                        currentPhotoReady = true;
                        schedulePhotoSlideshow();
                        Toast.makeText(MediaDetailActivity.this, "图片加载失败", Toast.LENGTH_SHORT).show();
                    }
                });
        target.setOnClickListener(v -> finish());
        preloadAdjacentPhotos();
    }

    private String photoUrl(MediaItem item) {
        String url = item.getMediaUrl();
        return url == null || url.isEmpty() ? item.getThumbnailUrl() : url;
    }

    private void preloadAdjacentPhotos() {
        // One bounded, lower-priority photo ahead. Never fetch a video as an image.
        if (mediaList == null || currentIndex + 1 >= mediaList.size()) return;
        MediaItem item = mediaList.get(currentIndex + 1);
        if (!DetailLoadState.shouldPreload(item)) return;
        String url = item.getMediaUrl();
        if (preloads.contains(url)) return;
        int width = Math.min(1920, getResources().getDisplayMetrics().widthPixels);
        int height = Math.min(1080, getResources().getDisplayMetrics().heightPixels);
        CachedImageLoader.RequestHandle handle = CachedImageLoader.preloadImage(this, url, token, width, height);
        preloads.setNext(url, handle::clear);
    }

    private void clearImageRequests() {
        clearImageRequests(null);
    }

    private void clearImageRequests(String retainedPhotoUrl) {
        if (retainedPhotoUrl == null) preloads.clear();
        else preloads.select(retainedPhotoUrl);
        if (imageView != null) {
            CachedImageLoader.clear(imageView);
            imageView = null;
        }
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
        playButton.setContentDescription(getString(R.string.video_play));
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
        TextView previewHelp = new TextView(this);
        previewHelp.setText(R.string.video_preview_help);
        previewHelp.setTextColor(Color.WHITE);
        previewHelp.setTextSize(18);
        previewHelp.setGravity(Gravity.CENTER);
        previewHelp.setPadding(24, 12, 24, 12);
        previewHelp.setBackgroundColor(0xCC202020);
        container.addView(previewHelp, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void releasePlayers() {
        clearVideoTimeout();
        controlsHandler.removeCallbacksAndMessages(null);
        videoControls = null;
        videoSurface = null;
        fallbackSeek.reset();
        videoReadyHintShown = false;
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
            old.setOnInfoListener(null);
            old.setOnSeekCompleteListener(null);
            old.release();
        }
        playerView = null;
    }

    private void startVideoPlayback(MediaItem item) {
        loadState.next();
        clearImageRequests();
        releasePlayers();
        isVideoPlaying = true;
        videoErrorMessage = null;
        currentVideoItem = item;
        hideInfoOverlay();

        container.removeAllViews();
        showLoading();
        if (slideshowActive) showSlideshowIndicator(true);

        String videoId = item.getId();
        SharedPreferences currentPrefs = getSharedPreferences("fn_photo_prefs", MODE_PRIVATE);
        String currentEndpoint = currentPrefs.getString("nas_url", baseUrl);
        token = currentPrefs.getString("api_token", "");
        String videoUrl = currentEndpoint + "/p/api/v1/stream/v/" + videoId;
        Log.d(TAG, "Starting video playback");
        setupExoPlayer(videoUrl);
    }

    private void setupExoPlayer(String videoUrl) {
        try {
            playerView = new PlayerView(this);
            // All input is routed before child dispatch; do not run two competing controllers.
            playerView.setUseController(false);
            playerView.setFocusable(false);
            playerView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            container.addView(playerView);

            player = new SimpleExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            com.google.android.exoplayer2.upstream.DataSource.Factory dataSourceFactory =
                    getIntent().getBooleanExtra("SHARED_ALBUM_READ_ONLY", false)
                            ? new SharedAlbumHttpDataSourceFactory(this, "ExoPlayer")
                            : new AuthenticatedHttpDataSourceFactory(this, "ExoPlayer");

            final SimpleExoPlayer requestedPlayer = player;
            final long generation = loadState.next();
            player.addListener(new Player.DefaultEventListener() {
                @Override
                public void onPlayerError(com.google.android.exoplayer2.ExoPlaybackException error) {
                    if (!loadState.isCurrent(generation) || player != requestedPlayer) return;
                    VideoPlaybackFailure failure = VideoPlaybackFailure.classify(error);
                    if (!failure.shouldFallback()) {
                        Log.w(TAG, "ExoPlayer playback failed; no platform retry", error);
                        showVideoError(failure.getUserMessage());
                        return;
                    }
                    Log.w(TAG, "ExoPlayer format/decoder failure; trying platform player", error);
                    boolean playIntent = requestedPlayer.getPlayWhenReady();
                    releasePlayers();
                    container.removeAllViews();
                    showLoading();
                    setupMediaPlayer(videoUrl, playIntent);
                }

                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    if (!loadState.isCurrent(generation) || player != requestedPlayer) return;
                    if (playbackState == Player.STATE_BUFFERING) armVideoTimeout(generation);
                    if (playbackState == Player.STATE_READY) {
                        clearVideoTimeout();
                        hideLoading();
                        showReadyVideoHint();
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        clearVideoTimeout();
                        if (slideshowActive) {
                            switchToNext();
                        }
                        // Match the platform player's completed state: rewinding from the end
                        // stays paused until an explicit Play/OK, instead of silently resuming.
                        if (!slideshowActive && player == requestedPlayer && playWhenReady) {
                            requestedPlayer.setPlayWhenReady(false);
                        }
                    }
                }
            });

            android.net.Uri uri = android.net.Uri.parse(videoUrl);
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(new VideoLoadErrorPolicy())
                    .createMediaSource(uri);
            armVideoTimeout(generation);
            showLoading();
            player.prepare(mediaSource);
            player.setPlayWhenReady(true);
            createVideoControls();
        } catch (Exception | LinkageError e) {
            VideoPlaybackFailure failure = VideoPlaybackFailure.classify(e);
            if (failure.shouldFallback()) {
                Log.w(TAG, "ExoPlayer initialization compatibility failure; trying platform player", e);
                boolean playIntent = player == null || player.getPlayWhenReady();
                releasePlayers();
                container.removeAllViews();
                showLoading();
                setupMediaPlayer(videoUrl, playIntent);
            } else {
                Log.w(TAG, "ExoPlayer initialization failed", e);
                showVideoError(failure.getUserMessage());
            }
        }
    }

    private void setupMediaPlayer(String videoUrl, boolean playIntent) {
        // Platform MediaPlayer does not expose per-hop redirect/header policy. Never bypass the
        // protected OkHttp transport for received media, even after a decoder/format failure.
        if (getIntent().getBooleanExtra("SHARED_ALBUM_READ_ONLY", false)) {
            showVideoError("当前设备无法播放此共享视频，请重试或切换其他媒体");
            return;
        }
        try {
            android.view.SurfaceView sv = new android.view.SurfaceView(this);
            sv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            container.addView(sv);

            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            fallbackPlayer = mp;
            fallbackPlayback.reset();
            fallbackPlayback.setPlayWhenReady(playIntent);
            fallbackSeekable = true;
            final long generation = loadState.next();
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("accesstoken", token != null ? token : "");
            android.net.Uri uri = android.net.Uri.parse(videoUrl);
            headers.put("authx", FnAuthUtils.generateAuthX(uri.getEncodedPath(), "GET", uri.getEncodedQuery()));
            mp.setDataSource(this, uri, headers);
            armVideoTimeout(generation);
            showLoading();

            sv.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    if (!loadState.isCurrent(generation) || fallbackPlayer != mp) return;
                    try {
                        mp.setDisplay(holder);
                        mp.prepareAsync();
                    } catch (RuntimeException failure) {
                        showVideoError(VideoPlaybackFailure.classify(failure).getUserMessage());
                    }
                }
                @Override
                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {}
                @Override
                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    // Defer view removal until SurfaceHolder has finished its callback.
                    playbackHandler.post(() -> {
                        if (loadState.isCurrent(generation) && fallbackPlayer == mp) {
                            showVideoError("视频画面已中断，请重试");
                        }
                    });
                }
            });

            mp.setOnPreparedListener(mp2 -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return;
                clearVideoTimeout();
                hideLoading();
                fallbackPlayback.prepared();
                showReadyVideoHint();
                try {
                    if (fallbackPlayback.shouldPlay()) mp2.start();
                } catch (RuntimeException failure) {
                    showVideoError(VideoPlaybackFailure.classify(failure).getUserMessage());
                }
            });
            mp.setOnErrorListener((mp2, what, extra) -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return true;
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                showVideoError(extra == android.media.MediaPlayer.MEDIA_ERROR_IO
                        ? "读取视频失败，请检查网络或服务器后重试"
                        : "设备无法播放此视频，可重试或切换其他媒体");
                return true;
            });
            mp.setOnInfoListener((mp2, what, extra) -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return true;
                if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    armVideoTimeout(generation);
                    showLoading();
                } else if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    clearVideoTimeout();
                    hideLoading();
                } else if (what == android.media.MediaPlayer.MEDIA_INFO_NOT_SEEKABLE) {
                    fallbackSeekable = false;
                }
                return false;
            });
            mp.setOnSeekCompleteListener(mp2 -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return;
                Long next = fallbackSeek.completed();
                if (next != null) {
                    try { mp2.seekTo((int) (long) next); }
                    catch (RuntimeException failure) {
                        showVideoError(VideoPlaybackFailure.classify(failure).getUserMessage());
                    }
                }
            });
            mp.setOnCompletionListener(mp2 -> {
                if (!loadState.isCurrent(generation) || fallbackPlayer != mp2) return;
                clearVideoTimeout();
                fallbackPlayback.setPlayWhenReady(false);
                if (slideshowActive) {
                    switchToNext();
                }
            });
            createVideoControls();
        } catch (Exception e) {
            Log.w(TAG, "MediaPlayer initialization failed", e);
            showVideoError(VideoPlaybackFailure.classify(e).getUserMessage());
        }
    }

    private void createVideoControls() {
        videoSurface = new View(this);
        // Keep focus after touch input too; otherwise ViewRoot consumes the first DPAD
        // merely leaving touch mode before our dispatchKeyEvent can see it.
        videoSurface.setFocusableInTouchMode(true);
        videoSurface.setContentDescription(getString(R.string.video_surface));
        videoSurface.setOnClickListener(v -> showVideoControls());
        container.addView(videoSurface, new FrameLayout.LayoutParams(-1, -1));
        videoControls = new VideoControls(this, container, new VideoControls.Listener() {
            @Override public void seekBy(long delta) { seekVideo(delta, false); }
            @Override public void seekTo(long position) { seekVideo(position, true); }
            @Override public void togglePlayback() { setVideoPlaying(null); }
            @Override public void navigate(boolean next) { navigateManually(next); }
            @Override public void close() { hideVideoControls(); }
        });
        videoSurface.requestFocus();
        controlsHandler.post(refreshVideoControls);
        showVideoHint(getString(R.string.video_remote_help));
    }

    private void showVideoControls() {
        if (videoControls == null) return;
        stopAutomaticAdvance();
        updateVideoControls();
        videoSurface.setFocusable(false);
        videoControls.show();
    }

    private void hideVideoControls() {
        if (videoControls == null) return;
        videoControls.hide();
        videoSurface.setFocusable(true);
        videoSurface.requestFocus();
        showVideoHint(getString(R.string.video_remote_help));
    }

    private void showVideoHint(String message) {
        if (videoControls == null) return;
        videoControls.hint(message);
        controlsHandler.removeCallbacks(hideVideoHint);
        controlsHandler.postDelayed(hideVideoHint, 6000);
    }

    private void showReadyVideoHint() {
        if (!videoReadyHintShown) {
            videoReadyHintShown = true;
            showVideoHint(getString(R.string.video_remote_help));
        }
    }

    private long videoDuration() {
        if (player != null) return player.getDuration();
        if (fallbackPlayer != null && fallbackPlayback.isPrepared()) return fallbackPlayer.getDuration();
        return -1;
    }

    private long videoPosition() {
        if (player != null) return player.getCurrentPosition();
        if (fallbackPlayer != null && fallbackPlayback.isPrepared()) {
            return fallbackSeek.displayedPosition(fallbackPlayer.getCurrentPosition());
        }
        return 0;
    }

    private boolean videoSeekable() {
        return videoDuration() > 0 && (player != null ? player.isCurrentWindowSeekable()
                : fallbackPlayer != null && fallbackPlayback.isPrepared() && fallbackSeekable);
    }

    private boolean videoPlayRequested() {
        return player != null ? player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED
                : fallbackPlayback.isPlayRequested();
    }

    private void updateVideoControls() {
        if (videoControls == null) return;
        try {
            videoControls.update(videoPosition(), videoDuration(), videoPlayRequested(), videoSeekable());
        } catch (IllegalStateException ignored) {
            // Platform callbacks may report a failure before the error screen releases the player.
        }
    }

    private void stopAutomaticAdvance() {
        if (slideshowActive) stopSlideshow();
    }

    private void navigateManually(boolean next) {
        stopAutomaticAdvance();
        if (next) switchToNext(); else switchToPrevious();
    }

    private void seekVideo(long value, boolean absolute) {
        stopAutomaticAdvance();
        try {
            if (!videoSeekable()) {
                showVideoHint(getString(R.string.video_seek_unavailable));
                if (videoControls != null) videoControls.announce(getString(R.string.video_seek_unavailable));
                return;
            }
            long duration = videoDuration();
            long target = absolute ? VideoSeekState.target(value, duration, 0)
                    : VideoSeekState.target(videoPosition(), duration, value);
            if (player != null) {
                player.seekTo(target);
            } else if (fallbackPlayer != null) {
                Long next = fallbackSeek.requestAbsolute(duration, target);
                if (next != null) fallbackPlayer.seekTo((int) (long) next);
            }
            String message = getString(R.string.video_seek_position,
                    VideoControls.time(target), VideoControls.time(duration));
            showVideoHint(message);
            // Announce the final requested position once a burst of repeats has settled.
            controlsHandler.removeCallbacks(announceSeek);
            seekAnnouncement = message;
            controlsHandler.postDelayed(announceSeek, 500);
            updateVideoControls();
        } catch (RuntimeException failure) {
            showVideoError(VideoPlaybackFailure.classify(failure).getUserMessage());
        }
    }

    private String seekAnnouncement;
    private final Runnable announceSeek = () -> {
        if (videoControls != null && seekAnnouncement != null) videoControls.announce(seekAnnouncement);
    };

    /** null toggles; dedicated PLAY/PAUSE are idempotent, including while preparing. */
    private void setVideoPlaying(Boolean requested) {
        stopAutomaticAdvance();
        boolean play = requested != null ? requested : !videoPlayRequested();
        try {
            if (player != null) {
                if (play && player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                player.setPlayWhenReady(play);
            } else if (fallbackPlayer != null) {
                fallbackPlayback.setPlayWhenReady(play);
                if (fallbackPlayback.isPrepared()) {
                    if (play) {
                        if (videoDuration() > 0 && videoPosition() >= videoDuration()) {
                            Long next = fallbackSeek.requestAbsolute(videoDuration(), 0);
                            if (next != null) fallbackPlayer.seekTo((int) (long) next);
                        }
                        fallbackPlayer.start();
                    } else fallbackPlayer.pause();
                }
            } else if (play && currentVideoItem != null) {
                startVideoPlayback(currentVideoItem);
            }
            showVideoHint(getString(play ? R.string.video_play : R.string.video_pause));
            updateVideoControls();
        } catch (RuntimeException failure) {
            showVideoError(VideoPlaybackFailure.classify(failure).getUserMessage());
        }
    }

    private void clearVideoTimeout() {
        if (playbackTimeout != null) playbackHandler.removeCallbacks(playbackTimeout);
        playbackTimeout = null;
    }

    private void armVideoTimeout(final long generation) {
        clearVideoTimeout();
        playbackTimeout = () -> {
            if (loadState.isCurrent(generation) && isVideoPlaying) {
                showVideoError("视频加载超时，请检查网络后重试");
            }
        };
        playbackHandler.postDelayed(playbackTimeout, VIDEO_WAIT_TIMEOUT_MS);
    }

    private void showVideoError(String message) {
        // Invalidate both player generations before releasing their pending network work.
        loadState.next();
        navigationState.next();
        clearImageRequests();
        releasePlayers();
        isVideoPlaying = false;
        if (slideshowActive) stopSlideshow();
        hideInfoOverlay();
        videoErrorMessage = message;
        container.removeAllViews();
        hideLoading();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(40, 32, 40, 32);
        panel.setBackgroundColor(0xEE202020);
        TextView explanation = new TextView(this);
        explanation.setText("视频播放失败\n\n" + message + "\n\n确定：重试  ·  左右：切换媒体  ·  返回：退出");
        explanation.setTextColor(Color.WHITE);
        explanation.setTextSize(20);
        explanation.setGravity(Gravity.CENTER);
        panel.addView(explanation);
        Button retry = new Button(this);
        retry.setText("重试");
        retry.setFocusable(true);
        retry.setOnClickListener(v -> retryVideo());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = 24;
        panel.addView(retry, retryParams);
        container.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        retry.requestFocus();
        Log.i(TAG, "Video error displayed; awaiting explicit retry");
    }

    private void retryVideo() {
        if (videoErrorMessage != null && currentVideoItem != null) {
            startVideoPlayback(currentVideoItem);
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
                    Toast.makeText(MediaDetailActivity.this, isVideoPlaying
                            ? "加载下一页失败，请在操作栏选择下一项重试" : "加载下一页失败，按右键重试", Toast.LENGTH_LONG).show();
                }
                @Override public void onPaused(String message) {
                    if (!loadState.isCurrent(generation) || !navigationState.isCurrent(navigation)) return;
                    hideLoading();
                    if (slideshowActive) stopSlideshow();
                    Toast.makeText(MediaDetailActivity.this,
                            message + (isVideoPlaying ? "；在操作栏选择下一项继续扫描或返回退出"
                                    : "；按右键继续扫描或返回退出"), Toast.LENGTH_LONG).show();
                }
            });
        } else {
            finishNavigation();
        }
    }

    @Override public void finish() {
        if (getIntent().getBooleanExtra("SHARED_ALBUM_READ_ONLY", false)
                && mediaList != null && currentIndex >= 0 && currentIndex < mediaList.size()) {
            Intent result = new Intent();
            result.putExtra("LAST_MEDIA_ID", mediaList.get(currentIndex).getId());
            setResult(RESULT_OK, result);
        }
        super.finish();
    }

    private void finishNavigation() {
            String boundary = pageController == null ? null : pageController.getBoundaryMessage();
            if (boundary != null) {
                if (slideshowActive) stopSlideshow();
                Toast.makeText(this, boundary, Toast.LENGTH_LONG).show();
                return;
            }
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
        if (getIntent().getBooleanExtra("SHARED_ALBUM_READ_ONLY", false)) {
            Toast.makeText(this, "共享相册暂不支持收藏操作", Toast.LENGTH_SHORT).show();
            return;
        }
        if (collectPending || api == null || mediaList == null || mediaList.isEmpty()) return;
        final MediaItem item = mediaList.get(currentIndex);
        final int photoId;
        try { photoId = Integer.parseInt(item.getId()); }
        catch (NumberFormatException e) { collectFailed("无法获取媒体ID"); return; }
        collectPending = true;
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/detail/" + photoId, "GET", null);
        Call<FnHttpApi.PhotoDetailResponse> read = api.getPhotoDetail(token, authx, photoId);
        collectCall = read;
        read.enqueue(new Callback<FnHttpApi.PhotoDetailResponse>() {
            @Override public void onResponse(Call<FnHttpApi.PhotoDetailResponse> call,
                    Response<FnHttpApi.PhotoDetailResponse> response) {
                if (call.isCanceled()) return;
                FnHttpApi.PhotoDetailResponse body = response.body();
                if (!response.isSuccessful() || body == null || body.code != 0 || body.data == null
                        || body.data.info == null || body.data.info.id != photoId) {
                    collectFailed("读取收藏状态失败，请重试");
                    return;
                }
                int target = CollectState.target(body.data.info.isCollect);
                if (target < 0) { collectFailed("收藏状态无效，请重试"); return; }
                writeCollect(photoId, target);
            }
            @Override public void onFailure(Call<FnHttpApi.PhotoDetailResponse> call, Throwable t) {
                if (!call.isCanceled()) collectFailed("读取收藏状态失败，请重试");
            }
        });
    }

    private void writeCollect(int photoId, final int target) {
        String body = "id=" + photoId + "&collect=" + target;
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/collect", "POST", body);
        Call<FnHttpApi.BaseResponse> write = api.toggleCollect(token, authx, photoId, target);
        collectCall = write;
        write.enqueue(new Callback<FnHttpApi.BaseResponse>() {
            @Override public void onResponse(Call<FnHttpApi.BaseResponse> call,
                    Response<FnHttpApi.BaseResponse> response) {
                if (call.isCanceled()) return;
                if (!response.isSuccessful() || response.body() == null || !response.body().isSuccessful()) {
                    collectFailed("更新收藏失败，请重试");
                    return;
                }
                collectPending = false;
                collectCall = null;
                SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", MODE_PRIVATE);
                prefs.edit().putLong("collect_revision", prefs.getLong("collect_revision", 0L) + 1L).apply();
                Toast.makeText(MediaDetailActivity.this,
                        target == 1 ? "已收藏 ❤️" : "已取消收藏 ♡", Toast.LENGTH_SHORT).show();
            }
            @Override public void onFailure(Call<FnHttpApi.BaseResponse> call, Throwable t) {
                if (!call.isCanceled()) collectFailed("更新收藏失败，请重试");
            }
        });
    }

    private void collectFailed(String message) {
        collectPending = false;
        collectCall = null;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String nullToStr(String str) {
        return str != null && !str.isEmpty() && !"null".equals(str) ? str : "-";
    }

    @Override
    @android.annotation.SuppressLint("RestrictedApi") // Old androidx.core marks the public Activity override restricted.
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }
        VideoRemoteInput.Mode mode = !isVideoPlaying ? VideoRemoteInput.Mode.BROWSE
                : videoControls != null && videoControls.isVisible()
                ? VideoRemoteInput.Mode.CONTROLS : VideoRemoteInput.Mode.VIDEO;
        // Accessibility/touch may move focus. Re-enter the row before letting directional keys through.
        if (mode == VideoRemoteInput.Mode.CONTROLS && !videoControls.hasFocus()) videoControls.focus();
        VideoRemoteInput.Action action = remoteInput.onKey(remoteKey(event.getKeyCode()),
                event.getAction() == KeyEvent.ACTION_DOWN, event.getRepeatCount(), event.getEventTime(),
                mode, loadState.current());
        switch (action) {
            case PASS: return super.dispatchKeyEvent(event);
            case SEEK_BACK: seekVideo(-10000, false); break;
            case SEEK_FORWARD: seekVideo(10000, false); break;
            case SHOW_CONTROLS: showVideoControls(); break;
            case HIDE_CONTROLS: hideVideoControls(); break;
            case TOGGLE_PLAY: setVideoPlaying(null); break;
            case PLAY: if (!infoVisible && currentVideoItem != null) setVideoPlaying(true); break;
            case PAUSE: setVideoPlaying(false); break;
            case PREVIOUS: if (!infoVisible) navigateManually(false); break;
            case NEXT: if (!infoVisible) navigateManually(true); break;
            case EXIT: finish(); break;
            case PHOTO_SLIDESHOW:
                if (!infoVisible && currentVideoItem == null && !slideshowActive) startSlideshow();
                break;
            default: break;
        }
        return true;
    }

    private static VideoRemoteInput.Key remoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return VideoRemoteInput.Key.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return VideoRemoteInput.Key.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return VideoRemoteInput.Key.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return VideoRemoteInput.Key.DOWN;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return VideoRemoteInput.Key.CENTER;
            case KeyEvent.KEYCODE_MENU: return VideoRemoteInput.Key.MENU;
            case KeyEvent.KEYCODE_BACK: return VideoRemoteInput.Key.BACK;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return VideoRemoteInput.Key.PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return VideoRemoteInput.Key.PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE: return VideoRemoteInput.Key.TOGGLE;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return VideoRemoteInput.Key.FAST_FORWARD;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return VideoRemoteInput.Key.REWIND;
            case KeyEvent.KEYCODE_MEDIA_NEXT: return VideoRemoteInput.Key.NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return VideoRemoteInput.Key.PREVIOUS;
            default: return VideoRemoteInput.Key.OTHER;
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) remoteInput.reset();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Playback keys have already been handled in dispatchKeyEvent, ahead of focused children.
        // Unhandled control-row directions must never fall through into photo navigation/zoom.
        if (isVideoPlaying) return super.onKeyDown(keyCode, event);
        if (videoErrorMessage != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (event.getRepeatCount() == 0) retryVideo();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    switchToPrevious();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    switchToNext();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
                default:
                    return true;
            }
        }
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
            case KeyEvent.KEYCODE_INFO:
                showInfoOverlay();
                return true;
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_F2:
                toggleCollect();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleOkKey() {
        if (currentVideoItem != null && !isVideoPlaying) {
            startVideoPlayback(currentVideoItem);
        } else if (!isVideoPlaying) {
            toggleSlideshow();
        }
    }

    private static String sessionOwner(String url, String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((url + "\n" + token).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(Locale.US, "%02x", value & 255));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String activeSessionOwner() {
        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", MODE_PRIVATE);
        String source = prefs.getString("saved_source", "");
        if (source.isEmpty()) source = prefs.getString("nas_url", baseUrl);
        return sessionOwner(source, prefs.getString("login_session", prefs.getString("api_token", "")));
    }

    @Override public Object onRetainCustomNonConfigurationInstance() {
        return pageController != null ? new RetainedDetail(pageController, restoredWithoutPaging, sessionOwner, mediaSourceEndpoint) : null;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (videoErrorMessage != null) outState.putString("VIDEO_ERROR_MESSAGE", videoErrorMessage);
        outState.putInt("DETAIL_INDEX", currentIndex);
        if (sessionStore != null && mediaList != null && !mediaList.isEmpty()) {
            try {
                if (!sessionOwner.equals(activeSessionOwner())) {
                    sessionStore.delete(sessionId);
                    super.onSaveInstanceState(outState);
                    return;
                }
                sessionStore.save(sessionId, sessionOwner, mediaList,
                        restoredWithoutPaging || pageController.hasMore()
                                || pageController.getBoundaryMessage() != null, mediaSourceEndpoint);
                outState.putString("DETAIL_SESSION", sessionId);
                outState.putInt("DETAIL_INDEX", currentIndex);
            } catch (java.io.IOException e) { Log.w(TAG, "Cannot save detail session", e); }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (reloadOnStart && mediaList != null && !mediaList.isEmpty()) {
            reloadOnStart = false;
            if (videoErrorMessage != null && "video".equals(mediaList.get(currentIndex).getType())) {
                currentVideoItem = mediaList.get(currentIndex);
                showVideoError(videoErrorMessage);
            } else {
                showCurrentMedia();
            }
        }
    }

    @Override
    protected void onStop() {
        if (collectCall != null) collectCall.cancel();
        collectCall = null;
        collectPending = false;
        loadState.next();
        navigationState.next();
        clearImageRequests();
        releasePlayers();
        playbackHandler.removeCallbacksAndMessages(null);
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
        if (pageController != null && !isChangingConfigurations()) pageController.close();
        if (isFinishing() && sessionStore != null) sessionStore.delete(sessionId);
        clearImageRequests();
        releasePlayers();
        playbackHandler.removeCallbacksAndMessages(null);
        slideshowHandler.removeCallbacksAndMessages(null);
        debounceHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
