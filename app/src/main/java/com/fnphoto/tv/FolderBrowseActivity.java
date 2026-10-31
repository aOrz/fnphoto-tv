package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fnphoto.tv.api.FnAuthUtils;
import com.fnphoto.tv.api.FnHttpApi;
import com.fnphoto.tv.api.HttpClientProvider;
import com.fnphoto.tv.cache.CachedImageLoader;
import com.fnphoto.tv.paging.GridPageState;
import com.fnphoto.tv.paging.FolderNavigationState;
import com.fnphoto.tv.paging.MetadataLimits;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 文件夹浏览Activity
 * 支持混合视图：文件夹列表 + 照片墙
 */
public class FolderBrowseActivity extends FragmentActivity {
    private static final int GRID_SPAN_COUNT = 6; // 照片墙每行显示6个
    private static final int FOLDER_GRID_SPAN_COUNT = 4; // 文件夹每行显示4个
    private static final int PAGE_LIMIT = 100; // 每页加载数量

    private TextView tvTitle;
    private RecyclerView rvFolders;
    private RecyclerView rvPhotos;
    private LinearLayout layoutContent;
    private TextView tvLoading;

    private FnHttpApi api;
    private String token;
    private String baseUrl;
    private String currentFolderPath;
    private String currentFolderName;
    private FolderNavigationState folderNavigation;

    private FolderAdapter folderAdapter;
    private PhotoGridAdapter photoAdapter;

    private List<FnHttpApi.SubFolderItem> folderList = new ArrayList<>();
    private List<FnHttpApi.FolderMediaItem> photoList = new ArrayList<>();
    private final List<MediaItem> mediaItems = new ArrayList<>();

    private final GridPageState pages = new GridPageState(PAGE_LIMIT);
    private Call<FnHttpApi.FolderFileListResponse> mediaCall;
    private Call<FnHttpApi.SubFolderListResponse> folderCall;
    private boolean foldersLoading;
    private boolean foldersFailed;
    private boolean foldersTruncated;
    private Object dataGeneration = new Object();
    private boolean destroyed;
    private MediaListHolder.PageCallback detailCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置全屏模式
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // 获取传递的参数
        currentFolderPath = getIntent().getStringExtra("FOLDER_PATH");
        currentFolderName = getIntent().getStringExtra("FOLDER_NAME");

        try {
            folderNavigation = savedInstanceState != null && savedInstanceState.containsKey("folder_paths")
                    ? FolderNavigationState.restore(savedInstanceState.getStringArrayList("folder_paths"),
                            savedInstanceState.getStringArrayList("folder_names"))
                    : new FolderNavigationState(currentFolderPath, currentFolderName);
            currentFolderPath = folderNavigation.getPath();
            currentFolderName = folderNavigation.getName();
        } catch (IllegalArgumentException invalidPath) {
            Toast.makeText(this, "文件夹路径无效或超出安全长度", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 初始化视图
        initViews();

        // 初始化API
        initApi();

        // 加载文件夹内容
        loadFolderContent();
    }

    private void initViews() {
        // 创建根布局
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setBackgroundColor(Color.parseColor("#0B0C0F"));

        // 标题栏
        tvTitle = new TextView(this);
        tvTitle.setText(currentFolderName != null ? currentFolderName : "文件夹");
        tvTitle.setTextColor(Color.parseColor("#F2F4F8"));
        tvTitle.setTextSize(34);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            tvTitle.setLetterSpacing(0.01f);
        }
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvTitle.setPadding(dp(64), dp(40), dp(64), 0);
        rootLayout.addView(tvTitle);

        // 加载提示
        tvLoading = new TextView(this);
        tvLoading.setText("加载中...");
        tvLoading.setTextColor(Color.parseColor("#A8AFBF"));
        tvLoading.setTextSize(14);
        tvLoading.setPadding(dp(64), dp(14), dp(64), dp(6));
        tvLoading.setOnClickListener(v -> {
            if (foldersFailed) loadSubFolders();
            if (pages.isWindowFull()) startNextWindow();
            else loadMediaFiles(true);
        });
        rootLayout.addView(tvLoading);

        // 内容区域
        layoutContent = new LinearLayout(this);
        layoutContent.setOrientation(LinearLayout.VERTICAL);
        layoutContent.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 文件夹列表区域
        TextView tvFoldersTitle = new TextView(this);
        tvFoldersTitle.setText("子文件夹");
        tvFoldersTitle.setTextColor(Color.parseColor("#6E7686"));
        tvFoldersTitle.setTextSize(12);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            tvFoldersTitle.setLetterSpacing(0.14f);
        }
        tvFoldersTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvFoldersTitle.setPadding(dp(70), dp(24), dp(64), dp(8));
        tvFoldersTitle.setVisibility(View.GONE);
        tvFoldersTitle.setTag("folders_title");
        layoutContent.addView(tvFoldersTitle);

        rvFolders = new RecyclerView(this);
        // A large child-directory list must not expand all rows or push photos off screen.
        rvFolders.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132)));
        rvFolders.setPadding(dp(56), dp(4), dp(56), dp(4));
        rvFolders.setClipToPadding(false);
        rvFolders.setVisibility(View.GONE);
        folderAdapter = new FolderAdapter();
        rvFolders.setAdapter(folderAdapter);
        rvFolders.setLayoutManager(new GridLayoutManager(this, FOLDER_GRID_SPAN_COUNT));
        layoutContent.addView(rvFolders);

        // 照片墙区域
        TextView tvPhotosTitle = new TextView(this);
        tvPhotosTitle.setText("照片和视频");
        tvPhotosTitle.setTextColor(Color.parseColor("#6E7686"));
        tvPhotosTitle.setTextSize(12);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            tvPhotosTitle.setLetterSpacing(0.14f);
        }
        tvPhotosTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvPhotosTitle.setPadding(dp(70), dp(24), dp(64), dp(8));
        tvPhotosTitle.setVisibility(View.GONE);
        tvPhotosTitle.setTag("photos_title");
        layoutContent.addView(tvPhotosTitle);

        rvPhotos = new RecyclerView(this);
        rvPhotos.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rvPhotos.setPadding(dp(56), dp(4), dp(56), dp(32));
        rvPhotos.setClipToPadding(false);
        rvPhotos.setVisibility(View.GONE);
        photoAdapter = new PhotoGridAdapter();
        rvPhotos.setAdapter(photoAdapter);
        rvPhotos.setLayoutManager(new GridLayoutManager(this, GRID_SPAN_COUNT));
        rvPhotos.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                GridLayoutManager manager = (GridLayoutManager) view.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= photoList.size() - 12) {
                    loadMediaFiles(false);
                }
            }
        });
        layoutContent.addView(rvPhotos);

        rootLayout.addView(layoutContent);
        setContentView(rootLayout);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void initApi() {
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
    }

    private void loadFolderContent() {
        if (api == null || destroyed) return;
        loadSubFolders();
        loadMediaFiles(false);
    }

    private void loadSubFolders() {
        if (api == null || destroyed || foldersLoading) return;
        foldersLoading = true;
        foldersFailed = false;
        updateLoadingStatus();
        String params = "desc=false&orderBy=2&folderPath=" + currentFolderPath;
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/folder_view/getFolderList", "GET", params);
        folderCall = api.getSubFolders(token, authx, currentFolderPath, false, 2);
        folderCall.enqueue(new Callback<FnHttpApi.SubFolderListResponse>() {
            @Override public void onResponse(Call<FnHttpApi.SubFolderListResponse> call,
                    Response<FnHttpApi.SubFolderListResponse> response) {
                if (destroyed || call != folderCall) return;
                foldersLoading = false;
                FnHttpApi.SubFolderListResponse result = response.body();
                if (response.isSuccessful() && result != null && result.code == 0
                        && result.data != null && result.data.list != null) {
                    int retainedFolders = Math.min(result.data.list.size(), MetadataLimits.MAX_ITEMS);
                    for (int i = 0; i < retainedFolders; i++) {
                        if (result.data.list.get(i) == null) {
                            foldersFailed = true;
                            updateLoadingStatus();
                            return;
                        }
                    }
                    folderList.clear();
                    foldersTruncated = result.data.list.size() > MetadataLimits.MAX_ITEMS;
                    folderList.addAll(result.data.list.subList(0,
                            retainedFolders));
                    findViewWithTag("folders_title").setVisibility(folderList.isEmpty() ? View.GONE : View.VISIBLE);
                    rvFolders.setVisibility(folderList.isEmpty() ? View.GONE : View.VISIBLE);
                    folderAdapter.notifyDataSetChanged();
                } else {
                    foldersFailed = true;
                }
                updateLoadingStatus();
            }
            @Override public void onFailure(Call<FnHttpApi.SubFolderListResponse> call, Throwable t) {
                if (destroyed || call != folderCall || call.isCanceled()) return;
                foldersLoading = false;
                foldersFailed = true;
                updateLoadingStatus();
            }
        });
    }

    private void loadMediaFiles(boolean retry) {
        if (api == null || destroyed) return;
        final GridPageState.Request page = pages.begin(retry);
        if (page == null) return;
        updateLoadingStatus();
        String params = "folderPath=" + currentFolderPath + "&desc=false&orderBy=2&limit="
                + page.limit + "&offset=" + page.offset;
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/folder_view/getFileList", "GET", params);
        mediaCall = api.getFolderFiles(token, authx, currentFolderPath, false, 2, page.limit, page.offset);
        mediaCall.enqueue(new Callback<FnHttpApi.FolderFileListResponse>() {
            @Override public void onResponse(Call<FnHttpApi.FolderFileListResponse> call,
                    Response<FnHttpApi.FolderFileListResponse> response) {
                if (destroyed || !pages.isCurrent(page)) return;
                FnHttpApi.FolderFileListResponse result = response.body();
                if (!response.isSuccessful() || result == null || result.code != 0
                        || result.data == null || result.data.list == null) {
                    pages.fail(page);
                    updateLoadingStatus();
                    return;
                }
                List<FnHttpApi.FolderMediaItem> batch = result.data.list;
                if (batch.size() > page.limit) {
                    pages.reject(page);
                    updateLoadingStatus();
                    return;
                }
                List<Integer> ids = new ArrayList<>();
                for (FnHttpApi.FolderMediaItem item : batch) ids.add(item == null ? null : item.id);
                // total==0 may mean the field was omitted by older servers.
                Boolean hasNext = result.data.total > 0
                        ? (long) page.offset + batch.size() < result.data.total : null;
                boolean[] added = pages.accept(page, ids, hasNext);
                if (added == null) { updateLoadingStatus(); return; }
                int start = photoList.size();
                for (int i = 0; i < batch.size(); i++) {
                    if (added[i]) {
                        FnHttpApi.FolderMediaItem item = batch.get(i);
                        photoList.add(item);
                        mediaItems.add(toMediaItem(item, baseUrl));
                    }
                }
                if (!photoList.isEmpty()) {
                    findViewWithTag("photos_title").setVisibility(View.VISIBLE);
                    rvPhotos.setVisibility(View.VISIBLE);
                }
                if (photoList.size() > start) photoAdapter.notifyItemRangeInserted(start, photoList.size() - start);
                updateLoadingStatus();
            }
            @Override public void onFailure(Call<FnHttpApi.FolderFileListResponse> call, Throwable t) {
                if (destroyed || call.isCanceled()) return;
                if (pages.fail(page)) updateLoadingStatus();
            }
        });
    }

    private void startNextWindow() {
        if (!pages.nextWindow()) return;
        dataGeneration = new Object();
        MediaListHolder.clear();
        photoList.clear();
        mediaItems.clear();
        photoAdapter.notifyDataSetChanged();
        rvPhotos.scrollToPosition(0);
        loadMediaFiles(false);
    }

    private void updateLoadingStatus() {
        if (detailCallback != null && !pages.isLoading()) {
            MediaListHolder.PageCallback callback = detailCallback;
            detailCallback = null;
            if (pages.hasError()) callback.onFailure();
            else callback.onLoaded(toMediaItems(), pages.hasMore());
        }
        boolean error = foldersFailed || pages.hasError();
        tvLoading.setFocusable(error || pages.isWindowFull());
        tvLoading.setClickable(error || pages.isWindowFull());
        if (pages.isInvalidPage()) {
            tvLoading.setText("服务器分页响应超限或无效，未跳过记录；按确定重试或返回缩小范围");
        } else if (error) {
            tvLoading.setText("部分内容加载失败，按确定重试（已加载 " + photoList.size() + " 项）");
        } else if (pages.isWindowFull()) {
            tvLoading.setText("本批已扫描 " + pages.getWindowRows() + " 条、显示 " + photoList.size()
                    + " 项；按确定查看下一批（替换当前列表）");
        } else if (pages.isOffsetLimit()) {
            tvLoading.setText(pages.boundaryMessage());
        } else if (pages.isStalled()) {
            tvLoading.setText("服务器返回重复页，已停止加载（已加载 " + photoList.size() + " 项）");
        } else if (foldersLoading || pages.isLoading()) {
            tvLoading.setText(photoList.isEmpty() ? "加载中..." : "正在加载更多...");
        } else {
            tvLoading.setText(folderList.isEmpty() && photoList.isEmpty() ? "该文件夹为空"
                    : "已加载 " + photoList.size() + " 项" + (pages.hasMore() ? "，下移加载更多" : ""));
        }
        if (foldersTruncated) tvLoading.append("；子文件夹仅显示前 " + MetadataLimits.MAX_ITEMS
                + " 项，接口无已验证分页，请返回上级缩小范围");
    }

    @Override protected void onDestroy() {
        destroyed = true;
        pages.close();
        if (mediaCall != null) mediaCall.cancel();
        if (folderCall != null) folderCall.cancel();
        if (detailCallback != null) {
            MediaListHolder.PageCallback callback = detailCallback;
            detailCallback = null;
            callback.onFailure();
        }
        if (rvPhotos != null) rvPhotos.setAdapter(null);
        super.onDestroy();
    }

    private View findViewWithTag(String tag) {
        for (int i = 0; i < layoutContent.getChildCount(); i++) {
            View child = layoutContent.getChildAt(i);
            if (tag.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (folderNavigation != null) {
            outState.putStringArrayList("folder_paths", folderNavigation.savedPaths());
            outState.putStringArrayList("folder_names", folderNavigation.savedNames());
        }
    }

    private void reloadCurrentFolder() {
        dataGeneration = new Object();
        pages.reset();
        if (mediaCall != null) mediaCall.cancel();
        if (folderCall != null) folderCall.cancel();
        folderCall = null;
        mediaCall = null;
        if (detailCallback != null) {
            MediaListHolder.PageCallback callback = detailCallback;
            detailCallback = null;
            callback.onFailure();
        }
        MediaListHolder.clear();
        foldersLoading = false;
        foldersFailed = false;
        foldersTruncated = false;
        folderList.clear();
        photoList.clear();
        mediaItems.clear();
        folderAdapter.notifyDataSetChanged();
        photoAdapter.notifyDataSetChanged();
        rvFolders.scrollToPosition(0);
        rvPhotos.scrollToPosition(0);
        rvFolders.setVisibility(View.GONE);
        rvPhotos.setVisibility(View.GONE);
        findViewWithTag("folders_title").setVisibility(View.GONE);
        findViewWithTag("photos_title").setVisibility(View.GONE);
        currentFolderPath = folderNavigation.getPath();
        currentFolderName = folderNavigation.getName();
        tvTitle.setText(currentFolderName);
        loadFolderContent();
    }

    @Override public void onBackPressed() {
        if (folderNavigation != null && folderNavigation.back()) {
            Toast.makeText(this, "返回上级文件夹，重新加载列表", Toast.LENGTH_SHORT).show();
            reloadCurrentFolder();
        } else finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ========== 文件夹适配器 ==========
    private class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.ViewHolder> {

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_folder_preview, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            FnHttpApi.SubFolderItem folder = folderList.get(position);
            holder.bind(folder);
        }

        @Override
        public int getItemCount() {
            return folderList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivPreview;
            TextView tvName;
            TextView tvCount;

            ViewHolder(View itemView) {
                super(itemView);
                ivPreview = itemView.findViewById(R.id.iv_folder_preview);
                tvName = itemView.findViewById(R.id.tv_folder_name);
                tvCount = itemView.findViewById(R.id.tv_folder_count);

                itemView.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        FnHttpApi.SubFolderItem folder = folderList.get(pos);
                        if (folderNavigation.enter(folder.path, folder.name)) reloadCurrentFolder();
                        else Toast.makeText(FolderBrowseActivity.this,
                                "目录重复、路径超长或超过 " + FolderNavigationState.MAX_HISTORY
                                        + " 层；请返回上级后重试", Toast.LENGTH_LONG).show();
                    }
                });

                itemView.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.setBackgroundResource(R.drawable.bg_menu_item);
                    } else {
                        v.setBackgroundResource(0);
                    }
                });
            }

            void bind(FnHttpApi.SubFolderItem folder) {
                tvName.setText(folder.name);
                tvCount.setText("文件夹");
                ivPreview.setImageResource(R.drawable.folder_icon);
            }
        }
    }

    // ========== 照片网格适配器 ==========
    private class PhotoGridAdapter extends RecyclerView.Adapter<PhotoGridAdapter.ViewHolder> {
        PhotoGridAdapter() { setHasStableIds(true); }
        @Override public long getItemId(int position) { return photoList.get(position).id; }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_photo_grid, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            FnHttpApi.FolderMediaItem item = photoList.get(position);
            holder.bind(item);

            if (position >= photoList.size() - 12) {
                rvPhotos.post(() -> loadMediaFiles(false));
            }
        }

        @Override public void onViewRecycled(ViewHolder holder) {
            CachedImageLoader.clear(holder.ivPhoto);
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return photoList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivPhoto;
            View vVideoIndicator;

            ViewHolder(View itemView) {
                super(itemView);
                ivPhoto = itemView.findViewById(R.id.iv_photo);
                vVideoIndicator = itemView.findViewById(R.id.v_video_indicator);

                itemView.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        openMediaDetail(pos);
                    }
                });

                itemView.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.setBackgroundResource(R.drawable.bg_menu_item);
                    } else {
                        v.setBackgroundResource(0);
                    }
                });
            }

            void bind(FnHttpApi.FolderMediaItem item) {
                String thumbUrl = baseUrl + "/p/api/v1/stream/p/t/" + item.id + "/s/" + item.photoUUID;

                CachedImageLoader.loadIntoImageView(ivPhoto, thumbUrl, token);

                vVideoIndicator.setVisibility("video".equals(item.category) ? View.VISIBLE : View.GONE);
            }
        }
    }

    private List<MediaItem> toMediaItems() {
        return new ArrayList<>(mediaItems);
    }

    private static MediaItem toMediaItem(FnHttpApi.FolderMediaItem item, String baseUrl) {
        String thumbUrl = baseUrl + "/p/api/v1/stream/p/t/" + item.id + "/s/" + item.photoUUID;
        String mediaUrl = "video".equals(item.category)
                ? baseUrl + "/p/api/v1/stream/v/" + item.id
                : baseUrl + "/p/api/v1/stream/p/t/" + item.id + "/o/" + item.photoUUID;
        return new MediaItem(String.valueOf(item.id), item.fileName, item.category, thumbUrl, mediaUrl);
    }

    private void openMediaDetail(int position) {
        final Object generation = dataGeneration;
        MediaListHolder.set(toMediaItems(), new MediaListHolder.PageSource() {
            private final GridPageState.Delivery delivery = new GridPageState.Delivery(mediaItems.size());
            @Override public boolean hasMore() {
                return !(destroyed || generation != dataGeneration) && delivery.hasMore(mediaItems.size(), pages.hasMore());
            }
            @Override public String getBoundaryMessage() {
                return destroyed || generation != dataGeneration ? "原列表已切换，请返回列表重新选择"
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
                if (!pages.isLoading()) loadMediaFiles(true);
            }
        });
        Intent intent = new Intent(this, MediaDetailActivity.class);
        intent.putExtra("CURRENT_INDEX", position);
        startActivity(intent);
    }
}
