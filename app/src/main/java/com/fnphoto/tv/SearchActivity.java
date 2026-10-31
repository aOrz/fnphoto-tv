package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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
    private Object dataGeneration = new Object();
    private MediaListHolder.PageCallback detailCallback;

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
        LinearLayout rootLayout = new LinearLayout(this);
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
        tvStatus.setTextSize(13);
        tvStatus.setBackgroundResource(R.drawable.bg_menu_item);
        tvStatus.setPadding(dp(72), dp(14), dp(64), dp(6));
        tvStatus.setOnClickListener(v -> {
            if (pages.isWindowFull()) startNextWindow();
            else loadSearchPage(true);
        });
        tvStatus.setFocusable(false);
        tvStatus.setClickable(false);
        rootLayout.addView(tvStatus);

        // 结果网格
        rvResults = new RecyclerView(this);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
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
        rootLayout.addView(rvResults);

        setContentView(rootLayout);

        // 初始不弹软键盘：按 OK / 回车再唤起，避免遮挡画面
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            editSearch.setShowSoftInputOnFocus(false);
        }
        editSearch.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && editSearch.getWindowToken() != null) {
                    imm.hideSoftInputFromWindow(editSearch.getWindowToken(), 0);
                }
            }
        }, 400);
        editSearch.setOnClickListener(v -> showKeyboard());
        editSearch.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                boolean isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER;
                if (isOk && event.getAction() == KeyEvent.ACTION_UP) {
                    showKeyboard();
                    return true;
                }
                return false;
            }
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
    }

    private void showKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            editSearch.setShowSoftInputOnFocus(true);
        }
        editSearch.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(editSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
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
        searchResults.clear();
        mediaItems.clear();
        adapter.notifyDataSetChanged();
        updateSearchStatus();
    }

    private void loadSearchPage(boolean retry) {
        if (api == null || destroyed || !queryReady || currentQuery.isEmpty()) return;
        final GridPageState.Request page = pages.begin(retry);
        if (page == null) return;
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
                if (pages.fail(page)) updateSearchStatus();
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
        tvStatus.setFocusable(pages.hasError() || pages.isWindowFull());
        tvStatus.setClickable(pages.hasError() || pages.isWindowFull());
        if (currentQuery.isEmpty()) tvStatus.setText("输入关键词开始搜索");
        else if (pages.isInvalidPage()) tvStatus.setText("服务器分页响应超限或无效，未跳过记录；按确定重试或缩小关键词范围");
        else if (pages.hasError()) tvStatus.setText("加载失败，按确定重试（已加载 " + searchResults.size() + " 项）");
        else if (pages.isWindowFull()) tvStatus.setText("本批已扫描 " + pages.getWindowRows() + " 条、显示 "
                + searchResults.size() + " 项；按确定查看下一批（替换当前列表）");
        else if (pages.isOffsetLimit()) tvStatus.setText(pages.boundaryMessage());
        else if (pages.isStalled()) tvStatus.setText("服务器返回重复页，已停止加载（已加载 " + searchResults.size() + " 项）");
        else if (pages.isLoading()) tvStatus.setText(searchResults.isEmpty() ? "搜索中..." : "正在加载更多...");
        else tvStatus.setText("已加载 " + searchResults.size() + " 个结果" + (pages.hasMore() ? "，下移加载更多" : ""));
        if (detailCallback != null && !pages.isLoading()) {
            MediaListHolder.PageCallback callback = detailCallback;
            detailCallback = null;
            if (pages.hasError()) callback.onFailure();
            else callback.onLoaded(toMediaItems(), pages.hasMore());
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && editSearch.isFocused()) {
            if (tvStatus.isFocusable() && tvStatus.requestFocus()) return true;
            if (rvResults.requestFocus()) return true;
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
