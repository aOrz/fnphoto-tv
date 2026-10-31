package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fnphoto.tv.api.FnAuthUtils;
import com.fnphoto.tv.api.HttpClientProvider;
import com.fnphoto.tv.api.FnHttpApi;
import com.fnphoto.tv.cache.CachedImageLoader;
import com.fnphoto.tv.paging.GridPageState;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class SearchActivity extends FragmentActivity {
    private static final int SEARCH_DELAY = 500;
    private static final int PAGE_LIMIT = 100;

    private EditText editSearch;
    private RecyclerView rvResults;
    private TextView tvStatus;
    private TvStateView screenState;
    private SearchAdapter adapter;

    private FnHttpApi api;
    private String token;
    private String baseUrl;
    private List<FnHttpApi.GalleryPhoto> searchResults = new ArrayList<>();
    private final List<MediaItem> mediaItems = new ArrayList<>();
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private String currentQuery = "";
    private final GridPageState pages = new GridPageState(PAGE_LIMIT);
    private Call<FnHttpApi.GalleryListResponse> searchCall;
    private boolean destroyed;
    private boolean queryReady;
    private GridScreenState.Failure failure = GridScreenState.Failure.REQUEST;
    private Object dataGeneration = new Object();
    private MediaListHolder.PageCallback detailCallback;
    private final SearchKeyboardState keyboardState = new SearchKeyboardState();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("nas_url", "");
        token = prefs.getString("api_token", "");

        if (baseUrl.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "未登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(HttpClientProvider.getClient(this))
                .build();
        api = retrofit.create(FnHttpApi.class);

        initViews();

        String extraQuery = getIntent().getStringExtra("EXTRA_QUERY");
        if (extraQuery != null && !extraQuery.isEmpty()) {
            editSearch.setText(extraQuery);
            searchHandler.removeCallbacksAndMessages(null);
            queryReady = true;
            loadSearchPage(false);
        }
    }

    private void initViews() {
        LinearLayout rootLayout = new LinearLayout(this) {
            private final Rect visibleFrame = new Rect();
            private final ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener = this::observeKeyboard;

            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
                observeKeyboard();
            }

            @Override protected void onDetachedFromWindow() {
                ViewTreeObserver observer = getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(keyboardLayoutListener);
                super.onDetachedFromWindow();
            }

            // Old androidx.core annotates the public Activity/Window.Callback override restricted.
            // This calls that public framework entry point, not a private AndroidX helper.
            @android.annotation.SuppressLint("RestrictedApi")
            @Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
                observeKeyboard();
                if (isRemoteKey(event.getKeyCode()) && keyboardState.routeKey(
                        event.getDeviceId(), event.getKeyCode(), event.getDownTime(),
                        event.getAction() == KeyEvent.ACTION_DOWN,
                        event.getAction() == KeyEvent.ACTION_UP)) {
                    // A hidden TV IME can still consume keys (or return them after a timeout).
                    // Browse keys must reach the focused View before that asynchronous hop.
                    boolean handled = SearchActivity.this.dispatchKeyEvent(event);
                    int direction = focusDirection(event.getKeyCode());
                    if (!handled && direction != 0 && event.getAction() == KeyEvent.ACTION_DOWN) {
                        View focused = findFocus();
                        View next = focused != null ? focused.focusSearch(direction) : null;
                        if (next != null) next.requestFocus(direction);
                    }
                    return true; // Keep both down/up on the same browse path, even at an edge.
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    keyboardState.dismiss();
                    disableAutomaticKeyboard();
                }
                return super.dispatchKeyEventPreIme(event);
            }

            private void observeKeyboard() {
                getWindowVisibleDisplayFrame(visibleFrame);
                // This Activity is full-screen. A docked IME obscures much more than system bars.
                // Explicit keyboard ownership also protects floating/non-resizing IMEs.
                keyboardState.observeVisibility(getRootView().getHeight() - visibleFrame.bottom > dp(100));
            }

            @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (!hasWindowFocus) keyboardState.clearKeys();
            }

            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                observeKeyboard();
            }
        };
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setBackgroundColor(Color.parseColor("#0B0C0F"));

        // 顶部标题区
        TextView tvTitle = new TextView(this);
        tvTitle.setText("搜索");
        tvTitle.setTextColor(Color.parseColor("#F2F4F8"));
        tvTitle.setTextSize(34);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            tvTitle.setLetterSpacing(0.01f);
        }
        tvTitle.setPadding(dp(64), dp(40), dp(64), 0);
        rootLayout.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("按文件名在相册中查找");
        tvSubtitle.setTextColor(Color.parseColor("#A8AFBF"));
        tvSubtitle.setTextSize(14);
        tvSubtitle.setPadding(dp(64), dp(8), dp(64), 0);
        rootLayout.addView(tvSubtitle);

        // 胶囊搜索框
        editSearch = new EditText(this);
        editSearch.setHint("输入文件名，回车确认");
        editSearch.setTextColor(Color.parseColor("#F2F4F8"));
        editSearch.setHintTextColor(Color.parseColor("#6E7686"));
        editSearch.setTextSize(17);
        editSearch.setSingleLine(true);
        // Set before attaching the editor: initial focus must not queue a delayed IME show.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            editSearch.setShowSoftInputOnFocus(false);
        }
        editSearch.setBackgroundResource(R.drawable.bg_search_field);
        editSearch.setPadding(dp(24), 0, dp(24), 0);
        editSearch.setIncludeFontPadding(false);
        editSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        int searchIcon = dp(16);
        editSearch.setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.ic_menu_search, 0, 0, 0);
        editSearch.setCompoundDrawablePadding(dp(12));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        inputLp.topMargin = dp(26);
        inputLp.leftMargin = dp(64);
        inputLp.rightMargin = dp(64);
        editSearch.setLayoutParams(inputLp);
        rootLayout.addView(editSearch);

        // 状态行
        tvStatus = new TextView(this);
        tvStatus.setText("输入关键词开始搜索");
        tvStatus.setTextColor(Color.parseColor("#A8AFBF"));
        tvStatus.setTextSize(18);
        tvStatus.setBackgroundResource(R.drawable.bg_menu_item);
        tvStatus.setMinHeight(dp(52));
        tvStatus.setPadding(dp(64), dp(12), dp(64), dp(12));
        tvStatus.setOnClickListener(v -> {
            if (pages.isWindowFull()) startNextWindow();
            else loadSearchPage(true);
        });
        tvStatus.setFocusable(false);
        tvStatus.setClickable(false);
        rootLayout.addView(tvStatus);

        // 结果网格
        FrameLayout content = new FrameLayout(this);
        rootLayout.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rvResults = new RecyclerView(this);
        FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rvResults.setLayoutParams(gridLp);
        rvResults.setClipToPadding(false);
        rvResults.setPadding(dp(48), dp(8), dp(48), dp(40));
        rvResults.setLayoutManager(new AdaptiveGridLayoutManager(this));

        adapter = new SearchAdapter();
        rvResults.setAdapter(adapter);
        rvResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                GridLayoutManager manager = (GridLayoutManager) view.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= searchResults.size() - 12) {
                    loadSearchPage(false);
                }
            }
        });
        content.addView(rvResults);
        screenState = new TvStateView(this);
        content.addView(screenState, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);

        // 初始不弹软键盘：按 OK / 回车再唤起，避免遮挡画面
        editSearch.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboardState.shouldHideInitialKeyboard() && imm != null && editSearch.getWindowToken() != null) {
                    imm.hideSoftInputFromWindow(editSearch.getWindowToken(), 0);
                }
            }
        }, 400);
        editSearch.setOnClickListener(v -> showKeyboard());
        editSearch.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // EditText consumes DPAD_DOWN for cursor movement before Activity.onKeyDown.
                // Route the user's explicit focus move here, without focusing on async updates.
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && keyboardState.routesRemoteKeys()) {
                    return focusSearchContent();
                }
                boolean isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER;
                if (isOk) {
                    // Keep the opening gesture together: forwarding its DOWN elsewhere while
                    // consuming only UP leaves the editor/IME with an unmatched gesture.
                    SearchKeyboardState.OpenKey result = keyboardState.handleKeyboardOpenKey(
                            event.getDeviceId(), keyCode, event.getDownTime(),
                            event.getAction() == KeyEvent.ACTION_DOWN,
                            event.getAction() == KeyEvent.ACTION_UP, event.isCanceled());
                    if (result == SearchKeyboardState.OpenKey.OPEN) showKeyboard();
                    return result != SearchKeyboardState.OpenKey.PASS;
                }
                return false;
            }
        });

        editSearch.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_DONE) return false;
            hideKeyboard();
            return true;
        });

        // Auto-focus search input
        editSearch.requestFocus();

        // Search on text change with debounce
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchHandler.removeCallbacksAndMessages(null);
                String query = s.toString().trim();
                // Invalidate immediately, not after debounce: an old response must not repaint.
                resetSearch(query);
                if (!query.isEmpty()) {
                    searchHandler.postDelayed(() -> { queryReady = true; loadSearchPage(false); }, SEARCH_DELAY);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        updateSearchStatus();
    }

    private void showKeyboard() {
        final int request = keyboardState.request();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            editSearch.setShowSoftInputOnFocus(true);
        }
        editSearch.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
        // Center/click is an explicit user request. IMPLICIT may be ignored after dismissal;
        // flags=0 requests normally, without FORCED's cross-window persistence.
        boolean accepted = imm != null && imm.showSoftInput(editSearch,
                0,
                new ResultReceiver(new Handler(Looper.getMainLooper())) {
                    @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == android.view.inputmethod.InputMethodManager.RESULT_UNCHANGED_HIDDEN
                                || resultCode == android.view.inputmethod.InputMethodManager.RESULT_HIDDEN) {
                            if (keyboardState.rejectRequest(request)) disableAutomaticKeyboard();
                        }
                    }
                });
        if (!accepted && keyboardState.rejectRequest(request)) disableAutomaticKeyboard();
    }

    private void hideKeyboard() {
        keyboardState.dismiss();
        disableAutomaticKeyboard();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(editSearch.getWindowToken(), 0);
    }

    private void disableAutomaticKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            editSearch.setShowSoftInputOnFocus(false);
        }
    }

    private static int focusDirection(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }

    private static boolean isRemoteKey(int keyCode) {
        return focusDirection(keyCode) != 0 || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK;
    }

    private void resetSearch(String query) {
        dataGeneration = new Object();
        pages.reset();
        if (searchCall != null) searchCall.cancel();
        if (detailCallback != null) {
            MediaListHolder.PageCallback callback = detailCallback;
            detailCallback = null;
            callback.onFailure();
        }
        currentQuery = query;
        queryReady = false;
        failure = GridScreenState.Failure.REQUEST;
        searchResults.clear();
        mediaItems.clear();
        adapter.notifyDataSetChanged();
        updateSearchStatus();
    }

    private void loadSearchPage(boolean retry) {
        if (api == null || destroyed || !queryReady || currentQuery.isEmpty()) return;
        final GridPageState.Request page = pages.begin(retry);
        if (page == null) return;
        failure = GridScreenState.Failure.REQUEST;
        updateSearchStatus();
        String params = "keyword=" + currentQuery + "&limit=" + page.limit + "&offset=" + page.offset;
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/search", "GET", params);
        searchCall = api.searchPhotos(token, authx, currentQuery, page.limit, page.offset);
        searchCall.enqueue(new Callback<FnHttpApi.GalleryListResponse>() {
            @Override public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                    Response<FnHttpApi.GalleryListResponse> response) {
                if (destroyed || !pages.isCurrent(page)) return;
                FnHttpApi.GalleryListResponse result = response.body();
                if (!response.isSuccessful() || result == null || result.code != 0
                        || result.data == null || result.data.list == null) {
                    failure = GridScreenState.Failure.fromHttpStatus(response.code());
                    pages.fail(page);
                    updateSearchStatus();
                    return;
                }
                List<FnHttpApi.GalleryPhoto> batch = result.data.list;
                if (batch.size() > page.limit) {
                    pages.reject(page);
                    updateSearchStatus();
                    return;
                }
                List<Integer> ids = new ArrayList<>();
                for (FnHttpApi.GalleryPhoto photo : batch) ids.add(photo == null ? null : photo.id);
                boolean[] additions = pages.accept(page, ids, result.data.hasNext);
                if (additions == null) { updateSearchStatus(); return; }
                int start = searchResults.size();
                for (int i = 0; i < batch.size(); i++) {
                    if (additions[i]) {
                        FnHttpApi.GalleryPhoto photo = batch.get(i);
                        searchResults.add(photo);
                        mediaItems.add(toMediaItem(photo, baseUrl));
                    }
                }
                if (searchResults.size() > start) adapter.notifyItemRangeInserted(start, searchResults.size() - start);
                updateSearchStatus();
            }
            @Override public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable t) {
                if (destroyed || call.isCanceled()) return;
                if (pages.fail(page)) {
                    failure = GridScreenState.Failure.REQUEST;
                    updateSearchStatus();
                }
            }
        });
    }

    private void startNextWindow() {
        if (!pages.nextWindow()) return;
        dataGeneration = new Object();
        MediaListHolder.clear();
        searchResults.clear();
        mediaItems.clear();
        adapter.notifyDataSetChanged();
        rvResults.scrollToPosition(0);
        loadSearchPage(false);
    }

    private void updateSearchStatus() {
        boolean hasResults = !searchResults.isEmpty();
        tvStatus.setVisibility(hasResults ? View.VISIBLE : View.GONE);
        rvResults.setVisibility(hasResults ? View.VISIBLE : View.GONE);
        tvStatus.setFocusable(pages.hasError() || pages.isWindowFull());
        tvStatus.setClickable(pages.hasError() || pages.isWindowFull());
        if (currentQuery.isEmpty()) tvStatus.setText("输入关键词开始搜索");
        else if (pages.isInvalidPage()) tvStatus.setText("服务器分页响应超限或无效，未跳过记录；按确定重试或缩小关键词范围");
        else if (pages.hasError()) tvStatus.setText(failureHeading() + "，按确定重试（保留已加载 " + searchResults.size() + " 项）");
        else if (pages.isWindowFull()) tvStatus.setText("本批已扫描 " + pages.getWindowRows() + " 条、显示 "
                + searchResults.size() + " 项；按确定查看下一批（替换当前列表）");
        else if (pages.isOffsetLimit()) tvStatus.setText(pages.boundaryMessage());
        else if (pages.isStalled()) tvStatus.setText("服务器返回重复页，已停止加载（已加载 " + searchResults.size() + " 项）");
        else if (pages.isLoading()) tvStatus.setText(searchResults.isEmpty() ? "搜索中..." : "正在加载更多...");
        else tvStatus.setText("已加载 " + searchResults.size() + " 个结果" + (pages.hasMore() ? "，下移加载更多" : ""));
        if (hasResults) screenState.setVisibility(View.GONE);
        else showSearchState(GridScreenState.search(currentQuery, queryReady, searchResults.size(), pages, failure));
        if (detailCallback != null && !pages.isLoading()) {
            MediaListHolder.PageCallback callback = detailCallback;
            detailCallback = null;
            if (pages.hasError()) callback.onFailure();
            else callback.onLoaded(toMediaItems(), pages.hasMore());
        }
    }

    private String failureHeading() {
        if (failure == GridScreenState.Failure.AUTHENTICATION) return "登录已失效";
        if (failure == GridScreenState.Failure.PERMISSION) return "没有访问权限";
        return "搜索失败";
    }

    private void showSearchState(GridScreenState.Kind state) {
        switch (state) {
            case INPUT:
                screenState.bind("搜索照片和视频", "输入文件名关键词，查找 NAS 中的照片和视频。",
                        "输入关键词", this::showKeyboard);
                break;
            case LOADING:
                screenState.bind("正在搜索", "正在查找匹配的照片和视频，请稍候。", null, null);
                screenState.setLoading(true);
                break;
            case NO_RESULTS:
                screenState.bind("未找到搜索结果", "没有匹配此关键词的照片或视频。试试更短的文件名或其他关键词。",
                        "修改关键词", this::showKeyboard);
                break;
            case EMPTY_WINDOW:
                screenState.bind("本批没有更多结果", "此前已有匹配结果，当前批次没有更多照片或视频。可修改关键词继续查找。",
                        "修改关键词", this::showKeyboard);
                break;
            case AUTHENTICATION:
                screenState.bind("登录已失效", "当前登录无法继续访问。可重试；若仍失败，请返回并重新登录。",
                        "重试", () -> loadSearchPage(true));
                break;
            case PERMISSION:
                screenState.bind("没有访问权限", "服务器拒绝访问搜索结果。请检查当前账号的相册权限后重试。",
                        "重试", () -> loadSearchPage(true));
                break;
            case BOUNDARY:
                screenState.bind("搜索结果不完整", pages.boundaryMessage(),
                        pages.isWindowFull() ? "查看下一批" : "修改关键词",
                        pages.isWindowFull() ? this::startNextWindow : this::showKeyboard);
                break;
            default:
                screenState.bind("搜索失败", pages.isInvalidPage()
                                ? "服务器返回的分页数据无效，未跳过记录。请重试，或修改关键词缩小范围。"
                                : "未能取得搜索结果。请检查网络和 NAS 服务后重试。",
                        "重新加载", () -> loadSearchPage(true));
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        keyboardState.dismiss();
        keyboardState.clearKeys();
        pages.close();
        searchHandler.removeCallbacksAndMessages(null);
        if (searchCall != null) searchCall.cancel();
        if (detailCallback != null) {
            MediaListHolder.PageCallback callback = detailCallback;
            detailCallback = null;
            callback.onFailure();
        }
        if (rvResults != null) rvResults.setAdapter(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean focusSearchContent() {
        if (screenState.focusAction()) return true;
        if (tvStatus.isShown() && tvStatus.isFocusable() && tvStatus.requestFocus()) return true;
        return rvResults.isShown() && rvResults.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!keyboardState.routesRemoteKeys()) hideKeyboard();
            else finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ========== Search Results Adapter ==========
    private class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.ViewHolder> {
        SearchAdapter() { setHasStableIds(true); }
        @Override public long getItemId(int position) { return searchResults.get(position).id; }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_photo_grid, parent, false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            FnHttpApi.GalleryPhoto photo = searchResults.get(position);
            holder.bind(photo, position);
            if (position >= searchResults.size() - 12) rvResults.post(() -> loadSearchPage(false));
        }

        @Override public void onViewRecycled(ViewHolder holder) {
            CachedImageLoader.clear(holder.ivPhoto);
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return searchResults.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivPhoto;
            View videoIndicator;

            ViewHolder(View itemView) {
                super(itemView);
                ivPhoto = itemView.findViewById(R.id.iv_photo);
                videoIndicator = itemView.findViewById(R.id.v_video_indicator);

                itemView.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        openMediaDetail(pos);
                    }
                });
            }

            void bind(FnHttpApi.GalleryPhoto photo, int position) {
                CachedImageLoader.loadIntoImageView(ivPhoto, mediaItems.get(position).getThumbnailUrl(), token);
                videoIndicator.setVisibility("video".equals(photo.category) ? View.VISIBLE : View.GONE);
            }
        }
    }

    private List<MediaItem> toMediaItems() {
        return new ArrayList<>(mediaItems);
    }

    private static MediaItem toMediaItem(FnHttpApi.GalleryPhoto photo, String baseUrl) {
        String thumbUrl = null;
        String mediaUrl = "video".equals(photo.category) ? baseUrl + "/p/api/v1/stream/v/" + photo.id : null;
        if (photo.additional != null && photo.additional.thumbnail != null) {
            FnHttpApi.GalleryThumbnail thumb = photo.additional.thumbnail;
            thumbUrl = resolveUrl(baseUrl, thumb.sUrl != null ? thumb.sUrl : thumb.mUrl);
            if (!"video".equals(photo.category)) {
                mediaUrl = resolveUrl(baseUrl, thumb.originalUrl != null ? thumb.originalUrl : thumb.mUrl);
            }
        }
        return new MediaItem(String.valueOf(photo.id), photo.fileName, photo.category, thumbUrl, mediaUrl);
    }

    private static String resolveUrl(String baseUrl, String url) {
        return url == null || url.isEmpty() ? null : url.startsWith("http") ? url : baseUrl + url;
    }

    private void openMediaDetail(int position) {
        final Object generation = dataGeneration;
        MediaListHolder.set(toMediaItems(), new MediaListHolder.PageSource() {
            private final GridPageState.Delivery delivery = new GridPageState.Delivery(mediaItems.size());
            @Override public boolean hasMore() {
                return !(destroyed || generation != dataGeneration) && delivery.hasMore(mediaItems.size(), pages.hasMore());
            }
            @Override public String getBoundaryMessage() {
                return destroyed || generation != dataGeneration ? "搜索条件已变更，请返回列表重新选择"
                        : pages.boundaryMessage();
            }
            @Override public void loadMore(MediaListHolder.PageCallback callback) {
                if (destroyed || generation != dataGeneration) { callback.onFailure(); return; }
                if (delivery.hasBuffered(mediaItems.size()) || !pages.hasMore()) {
                    delivery.delivered(mediaItems.size());
                    callback.onLoaded(toMediaItems(), pages.hasMore());
                    return;
                }
                if (detailCallback != null) { callback.onFailure(); return; }
                detailCallback = new MediaListHolder.PageCallback() {
                    @Override public void onLoaded(List<MediaItem> items, boolean hasMore) {
                        delivery.delivered(items.size());
                        callback.onLoaded(items, hasMore);
                    }
                    @Override public void onFailure() { callback.onFailure(); }
                };
                if (!pages.isLoading()) loadSearchPage(true);
            }
        });
        Intent intent = new Intent(this, MediaDetailActivity.class);
        intent.putExtra("CURRENT_INDEX", position);
        startActivity(intent);
    }
}
