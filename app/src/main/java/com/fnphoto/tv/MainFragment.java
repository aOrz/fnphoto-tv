package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.*;

import com.fnphoto.tv.api.FnAuthUtils;
import com.fnphoto.tv.api.FnHttpApi;
import com.fnphoto.tv.api.HttpClientProvider;
import com.fnphoto.tv.paging.MetadataLimits;
import com.fnphoto.tv.api.GeoPhotoResponseReader;
import com.fnphoto.tv.api.MetadataResponseInterceptor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainFragment extends BrowseSupportFragment {
    private static final String TAG = "MainFragment";
    private static final int PREVIEW_LOAD_DELAY = 300; // 延迟加载时间
    private static final int VISIBLE_RANGE_BUFFER = 6; // 可视范围前后的缓冲数量

    private static final int PAGE_SIZE = 60;
    private static final int PHOTO_PAGE_SIZE = 35; // Preserve the NAS album endpoint's existing page size.
    private static final int PREFETCH_DISTANCE = 12;
    private static final int MAX_PREVIEW_REQUESTS = 2;
    private final MainRequestScope requests = new MainRequestScope();
    private final Set<Integer> pendingPreviewIndexes = new HashSet<>();
    private final Set<Integer> pendingPersonPreviewIndexes = new HashSet<>();
    private final Map<Integer, Long> previewRetryAfter = new HashMap<>();
    private final Map<Integer, Long> personPreviewRetryAfter = new HashMap<>();
    private final Map<MediaItem, ArrayObjectAdapter> previewRows = new IdentityHashMap<>();
    private int previewRequests;
    private int previewScreen; // 0: inactive, 1: timeline, 2: person timeline
    private MainPagingState photoPaging;
    private final SparseScanBudget photoScan = new SparseScanBudget(MetadataLimits.MAX_SCAN_PAGES);
    private boolean timelineLimited;
    private boolean peopleLimited;
    private boolean personTimelineLimited;
    private boolean placesLimited;
    private MediaItem geoRetryItem;
    private String geoBoundaryMessage;
    private String photoFailureMessage;
    private String albumFailureMessage;
    private MainPagingState albumPaging;
    private boolean applyingPage;
    private int lastFilteredAutoIndex;
    private boolean albumDirectory;
    private int savedAlbumSelection = -1;
    private String photoMode;
    private String photoDate;
    private String photoRangeStart;
    private String photoRangeEnd;
    private String photoCategory;
    private int photoAlbumId;
    private int photoPersonId;
    private String pagedTitle;
    private ArrayObjectAdapter pagedLastRow;
    private ArrayObjectAdapter pageFooterAdapter;
    private ListRow pageFooter;
    private final Map<String, Integer> photoPositions = new HashMap<>();
    private final Map<String, Integer> albumPositions = new HashMap<>();
    private MediaListHolder.PageCallback detailPageCallback;

    private FnHttpApi api;
    private String token;
    private String baseUrl;
    private ArrayObjectAdapter mRowsAdapter;
    private CardPresenter mCardPresenter;
    private List<FnHttpApi.TimelineItem> timelineItems;
    private boolean isPhotoListView = false;
    private boolean favoritesTimeline;
    private long collectionRevision;
    private List<MediaItem> currentMediaList;
    
    // 懒加载相关
    private List<MediaItem> allDateItems = new ArrayList<>();
    private List<FnHttpApi.TimelineItem> allTimelineItems = new ArrayList<>();
    private Set<Integer> loadedIndexes = new HashSet<>();
    private Handler lazyLoadHandler = new Handler(Looper.getMainLooper());
    private Handler positionHandler = new Handler(Looper.getMainLooper()); // 专门用于位置恢复
    private int lastVisibleIndex = -1;
    
    // 预览缩略图缓存（按日期字符串缓存，避免返回时重新加载）
    private Map<String, List<String>> previewThumbnailCache = new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > 512;
        }
    };

    // 保存滚动位置
    private int savedTimelinePosition = -1;  // 保存时间线的选中位置
    private int savedPhotoListPosition = -1; // 保存照片列表的选中位置
    private SharedPreferences.OnSharedPreferenceChangeListener tokenChangeListener;

    // 人物模式
    private int currentPersonId = -1;
    private String currentPersonName;
    private List<FnHttpApi.PersonTimelineItem> savedPersonTimelineItems;
    private List<MediaItem> personDateItems = new ArrayList<>();
    private List<FnHttpApi.PersonTimelineItem> personTimelineItemsList = new ArrayList<>();
    private Set<Integer> personLoadedIdx = new HashSet<>();
    private int personLastVisibleIdx = -1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getArguments() != null) {
            baseUrl = getArguments().getString("nas_url", "");
            token = getArguments().getString("api_token", "");
        }

        setupUI();
        
        if (baseUrl != null && !baseUrl.isEmpty()) {
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl + "/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(HttpClientProvider.getClient(getActivity()))
                    .build();
            api = retrofit.create(FnHttpApi.class);
        }

        setupEventListeners();
        watchTokenChanges();
        if (getActivity() != null) collectionRevision = getActivity()
                .getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE).getLong("collect_revision", 0L);
    }

    @Override public void onResume() {
        super.onResume();
        if (getActivity() == null) return;
        long revision = getActivity().getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE)
                .getLong("collect_revision", 0L);
        if (revision == collectionRevision) return;
        collectionRevision = revision;
        java.util.Iterator<String> keys = previewThumbnailCache.keySet().iterator();
        while (keys.hasNext()) if (keys.next().startsWith("favorites:")) keys.remove();
        if (favoritesTimeline) {
            if (isPhotoListView && "favorites".equals(photoMode) && photoDate != null) {
                loadPhotosByDate(photoDate, 0);
            } else {
                loadFavorites();
            }
        }
    }

    private void watchTokenChanges() {
        if (getActivity() == null) return;
        SharedPreferences prefs = getActivity().getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        tokenChangeListener = (sharedPreferences, key) -> {
            if ("api_token".equals(key)) {
                String newToken = sharedPreferences.getString(key, "");
                if (!newToken.isEmpty() && !newToken.equals(token)) {
                    token = newToken;
                    Log.i(TAG, "Token updated from SharedPreferences");
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(tokenChangeListener);
    }

    private void beginScreen() {
        requests.next();
        lazyLoadHandler.removeCallbacksAndMessages(null);
        positionHandler.removeCallbacksAndMessages(null);
        pendingPreviewIndexes.clear();
        pendingPersonPreviewIndexes.clear();
        previewRetryAfter.clear();
        personPreviewRetryAfter.clear();
        previewRows.clear();
        previewRequests = 0;
        previewScreen = 0;
        if (photoPaging != null) photoPaging.cancel();
        if (albumPaging != null) albumPaging.cancel();
        photoPaging = null;
        photoScan.reset();
        applyingPage = false;
        lastFilteredAutoIndex = 0;
        geoRetryItem = null;
        geoBoundaryMessage = null;
        photoFailureMessage = null;
        currentMediaList = null;
        photoPositions.clear();
        albumDirectory = false;
        pagedLastRow = null;
        pageFooter = null;
        pageFooterAdapter = null;
        if (detailPageCallback != null) {
            MediaListHolder.PageCallback callback = detailPageCallback;
            detailPageCallback = null;
            callback.onFailure();
        }
        hideLoadingOverlay();
    }

    private void beginRootScreen() {
        beginScreen();
        favoritesTimeline = false;
        timelineLimited = peopleLimited = personTimelineLimited = placesLimited = false;
        photoMode = null;
        timelineItems = null;
        currentMediaList = null;
        albumPaging = null;
        photoPositions.clear();
        albumPositions.clear();
        allDateItems.clear();
        allTimelineItems.clear();
        personDateItems.clear();
        personTimelineItemsList.clear();
        loadedIndexes.clear();
        personLoadedIdx.clear();
        savedAlbumList = null;
        savedPlacesList = null;
        savedPersonList = null;
        savedPersonTimelineItems = null;
        currentPersonId = -1;
        currentPersonName = null;
        savedAlbumSelection = -1;
        savedTimelinePosition = -1;
        savedPhotoListPosition = -1;
        isPhotoListView = false;
        mRowsAdapter.clear();
    }

    private <T> void enqueue(Call<T> call, final Callback<T> callback) {
        final long generation = requests.generation();
        requests.track(call, call::cancel);
        call.enqueue(new Callback<T>() {
            @Override public void onResponse(Call<T> completed, Response<T> response) {
                if (requests.complete(completed, generation) && !completed.isCanceled()) {
                    callback.onResponse(completed, response);
                }
            }
            @Override public void onFailure(Call<T> completed, Throwable error) {
                if (requests.complete(completed, generation) && !completed.isCanceled()) {
                    callback.onFailure(completed, error);
                }
            }
        });
    }

    private String absoluteUrl(String url) {
        return url == null || url.isEmpty() ? null :
                (url.startsWith("http://") || url.startsWith("https://") ? url : baseUrl + url);
    }

    private void setupUI() {
        setTitle("");
        setHeadersState(BrowseSupportFragment.HEADERS_DISABLED);
        setBrandColor(getResources().getColor(android.R.color.transparent));
        setSearchAffordanceColor(getResources().getColor(android.R.color.transparent));
        
        mCardPresenter = new CardPresenter(baseUrl);
        mRowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(mRowsAdapter);
    }

    private void setupEventListeners() {
        setOnItemViewClickedListener(new OnItemViewClickedListener() {
            @Override
            public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                      RowPresenter.ViewHolder rowViewHolder, Row row) {
                if (item instanceof MediaItem) {
                    MediaItem mediaItem = (MediaItem) item;
                    
                    if ("geo_retry".equals(mediaItem.getType()) && geoRetryItem != null) {
                        loadPhotosByGeo(geoRetryItem);
                    } else if ("boundary_notice".equals(mediaItem.getType())) {
                        new android.app.AlertDialog.Builder(getActivity()).setMessage(mediaItem.getDateStr())
                                .setPositiveButton("知道了", null).show();
                    } else if ("page_action".equals(mediaItem.getType())) {
                        if (photoPaging != null) {
                            if (isWindowFull(photoPaging, currentMediaList.size())) nextPhotoWindow();
                            else loadPhotoPage(true);
                        } else if (albumDirectory) {
                            if (isWindowFull(albumPaging, savedAlbumList.size())) nextAlbumWindow();
                            else loadAlbumPage();
                        }
                    } else if ("date".equals(mediaItem.getType())) {
                        loadPhotosByDate(mediaItem.getDateStr(), mediaItem.getPhotoCount());
                    } else if ("person_date".equals(mediaItem.getType())) {
                        loadPersonPhotosByDate(mediaItem.getDateStr(), mediaItem.getPhotoCount());
                    } else if ("folder".equals(mediaItem.getType())) {
                        openFolderBrowse(mediaItem);
                    } else if ("album".equals(mediaItem.getType())) {
                        loadPhotosByAlbum(mediaItem.getId(), mediaItem.getTitle());
                    } else if ("place".equals(mediaItem.getType())) {
                        loadPhotosByGeo(mediaItem);
                    } else if ("person".equals(mediaItem.getType())) {
                        int personId = Integer.parseInt(mediaItem.getId());
                        loadPersonTimeline(personId, mediaItem.getTitle());
                    } else if ("video".equals(mediaItem.getType()) || "photo".equals(mediaItem.getType())) {
                        openMediaDetail(mediaItem);
                    }
                }
            }
        });

        // 监听选中项变化，实现懒加载
        setOnItemViewSelectedListener(new OnItemViewSelectedListener() {
            @Override
            public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                       RowPresenter.ViewHolder rowViewHolder, Row row) {
                if (item instanceof MediaItem) {
                    MediaItem mi = (MediaItem) item;
                    if (photoPaging != null && ("photo".equals(mi.getType()) || "video".equals(mi.getType()))) {
                        Integer index = photoPositions.get(mi.getId());
                        if (index != null && index >= currentMediaList.size() - PREFETCH_DISTANCE
                                && !photoPaging.hasFailed() && !applyingPage
                                && (!isFilteredPhotoMode() || index > lastFilteredAutoIndex)) {
                            if (isFilteredPhotoMode()) lastFilteredAutoIndex = index;
                            loadPhotoPage();
                        }
                    } else if (albumDirectory && "album".equals(mi.getType())) {
                        Integer index = albumPositions.get(mi.getId());
                        if (index != null && index >= savedAlbumList.size() - PREFETCH_DISTANCE
                                && !albumPaging.hasFailed()) loadAlbumPage();
                    } else if ("date".equals(mi.getType())) {
                        int selectedIndex = allDateItems.indexOf(item);
                        if (selectedIndex >= 0) {
                            scheduleLazyLoad(selectedIndex);
                        }
                    } else if ("person_date".equals(mi.getType())) {
                        int selectedIndex = personDateItems.indexOf(item);
                        if (selectedIndex >= 0) {
                            schedulePersonLazyLoad(selectedIndex);
                        }
                    }
                }
            }
        });
    }
    
    private void scheduleLazyLoad(int centerIndex) {
        lastVisibleIndex = centerIndex;
        
        // 取消之前的延迟任务
        lazyLoadHandler.removeCallbacksAndMessages(null);
        
        // 延迟加载，避免快速滚动时频繁加载
        lazyLoadHandler.postDelayed(() -> {
            if (centerIndex == lastVisibleIndex) {
                // 用户停止滚动，加载可视范围内的预览
                loadVisiblePreviews(centerIndex);
            }
        }, PREVIEW_LOAD_DELAY);
    }
    
    private void loadVisiblePreviews(int centerIndex) {
        if (previewScreen != 1 || isPhotoListView || allDateItems.isEmpty() || allTimelineItems.isEmpty()) return;
        lastVisibleIndex = centerIndex;
        int start = Math.max(0, centerIndex - VISIBLE_RANGE_BUFFER);
        int end = Math.min(allDateItems.size(), centerIndex + VISIBLE_RANGE_BUFFER + 1);
        // Nearest items first; only two metadata requests compete with visible Glide images.
        for (int distance = 0; distance <= VISIBLE_RANGE_BUFFER; distance++) {
            for (int side = 0; side < (distance == 0 ? 1 : 2); side++) {
                final int index = centerIndex + (side == 0 ? distance : -distance);
                if (index < start || index >= end || loadedIndexes.contains(index)
                        || pendingPreviewIndexes.contains(index)) continue;
                Long retryAfter = previewRetryAfter.get(index);
                if (retryAfter != null && retryAfter > android.os.SystemClock.uptimeMillis()) continue;
                final MediaItem item = allDateItems.get(index);
                if (item.getPreviewThumbUrls() != null) {
                    loadedIndexes.add(index);
                    continue;
                }
                if (previewRequests >= MAX_PREVIEW_REQUESTS) return;
                pendingPreviewIndexes.add(index);
                previewRequests++;
                loadDatePreviewThumbnails(item, allTimelineItems.get(index), () -> {
                    previewRequests--;
                    pendingPreviewIndexes.remove(index);
                    if (item.getPreviewThumbUrls() != null) {
                        loadedIndexes.add(index);
                        notifyItemChanged(item);
                    } else {
                        previewRetryAfter.put(index, android.os.SystemClock.uptimeMillis() + 3000);
                    }
                    loadVisiblePreviews(lastVisibleIndex);
                });
            }
        }
    }

    private void notifyItemChanged(MediaItem item) {
        ArrayObjectAdapter row = previewRows.get(item);
        if (row == null) return;
        int index = row.indexOf(item);
        if (index >= 0) row.notifyItemRangeChanged(index, 1);
    }

    public boolean onBackPressed() {
        // Media type is a root screen, not a drill-down. Preserve its cursor on first BACK.
        if (isPhotoListView && "media_type".equals(photoMode)) return false;
        if (isPhotoListView) {
            beginScreen();
            if (timelineItems != null) {
                Log.d(TAG, "Returning to timeline");
                savePhotoListPosition();
                // A detail-view collect/uncollect may remove the date entirely.
                // Reusing the old aggregate would leave a stale count/empty date card.
                if (favoritesTimeline) loadFavorites(true);
                else displayTimeline(timelineItems);
            } else if (savedAlbumList != null) {
                Log.d(TAG, "Returning to album list");
                displayAlbums(savedAlbumList);
            } else if (savedPlacesList != null) {
                Log.d(TAG, "Returning to places list");
                displayPlaces(savedPlacesList);
            } else if (savedPersonTimelineItems != null) {
                Log.d(TAG, "Returning to person timeline");
                displayPersonTimeline(savedPersonTimelineItems);
            } else if (currentPersonId > 0 && savedPersonList != null) {
                currentPersonId = -1;
                currentPersonName = null;
                displayPeople(savedPersonList);
            } else {
                return false;
            }
            return true;
        } else if (currentPersonId > 0) {
            beginScreen();
            Log.d(TAG, "Returning from person timeline to person list");
            currentPersonId = -1;
            currentPersonName = null;
            if (savedPersonList != null) {
                displayPeople(savedPersonList);
                return true;
            }
            return false;
        }
        return false;
    }
    
    private void saveTimelinePosition() {
        try {
            // 找到当前选中的日期项在allDateItems中的索引
            int selectedRow = getSelectedPosition();
            if (selectedRow >= 0 && selectedRow < mRowsAdapter.size()) {
                Object row = mRowsAdapter.get(selectedRow);
                if (row instanceof ListRow) {
                    // 获取当前行的选中位置
                    // 注意：Leanback不直接提供行内选中位置，我们使用lastVisibleIndex
                    savedTimelinePosition = lastVisibleIndex;
                    Log.d(TAG, "Saved timeline position: " + savedTimelinePosition);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving timeline position", e);
        }
    }
    
    private void savePhotoListPosition() {
        try {
            savedPhotoListPosition = getSelectedPosition();
            Log.d(TAG, "Saved photo list position: " + savedPhotoListPosition);
        } catch (Exception e) {
            Log.e(TAG, "Error saving photo list position", e);
        }
    }
    
    private void restoreTimelinePosition() {
        restorePreviewPosition(allDateItems, savedTimelinePosition);
    }

    private void restorePreviewPosition(List<MediaItem> items, int position) {
        if (position < 0 || position >= items.size()) return;
        MediaItem target = items.get(position);
        positionHandler.post(() -> {
            ArrayObjectAdapter adapter = previewRows.get(target);
            if (adapter == null) return;
            for (int row = 0; row < mRowsAdapter.size(); row++) {
                Object value = mRowsAdapter.get(row);
                if (value instanceof ListRow && ((ListRow) value).getAdapter() == adapter) {
                    restoreCardSelection(row, adapter.indexOf(target));
                    return;
                }
            }
        });
    }

    private void restoreCardSelection(int row, int column) {
        if (!isAdded() || getView() == null || row < 0 || row >= mRowsAdapter.size()
                || column < 0) return;
        RowsSupportFragment rows = getRowsSupportFragment();
        if (rows == null || rows.getVerticalGridView() == null) {
            setSelectedPosition(row, false);
            return;
        }
        // BrowseSupportFragment's task overload always starts a headers transition in
        // Leanback 1.0.0, which throws with our HEADERS_DISABLED configuration.
        // Apply the item task directly to the rows; their selection listener keeps
        // BrowseSupportFragment's selected row in sync without touching headers.
        rows.setSelectedPosition(row, false,
                new ListRowPresenter.SelectItemViewHolderTask(column));
    }

    public void loadTimeline() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        beginRootScreen();
        showLoadingOverlay("正在加载...");
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/gallery/timeline", "GET", null);

        enqueue(api.getTimeline(token, authx, null), new Callback<FnHttpApi.TimelineResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.TimelineResponse> call, 
                                   Response<FnHttpApi.TimelineResponse> response) {
                hideLoadingOverlay();
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.TimelineResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayTimeline(result.data.list);
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.TimelineResponse> call, Throwable t) {
                showRootLoadFailure(t);
            }
        });
    }

    private void displayTimeline(List<FnHttpApi.TimelineItem> items) {
        if (rejectInvalidMetadata(items)) return;
        timelineLimited |= items.size() > MetadataLimits.MAX_ITEMS;
        items = boundedMetadata(items);
        previewScreen = 1;
        timelineItems = items;
        isPhotoListView = false;
        allDateItems.clear();
        allTimelineItems.clear();
        loadedIndexes.clear();
        
        mRowsAdapter.clear();
        
        String currentYearMonth = "";
        ArrayObjectAdapter currentRowAdapter = null;
        
        for (FnHttpApi.TimelineItem item : items) {
            String yearMonth = item.year + "年" + item.month + "月";
            String dateStr = item.year + "-" + String.format("%02d", item.month) + "-" + String.format("%02d", item.day);
            
            if (!yearMonth.equals(currentYearMonth)) {
                currentYearMonth = yearMonth;
                HeaderItem header = new HeaderItem(yearMonth);
                currentRowAdapter = new ArrayObjectAdapter(mCardPresenter);
                mRowsAdapter.add(new ListRow(header, currentRowAdapter));
            }
            
            MediaItem mediaItem = new MediaItem(
                dateStr,
                item.day + "日 (" + item.itemCount + "张)",
                item.itemCount
            );
            // 如果缓存中有预览缩略图，直接复用
            if (previewThumbnailCache.containsKey(PhotoSelection.cacheKey(favoritesTimeline, dateStr))) {
                mediaItem.setPreviewThumbUrls(previewThumbnailCache.get(PhotoSelection.cacheKey(favoritesTimeline, dateStr)));
            }
            currentRowAdapter.add(mediaItem);
            previewRows.put(mediaItem, currentRowAdapter);
            allDateItems.add(mediaItem);
            allTimelineItems.add(item);
        }
        
        if (timelineLimited) addUnpagedLimitNotice("日期");
        // 初始加载前几个可见项的预览
        if (!allDateItems.isEmpty()) {
            lazyLoadHandler.postDelayed(() -> loadVisiblePreviews(0), 500);
        }
        
        // 恢复之前保存的位置
        restoreTimelinePosition();
    }

    public void loadFolders() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        beginRootScreen();
        showLoadingOverlay("正在加载文件夹...");
        Boolean desc = false;
        Integer orderBy = 2;
        StringBuilder paramsBuilder = new StringBuilder();
        paramsBuilder.append("desc=").append(desc);
        paramsBuilder.append("&orderBy=").append(orderBy);

        String params = paramsBuilder.toString();

        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/folder/list", "GET", params);

        enqueue(api.getManagedFolders(token, authx, desc, orderBy), new Callback<FnHttpApi.FolderListResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.FolderListResponse> call,
                                   Response<FnHttpApi.FolderListResponse> response) {
                hideLoadingOverlay();
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.FolderListResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayFolders(result.data.list);
                    } else {
                        Log.e(TAG, "加载文件夹失败: code=" + result.code + ", msg=" + result.msg);
                    }
                } else {
                    Log.e(TAG, "加载文件夹失败: HTTP " + response.code());
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.FolderListResponse> call, Throwable t) {
                showRootLoadFailure(t);
            }
        });
    }

    private void displayFolders(List<FnHttpApi.FolderItem> folders) {
        if (rejectInvalidMetadata(folders)) return;
        boolean limited = folders.size() > MetadataLimits.MAX_ITEMS;
        folders = boundedMetadata(folders);
        isPhotoListView = false;
        timelineItems = null;
        mRowsAdapter.clear();

        int itemsPerRow = 6;
        int totalRows = (int) Math.ceil((double) folders.size() / itemsPerRow);

        for (int row = 0; row < totalRows; row++) {
            int start = row * itemsPerRow;
            int end = Math.min(start + itemsPerRow, folders.size());

            HeaderItem header = row == 0 ? new HeaderItem("文件夹 (" + folders.size() + ")") : null;
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);

            for (int i = start; i < end; i++) {
                FnHttpApi.FolderItem folder = folders.get(i);
                String folderName = folder.getFolderName();
                int totalCount = folder.getTotalCount();

                MediaItem item = new MediaItem(
                    String.valueOf(folder.folderId),
                    folderName,
                    "folder",
                    null,
                    folder.folderPath
                );

                if (totalCount > 0) {
                    StringBuilder countInfo = new StringBuilder();
                    if (folder.photoCount > 0) {
                        countInfo.append(folder.photoCount).append("张照片");
                    }
                    if (folder.videoCount > 0) {
                        if (countInfo.length() > 0) {
                            countInfo.append(" · ");
                        }
                        countInfo.append(folder.videoCount).append("个视频");
                    }
                    item.setDateStr(countInfo.toString());
                }

                rowAdapter.add(item);
            }

            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }
        if (limited) addUnpagedLimitNotice("文件夹目录");
    }

    public void loadAlbums() {
        if (api == null || token == null || token.isEmpty()) return;
        beginRootScreen();
        albumPaging = new MainPagingState(PAGE_SIZE);
        savedAlbumList = new ArrayList<>();
        displayAlbums(savedAlbumList);
        loadAlbumPage();
    }

    private void displayAlbums(List<FnHttpApi.NewAlbum> albums) {
        savedAlbumList = new ArrayList<>(albums);
        albumDirectory = true;
        isPhotoListView = false;
        timelineItems = null;
        pagedTitle = "相册";
        mRowsAdapter.clear();
        pagedLastRow = null;
        pageFooter = null;
        pageFooterAdapter = null;
        albumPositions.clear();
        for (int i = 0; i < albums.size(); i++) appendAlbumCard(albums.get(i), i);
        updatePageFooter(albumPaging, savedAlbumList.size());
        if (savedAlbumSelection >= 0 && savedAlbumSelection < albums.size()) {
            final int position = savedAlbumSelection;
            positionHandler.post(() -> restoreCardSelection(position / 6, position % 6));
        }
    }

    private void appendAlbumCard(FnHttpApi.NewAlbum album, int position) {
        String posterUrl = absoluteUrl(album.posterUrl != null ? album.posterUrl : album.posterImgUrl);
        MediaItem item = new MediaItem(String.valueOf(album.albumId), album.albumName,
                "album", posterUrl, posterUrl);
        item.setDateStr(album.photoCount + "张照片 · " + album.videoCount + "个视频");
        albumPositions.put(item.getId(), position);
        appendPagedCard(item, position);
    }

    private void loadAlbumPage() {
        if (!albumDirectory || albumPaging == null || applyingPage) return;
        if (isWindowFull(albumPaging, savedAlbumList.size())) {
            updatePageFooter(albumPaging, savedAlbumList.size());
            return;
        }
        final int offset = albumPaging.begin();
        if (offset < 0) {
            updatePageFooter(albumPaging, savedAlbumList.size());
            return;
        }
        albumFailureMessage = null;
        updatePageFooter(albumPaging, savedAlbumList.size());
        String params = "sort_direction=desc&sort_by=date_time&offset=" + offset + "&limit=" + PAGE_SIZE;
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/album/list", "GET", params);
        enqueue(api.getAlbums(token, authx, "desc", "date_time", offset, PAGE_SIZE),
                new Callback<FnHttpApi.AlbumListResponse>() {
            @Override public void onResponse(Call<FnHttpApi.AlbumListResponse> call,
                    Response<FnHttpApi.AlbumListResponse> response) {
                FnHttpApi.AlbumListResponse result = response.body();
                if (!response.isSuccessful() || result == null || result.code != 0
                        || result.data == null || !validPage(result.data.list, PAGE_SIZE)) {
                    albumPaging.fail();
                } else {
                    List<String> ids = new ArrayList<>();
                    for (FnHttpApi.NewAlbum album : result.data.list) ids.add(String.valueOf(album.albumId));
                    applyingPage = true;
                    for (int index : albumPaging.accept(ids, null)) {
                        FnHttpApi.NewAlbum album = result.data.list.get(index);
                        if (albumPositions.containsKey(String.valueOf(album.albumId))) continue;
                        int position = savedAlbumList.size();
                        savedAlbumList.add(album);
                        appendAlbumCard(album, position);
                    }
                    applyingPage = false;
                }
                updatePageFooter(albumPaging, savedAlbumList.size());
                if (offset == 0 && !savedAlbumList.isEmpty()) {
                    positionHandler.post(() -> setSelectedPosition(0));
                }
            }
            @Override public void onFailure(Call<FnHttpApi.AlbumListResponse> call, Throwable error) {
                albumPaging.fail();
                albumFailureMessage = metadataFailureMessage(error);
                updatePageFooter(albumPaging, savedAlbumList.size());
            }
        });
    }

    private void loadDatePreviewThumbnails(final MediaItem mediaItem, 
                                           final FnHttpApi.TimelineItem timelineItem,
                                           final Runnable onComplete) {
        if (api == null || token == null || token.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        
        String dateStr = mediaItem.getDateStr();
        if (favoritesTimeline) {
            SparseScanBudget budget = new SparseScanBudget(MetadataLimits.MAX_SCAN_PAGES);
            budget.start(true);
            loadFavoriteDatePreview(mediaItem, dateStr, new MainPagingState(PHOTO_PAGE_SIZE),
                    new ArrayList<>(), budget, onComplete);
            return;
        }
        String dateTime = dateStr.replace("-", ":");
        String startTime = dateTime + " 00:00:00";
        String endTime = dateTime + " 23:59:59";
        int limit = Math.min(timelineItem.itemCount,4);
        int offset = 0;
        String mode = "index";
        
        StringBuilder paramsBuilder = new StringBuilder();
        paramsBuilder.append("end_time=").append(endTime);
        paramsBuilder.append("&limit=").append(limit);
        paramsBuilder.append("&mode=").append(mode);
        paramsBuilder.append("&offset=").append(offset);
        paramsBuilder.append("&start_time=").append(startTime);
        
        String params = paramsBuilder.toString();
        String path = "/p/api/v1/gallery/getList";
        
        String authx = FnAuthUtils.generateAuthX(path, "GET", params);
        
        enqueue(api.getPhotosByTimeRange(token, authx, startTime, endTime, limit, offset, mode), new Callback<FnHttpApi.GalleryListResponse>() {
                @Override
                public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                                       Response<FnHttpApi.GalleryListResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        FnHttpApi.GalleryListResponse result = response.body();
                        if (result.code == 0 && result.data != null && validPage(result.data.list, Math.max(0, limit))) {
                            List<String> thumbUrls = new ArrayList<>();
                            for (FnHttpApi.GalleryPhoto photo : result.data.list) {
                                if (!PhotoSelection.includes(favoritesTimeline, photo.isCollect)) continue;
                                if (photo.additional != null && photo.additional.thumbnail != null) {
                                    String thumbUrl = photo.additional.thumbnail.sUrl;
                                    if (thumbUrl == null) {
                                        thumbUrl = photo.additional.thumbnail.mUrl;
                                    }
                                    if (thumbUrl != null) {
                                        if (!thumbUrl.startsWith("http") && baseUrl != null) {
                                            thumbUrl = baseUrl + thumbUrl;
                                        }
                                        thumbUrls.add(thumbUrl);
                                    }
                                }
                            }
                            mediaItem.setPreviewThumbUrls(thumbUrls);
                            previewThumbnailCache.put(PhotoSelection.cacheKey(favoritesTimeline, dateStr), thumbUrls);
                        }
                    }
                    if (onComplete != null) onComplete.run();
                }

                @Override
                public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable t) {
                    Log.e(TAG, "加载预览缩略图失败: " + dateStr, t);
                    if (onComplete != null) onComplete.run();
                }
            });
    }

    private void loadFavoriteDatePreview(MediaItem item, String date, MainPagingState paging,
                                         List<String> urls, SparseScanBudget budget, Runnable onComplete) {
        int offset = paging.begin();
        if (offset < 0) {
            if (onComplete != null) onComplete.run();
            return;
        }
        String start = date.replace("-", ":") + " 00:00:00";
        String end = date.replace("-", ":") + " 23:59:59";
        String params = "end_time=" + end + "&limit=" + PHOTO_PAGE_SIZE
                + "&mode=index&offset=" + offset + "&start_time=" + start;
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/gallery/getList", "GET", params);
        enqueue(api.getPhotosByTimeRange(token, authx, start, end, PHOTO_PAGE_SIZE, offset, "index"),
                new Callback<FnHttpApi.GalleryListResponse>() {
            @Override public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                    Response<FnHttpApi.GalleryListResponse> response) {
                FnHttpApi.GalleryListResponse result = response.body();
                if (!response.isSuccessful() || result == null || result.code != 0
                        || result.data == null || !validPage(result.data.list, PHOTO_PAGE_SIZE)) {
                    if (onComplete != null) onComplete.run();
                    return;
                }
                List<String> ids = new ArrayList<>();
                for (FnHttpApi.GalleryPhoto photo : result.data.list) ids.add(String.valueOf(photo.id));
                for (int index : paging.accept(ids, result.data.hasNext)) {
                    FnHttpApi.GalleryPhoto photo = result.data.list.get(index);
                    if (!PhotoSelection.includes(true, photo.isCollect) || photo.additional == null
                            || photo.additional.thumbnail == null) continue;
                    FnHttpApi.GalleryThumbnail thumb = photo.additional.thumbnail;
                    String url = absoluteUrl(thumb.sUrl != null ? thumb.sUrl : thumb.mUrl);
                    if (url != null) urls.add(url);
                    if (urls.size() == 4) break;
                }
                if (paging.hasFailed()) {
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (budget.acceptPage(urls.size() >= 4, paging.hasMore())) {
                    loadFavoriteDatePreview(item, date, paging, urls, budget, onComplete);
                    return;
                }
                item.setPreviewThumbUrls(urls);
                previewThumbnailCache.put(PhotoSelection.cacheKey(true, date), urls);
                if (onComplete != null) onComplete.run();
            }
            @Override public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable error) {
                if (onComplete != null) onComplete.run();
            }
        });
    }

    private void schedulePersonLazyLoad(int centerIndex) {
        personLastVisibleIdx = centerIndex;
        lazyLoadHandler.removeCallbacksAndMessages(null);
        lazyLoadHandler.postDelayed(() -> {
            if (centerIndex == personLastVisibleIdx) {
                loadVisiblePersonPreviews(centerIndex);
            }
        }, PREVIEW_LOAD_DELAY);
    }

    private void loadVisiblePersonPreviews(int centerIndex) {
        if (previewScreen != 2 || isPhotoListView || personDateItems.isEmpty() || personTimelineItemsList.isEmpty()) return;
        personLastVisibleIdx = centerIndex;
        int start = Math.max(0, centerIndex - VISIBLE_RANGE_BUFFER);
        int end = Math.min(personDateItems.size(), centerIndex + VISIBLE_RANGE_BUFFER + 1);
        for (int distance = 0; distance <= VISIBLE_RANGE_BUFFER; distance++) {
            for (int side = 0; side < (distance == 0 ? 1 : 2); side++) {
                final int index = centerIndex + (side == 0 ? distance : -distance);
                if (index < start || index >= end || personLoadedIdx.contains(index)
                        || pendingPersonPreviewIndexes.contains(index)) continue;
                Long retryAfter = personPreviewRetryAfter.get(index);
                if (retryAfter != null && retryAfter > android.os.SystemClock.uptimeMillis()) continue;
                final MediaItem item = personDateItems.get(index);
                if (item.getPreviewThumbUrls() != null) {
                    personLoadedIdx.add(index);
                    continue;
                }
                if (previewRequests >= MAX_PREVIEW_REQUESTS) return;
                pendingPersonPreviewIndexes.add(index);
                previewRequests++;
                loadPersonDatePreviewThumbnails(item, personTimelineItemsList.get(index), () -> {
                    previewRequests--;
                    pendingPersonPreviewIndexes.remove(index);
                    if (item.getPreviewThumbUrls() != null) {
                        personLoadedIdx.add(index);
                        notifyItemChanged(item);
                    } else {
                        personPreviewRetryAfter.put(index, android.os.SystemClock.uptimeMillis() + 3000);
                    }
                    loadVisiblePersonPreviews(personLastVisibleIdx);
                });
            }
        }
    }

    private void loadPersonDatePreviewThumbnails(final MediaItem mediaItem,
                                                  final FnHttpApi.PersonTimelineItem personItem,
                                                  final Runnable onComplete) {
        if (api == null || token == null || token.isEmpty() || currentPersonId < 0) {
            if (onComplete != null) onComplete.run();
            return;
        }

        String dateStr = mediaItem.getDateStr();
        String dateTime = dateStr.replace("-", ":");
        String startTime = dateTime + " 00:00:00";
        String endTime = dateTime + " 23:59:59";
        int limit = Math.min(personItem.itemCount, 4);

        String params = "album_id=" + currentPersonId
            + "&endTime=" + endTime
            + "&end_time=" + endTime
            + "&limit=" + limit
            + "&offset=0"
            + "&personId=" + currentPersonId
            + "&startTime=" + startTime
            + "&start_time=" + startTime;
        String path = "/p/api/v1/ai-person/photoLibrary/list";
        String authx = FnAuthUtils.generateAuthX(path, "GET", params);

        enqueue(api.getPersonPhotos(token, authx, currentPersonId, startTime, endTime, limit, 0, currentPersonId, startTime, endTime), new Callback<FnHttpApi.GalleryListResponse>() {
                @Override
                public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                                       Response<FnHttpApi.GalleryListResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        FnHttpApi.GalleryListResponse result = response.body();
                        if (result.code == 0 && result.data != null && validPage(result.data.list, Math.max(0, limit))) {
                            List<String> thumbUrls = new ArrayList<>();
                            for (FnHttpApi.GalleryPhoto photo : result.data.list) {
                                if (photo.additional != null && photo.additional.thumbnail != null) {
                                    String thumbUrl = photo.additional.thumbnail.sUrl;
                                    if (thumbUrl == null) {
                                        thumbUrl = photo.additional.thumbnail.mUrl;
                                    }
                                    if (thumbUrl != null) {
                                        if (!thumbUrl.startsWith("http") && baseUrl != null) {
                                            thumbUrl = baseUrl + thumbUrl;
                                        }
                                        thumbUrls.add(thumbUrl);
                                    }
                                }
                            }
                            mediaItem.setPreviewThumbUrls(thumbUrls);
                        }
                    }
                    if (onComplete != null) onComplete.run();
                }

                @Override
                public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable t) {
                    if (onComplete != null) onComplete.run();
                }
            });
    }

    /** Browse a media category using only the existing timeline/date-list contract. */
    public void loadMediaType(String category) {
        if (api == null || token == null || token.isEmpty()
                || !("photo".equals(category) || "video".equals(category))) return;
        beginRootScreen();
        photoCategory = category;
        final String title = "video".equals(category) ? "视频" : "照片";
        showLoadingOverlay("正在加载" + title + "...");
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/gallery/timeline", "GET", null);
        enqueue(api.getTimeline(token, authx, null), new Callback<FnHttpApi.TimelineResponse>() {
            @Override public void onResponse(Call<FnHttpApi.TimelineResponse> call,
                    Response<FnHttpApi.TimelineResponse> response) {
                hideLoadingOverlay();
                FnHttpApi.TimelineResponse result = response.body();
                if (!response.isSuccessful() || result == null || result.code != 0
                        || result.data == null || result.data.list == null || result.data.list.contains(null)) {
                    showEmptyState("加载失败，请重新选择媒体类型重试");
                    return;
                }
                TimelineDateRange range = new TimelineDateRange();
                for (FnHttpApi.TimelineItem item : result.data.list) {
                    if (!range.add(item.year, item.month, item.day)) {
                        showEmptyState("时间线日期无效，无法可靠确定媒体范围；请重新选择此分类重试");
                        return;
                    }
                }
                if (range.isEmpty()) {
                    showEmptyState("暂无" + title);
                    return;
                }
                photoRangeStart = range.firstDate() + " 00:00:00";
                photoRangeEnd = range.lastDate() + " 23:59:59";
                startPhotoPaging("media_type", title);
            }
            @Override public void onFailure(Call<FnHttpApi.TimelineResponse> call, Throwable error) {
                showRootLoadFailure(error);
            }
        });
    }

    public void loadPhotosByDate(String dateStr, int itemCount) {
        if (api == null || token == null || token.isEmpty()) return;
        saveTimelinePosition();
        beginScreen();
        photoDate = dateStr;
        startPhotoPaging(favoritesTimeline ? "favorites" : "date", dateStr);
    }

    public void loadFavorites() {
        loadFavorites(false);
    }

    private void loadFavorites(boolean restorePosition) {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        int previousPosition = savedTimelinePosition;
        beginRootScreen();
        if (restorePosition) savedTimelinePosition = previousPosition;
        favoritesTimeline = true;
        showLoadingOverlay("正在加载收藏...");
        String params = "is_collect=1";
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/gallery/timeline", "GET", params);

        enqueue(api.getTimeline(token, authx, 1), new Callback<FnHttpApi.TimelineResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.TimelineResponse> call,
                                   Response<FnHttpApi.TimelineResponse> response) {
                hideLoadingOverlay();
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.TimelineResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        if (result.data.list.isEmpty()) showEmptyState("暂无收藏");
                        else displayTimeline(result.data.list);
                    } else {
                        showEmptyState("暂无收藏");
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.TimelineResponse> call, Throwable t) {
                showRootLoadFailure(t);
            }
        });
    }

    public void loadRecent() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        beginRootScreen();
        showLoadingOverlay("正在加载最近照片...");
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/explore/recent_timeline", "GET", null);

        enqueue(api.getRecentTimeline(token, authx), new Callback<FnHttpApi.TimelineResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.TimelineResponse> call,
                                   Response<FnHttpApi.TimelineResponse> response) {
                hideLoadingOverlay();
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.TimelineResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayTimeline(result.data.list);
                    } else {
                        showEmptyState("暂无照片");
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.TimelineResponse> call, Throwable t) {
                showRootLoadFailure(t);
            }
        });
    }

    public void loadPlaces() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        beginRootScreen();
        showLoadingOverlay("正在加载地点...");
        String params = "offset=0&limit=-1";
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/explore/geos", "GET", params);

        enqueue(api.getGeos(token, authx, 0, -1), new Callback<FnHttpApi.GeoListResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.GeoListResponse> call,
                                   Response<FnHttpApi.GeoListResponse> response) {
                hideLoadingOverlay();
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.GeoListResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayPlaces(result.data.list);
                    } else {
                        showEmptyState("暂无地点");
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.GeoListResponse> call, Throwable t) {
                showRootLoadFailure(t);
            }
        });
    }

    private void displayPlaces(List<FnHttpApi.GeoItem> places) {
        if (rejectInvalidMetadata(places)) return;
        placesLimited |= places.size() > MetadataLimits.MAX_ITEMS;
        places = boundedMetadata(places);
        isPhotoListView = false;
        timelineItems = null;
        savedAlbumList = null;
        savedPlacesList = new ArrayList<>(places);
        mRowsAdapter.clear();

        int itemsPerRow = 6;
        int totalRows = (int) Math.ceil((double) places.size() / itemsPerRow);

        for (int row = 0; row < totalRows; row++) {
            int start = row * itemsPerRow;
            int end = Math.min(start + itemsPerRow, places.size());

            HeaderItem header = row == 0 ? new HeaderItem("地点 (" + places.size() + ")") : null;
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);

            for (int i = start; i < end; i++) {
                FnHttpApi.GeoItem geo = places.get(i);
                String posterUrl = absoluteUrl(geo.posterUrl);
                MediaItem item = new MediaItem(
                    geo.country + "/" + geo.city,
                    geo.city + "，" + geo.country,
                    "place",
                    posterUrl,
                    posterUrl
                );
                item.setDateStr(geo.itemCount + "张照片");
                rowAdapter.add(item);
            }

            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }
        if (placesLimited) addUnpagedLimitNotice("地点目录");
    }

    private void showEmptyState(String message) {
        isPhotoListView = true;
        timelineItems = null;
        mRowsAdapter.clear();

        HeaderItem header = new HeaderItem(message);
        ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(mCardPresenter);
        mRowsAdapter.add(new ListRow(header, listRowAdapter));
    }

    private List<FnHttpApi.NewAlbum> savedAlbumList;
    private List<FnHttpApi.GeoItem> savedPlacesList;
    private List<FnHttpApi.PersonItem> savedPersonList;

    public void loadPhotosByAlbum(String albumId, String albumName) {
        if (api == null || token == null || token.isEmpty()) return;
        final int id;
        try { id = Integer.parseInt(albumId); } catch (NumberFormatException error) { return; }
        Integer selection = albumPositions.get(albumId);
        savedAlbumSelection = selection == null ? -1 : selection;
        beginScreen();
        timelineItems = null;
        photoAlbumId = id;
        startPhotoPaging("album", "相册: " + albumName);
    }

    private void startPhotoPaging(String mode, String title) {
        photoMode = mode;
        pagedTitle = title;
        photoPaging = new MainPagingState(PHOTO_PAGE_SIZE);
        currentMediaList = new ArrayList<>();
        photoPositions.clear();
        isPhotoListView = true;
        mRowsAdapter.clear();
        loadPhotoPage();
    }

    private void loadPhotoPage() { loadPhotoPage(false); }

    private boolean isFilteredPhotoMode() {
        return "favorites".equals(photoMode) || "media_type".equals(photoMode);
    }

    private void loadPhotoPage(boolean explicit) {
        if (photoPaging == null || photoPaging.isLoading() || applyingPage) return;
        if (isWindowFull(photoPaging, currentMediaList.size())) {
            finishPhotoPage(true);
            return;
        }
        if (isFilteredPhotoMode() && !photoScan.start(explicit)) return;
        requestPhotoPage();
    }

    private void requestPhotoPage() {
        final int offset = photoPaging.begin();
        if (offset < 0) {
            finishPhotoPage(true);
            return;
        }
        photoFailureMessage = null;
        updatePageFooter(photoPaging, currentMediaList.size());
        Call<FnHttpApi.GalleryListResponse> request;
        if ("album".equals(photoMode)) {
            String params = "album_id=" + photoAlbumId + "&sort_by=date_time&sort_direction=desc&offset="
                    + offset + "&limit=" + PHOTO_PAGE_SIZE;
            String authx = FnAuthUtils.generateAuthX("/p/api/v1/album/photos", "GET", params);
            request = api.getAlbumPhotos(token, authx, photoAlbumId, "date_time", "desc", offset, PHOTO_PAGE_SIZE);
        } else {
            String dateTime = "media_type".equals(photoMode) ? null : photoDate.replace("-", ":");
            String start = "media_type".equals(photoMode) ? photoRangeStart : dateTime + " 00:00:00";
            String end = "media_type".equals(photoMode) ? photoRangeEnd : dateTime + " 23:59:59";
            if ("person".equals(photoMode)) {
                String params = "album_id=" + photoPersonId + "&endTime=" + end + "&end_time=" + end
                        + "&limit=" + PHOTO_PAGE_SIZE + "&offset=" + offset + "&personId=" + photoPersonId
                        + "&startTime=" + start + "&start_time=" + start;
                String authx = FnAuthUtils.generateAuthX("/p/api/v1/ai-person/photoLibrary/list", "GET", params);
                request = api.getPersonPhotos(token, authx, photoPersonId, start, end, PHOTO_PAGE_SIZE,
                        offset, photoPersonId, start, end);
            } else {
                String params = "end_time=" + end + "&limit=" + PHOTO_PAGE_SIZE + "&mode=index&offset="
                        + offset + "&start_time=" + start;
                String authx = FnAuthUtils.generateAuthX("/p/api/v1/gallery/getList", "GET", params);
                request = api.getPhotosByTimeRange(token, authx, start, end, PHOTO_PAGE_SIZE, offset, "index");
            }
        }
        enqueue(request, new Callback<FnHttpApi.GalleryListResponse>() {
            @Override public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                    Response<FnHttpApi.GalleryListResponse> response) {
                FnHttpApi.GalleryListResponse result = response.body();
                if (!response.isSuccessful() || result == null || result.code != 0
                        || result.data == null || !validPage(result.data.list, PHOTO_PAGE_SIZE)) {
                    photoPaging.fail();
                    finishPhotoPage(false);
                    return;
                }
                int previousCount = currentMediaList.size();
                List<String> ids = new ArrayList<>();
                for (FnHttpApi.GalleryPhoto photo : result.data.list) ids.add(String.valueOf(photo.id));
                applyingPage = true;
                for (int index : photoPaging.accept(ids, result.data.hasNext)) {
                    FnHttpApi.GalleryPhoto photo = result.data.list.get(index);
                    if (photoPositions.containsKey(String.valueOf(photo.id))) continue;
                    if (!PhotoSelection.includes("favorites".equals(photoMode), photo.isCollect)
                            || !PhotoSelection.matchesCategory("media_type".equals(photoMode) ? photoCategory : null, photo.category)) continue;
                    FnHttpApi.GalleryThumbnail thumb = photo.additional != null ? photo.additional.thumbnail : null;
                    String thumbUrl = thumb == null ? null : absoluteUrl(thumb.mUrl != null ? thumb.mUrl : thumb.sUrl);
                    String mediaUrl = thumb == null ? null : absoluteUrl(thumb.originalUrl);
                    if ("video".equals(photo.category)) {
                        mediaUrl = thumb != null && thumb.videoUrl != null ? absoluteUrl(thumb.videoUrl)
                                : baseUrl + "/p/api/v1/stream/v/" + photo.id;
                    } else if (mediaUrl == null) {
                        mediaUrl = thumbUrl;
                    }
                    MediaItem item = new MediaItem(String.valueOf(photo.id), photo.fileName,
                            photo.category, thumbUrl, mediaUrl);
                    int position = currentMediaList.size();
                    currentMediaList.add(item);
                    photoPositions.put(item.getId(), position);
                    appendPagedCard(item, position);
                }
                applyingPage = false;
                if (photoPaging.hasFailed()) {
                    finishPhotoPage(false);
                    return;
                }
                // Sparse filters get a finite raw-page budget per action. The
                // paused footer, not a false "empty/end", invites explicit continuation.
                if (isFilteredPhotoMode() && photoScan.acceptPage(
                        currentMediaList.size() > previousCount, photoPaging.hasMore())) {
                    requestPhotoPage();
                    return;
                }
                finishPhotoPage(true);
                if (previousCount == 0 && !currentMediaList.isEmpty()) {
                    positionHandler.post(() -> setSelectedPosition(0));
                }
            }
            @Override public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable error) {
                photoPaging.fail();
                photoFailureMessage = metadataFailureMessage(error);
                finishPhotoPage(false);
            }
        });
    }

    private void finishPhotoPage(boolean success) {
        updatePageFooter(photoPaging, currentMediaList.size());
        if (detailPageCallback != null) {
            MediaListHolder.PageCallback callback = detailPageCallback;
            detailPageCallback = null;
            if (success && photoScan.isPaused()) callback.onPaused(
                    "已扫描 " + photoPaging.getOffset() + " 条，暂未找到更多匹配");
            else if (success) callback.onLoaded(new ArrayList<>(currentMediaList),
                    photoPaging.hasMore() && !isWindowFull(photoPaging, currentMediaList.size()));
            else callback.onFailure();
        }
    }

    private void showRootLoadFailure(Throwable error) {
        Log.e(TAG, "元数据加载失败", error);
        hideLoadingOverlay();
        showEmptyState(isMetadataLimit(error)
                ? "响应超过安全大小，未显示结果；请改用相册、文件夹或搜索缩小范围"
                : "加载失败，请重新选择此分类重试");
    }

    private boolean rejectInvalidMetadata(List<?> rows) {
        if (rows != null && !rows.contains(null)) return false;
        showEmptyState("服务器返回无效条目；未显示结果，请重新选择此分类重试");
        return true;
    }

    private static boolean isMetadataLimit(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof MetadataResponseInterceptor.LimitExceededException
                    || error instanceof GeoPhotoResponseReader.LimitExceededException) return true;
        }
        return false;
    }

    private static String metadataFailureMessage(Throwable error) {
        return isMetadataLimit(error)
                ? "响应超过安全大小，未追加结果；请缩小范围（按确定可重试）" : null;
    }

    private static <T> List<T> boundedMetadata(List<T> rows) {
        return MainMetadataWindow.copyUnpaged(rows);
    }

    private void addUnpagedLimitNotice(String type) {
        addBoundaryNotice(type + "仅保留前 " + MetadataLimits.MAX_ITEMS
                + " 项；当前接口没有可靠续页合同，结果不完整。请改用相册、文件夹或搜索缩小范围。");
    }

    private void addBoundaryNotice(String message) {
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(mCardPresenter);
        MediaItem notice = new MediaItem("__boundary_notice", "确定：查看结果范围与限制", "boundary_notice", null, null);
        notice.setDateStr(message);
        adapter.add(notice);
        // A completeness warning must be visible before scrolling hundreds of
        // rows, not only after the potentially truncated result set.
        mRowsAdapter.add(0, new ListRow(new HeaderItem("结果范围（按确定查看）"), adapter));
    }

    private static boolean validPage(List<?> rows, int requestedSize) {
        if (rows == null || rows.size() > requestedSize) return false;
        for (Object row : rows) if (row == null) return false;
        return true;
    }

    private boolean isWindowFull(MainPagingState state, int count) {
        return state != null && state.hasMore()
                && !MainMetadataWindow.fitsNextPage(count, state.getPageSize());
    }

    private String scanPausedMessage() {
        return "确定：继续扫描（已查" + photoPaging.getOffset() + "条）";
    }

    private void nextPhotoWindow() {
        // Only an explicit list action replaces a window. Invalidate any detail
        // source referring to the previous window before dropping its metadata.
        requests.next();
        positionHandler.removeCallbacksAndMessages(null);
        MediaListHolder.clear();
        currentMediaList = new ArrayList<>();
        photoPositions.clear();
        photoScan.reset();
        lastFilteredAutoIndex = 0;
        resetPagedRows();
        loadPhotoPage(true);
        positionHandler.post(() -> setSelectedPosition(0));
    }

    private void nextAlbumWindow() {
        requests.next();
        positionHandler.removeCallbacksAndMessages(null);
        savedAlbumList = new ArrayList<>();
        albumPositions.clear();
        savedAlbumSelection = -1;
        resetPagedRows();
        loadAlbumPage();
        positionHandler.post(() -> setSelectedPosition(0));
    }

    private void resetPagedRows() {
        mRowsAdapter.clear();
        pagedLastRow = null;
        pageFooter = null;
        pageFooterAdapter = null;
    }

    private void appendPagedCard(MediaItem item, int position) {
        if (position % 6 == 0) {
            pagedLastRow = new ArrayObjectAdapter(mCardPresenter);
            ListRow row = new ListRow(position == 0 ? new HeaderItem(pagedTitle) : null, pagedLastRow);
            int footerIndex = pageFooter == null ? -1 : mRowsAdapter.indexOf(pageFooter);
            if (footerIndex >= 0) mRowsAdapter.add(footerIndex, row);
            else mRowsAdapter.add(row);
        }
        pagedLastRow.add(item);
    }

    private void updatePageFooter(MainPagingState state, int count) {
        if (state == null) return;
        String status;
        if (state.isLoading()) status = "正在加载 · 本批 " + count + " 项";
        else if (state.isOffsetLimited()) status = "偏移到上限 · 请缩小范围";
        else if (state.hasInvalidPage()) status = "响应异常 · 确定重试";
        else if (state.hasFailed()) {
            String reason = state == photoPaging ? photoFailureMessage : albumFailureMessage;
            status = reason != null ? "响应过大 · 缩小范围或确定重试" : "确定：重试（本批 " + count + " 项）";
        }
        else if (state.isStalled()) status = "结果可能不完整 · 请返回";
        else if (isWindowFull(state, count)) status = "确定：下一批（替换本批" + count + "项）";
        else if (state == photoPaging && photoScan.isPaused()) status = scanPausedMessage();
        else if (state.hasMore()) status = "确定：加载更多（本批 " + count + " 项）";
        else status = count == 0 ? "扫描到末尾 · 本批无匹配" : "扫描到末尾 · 本批 " + count + " 项";
        MediaItem action = new MediaItem("__page_action", status, "page_action", null, null);
        if (pageFooter == null) {
            pageFooterAdapter = new ArrayObjectAdapter(mCardPresenter);
            pageFooterAdapter.add(action);
            pageFooter = new ListRow(new HeaderItem("加载状态"), pageFooterAdapter);
            mRowsAdapter.add(pageFooter);
        } else {
            pageFooterAdapter.replace(0, action);
        }
    }

    public void loadPhotosByGeo(MediaItem placeItem) {
        if (token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        beginScreen();
        isPhotoListView = true;
        photoMode = "geo";
        geoRetryItem = placeItem;
        showLoadingOverlay("正在加载地点照片...");

        String[] parts = placeItem.getId().split("/", 2);
        final String country = parts.length > 0 ? parts[0] : "";
        final String city = parts.length > 1 ? parts[1] : "";
        final String placeName = placeItem.getTitle();

        final String requestBody = GeoPhotoResponseReader.requestBody(country, city);
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/search/results", "POST", requestBody);
        final String url = baseUrl + "/p/api/v1/search/results";

        Log.d(TAG, "=== 地点照片请求(OkHttp) ===");
        Log.d(TAG, "URL: " + url);
        Log.d(TAG, "Method: POST");
        Log.d(TAG, "Body: " + requestBody);


        MediaType JSON = MediaType.parse("application/json; charset=UTF-8");
        RequestBody body = RequestBody.create(JSON, requestBody);

        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .header("accesstoken", token)
            .header("authx", authx)
            .build();

        OkHttpClient client = HttpClientProvider.getClient(getActivity());
        final long generation = requests.generation();
        okhttp3.Call geoCall = client.newCall(request);
        requests.track(geoCall, geoCall::cancel);
        geoCall.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.e(TAG, "地点照片请求网络异常", e);
                positionHandler.post(() -> {
                    if (!requests.complete(call, generation)) return;
                    hideLoadingOverlay();
                    showGeoFailure(metadataFailureMessage(e) != null ? metadataFailureMessage(e)
                            : "网络异常，请按确定重试");
                });
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        final int status = response.code();
                        positionHandler.post(() -> {
                            if (!requests.complete(call, generation)) return;
                            hideLoadingOverlay();
                            showGeoFailure("地点请求失败（HTTP " + status + "），按确定重试");
                        });
                        return;
                    }
                    final GeoPhotoResponseReader.Result result = GeoPhotoResponseReader.read(
                            response.body().byteStream(), MetadataLimits.MAX_ITEMS,
                            MetadataLimits.MAX_METADATA_RESPONSE_BYTES);
                    positionHandler.post(() -> {
                        if (!requests.complete(call, generation)) return;
                        hideLoadingOverlay();
                        displayGeoPhotos(placeName, result);
                    });
                } catch (IOException | RuntimeException error) {
                    Log.e(TAG, "地点响应读取失败", error);
                    final String message = isMetadataLimit(error)
                            ? "地点响应超过安全大小，未显示结果；请返回按日期、相册或文件夹浏览。按确定可重试。"
                            : "地点结果读取失败，按确定重试；也可返回按日期或相册浏览";
                    positionHandler.post(() -> {
                        if (!requests.complete(call, generation)) return;
                        hideLoadingOverlay();
                        showGeoFailure(message);
                    });
                } finally {
                    response.close();
                }
            }
        });
    }

    private void showGeoFailure(String message) {
        mRowsAdapter.clear();
        currentMediaList = new ArrayList<>();
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(mCardPresenter);
        MediaItem retry = new MediaItem("__geo_retry", "确定：重试地点查询", "geo_retry", null, null);
        retry.setDateStr(message);
        adapter.add(retry);
        mRowsAdapter.add(new ListRow(new HeaderItem("地点加载失败"), adapter));
    }

    private void showLoadingOverlay(String message) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).showLoading(message);
                }
            });
        }
    }

    private void hideLoadingOverlay() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).hideLoading();
                }
            });
        }
    }

    private void displayGeoPhotos(String placeName, GeoPhotoResponseReader.Result result) {
        List<FnHttpApi.GalleryPhoto> photos = result.photos;
        switch (result.completeness) {
            case CLIENT_LIMIT:
                geoBoundaryMessage = "地点结果超过本次 " + MetadataLimits.MAX_ITEMS
                        + " 项上限，仅显示前一批；未验证地点续页接口，请返回按日期、相册或文件夹浏览";
                break;
            case SERVER_HAS_MORE:
                geoBoundaryMessage = "服务端显示仍有地点结果，但当前接口无可靠续页合同；结果不完整，请返回按日期或相册浏览";
                break;
            case SERVER_REPORTED_END:
                geoBoundaryMessage = "服务端报告本次地点结果结束；本应用无法独立核验地点结果完整性";
                break;
            default:
                geoBoundaryMessage = "地点接口未提供可靠结束标记；已显示本次返回结果，不能保证完整，请按日期或相册核对";
        }
        isPhotoListView = true;
        timelineItems = null;
        mRowsAdapter.clear();

        currentMediaList = new ArrayList<>();

        int itemsPerRow = 6;
        int totalRows = (int) Math.ceil((double) photos.size() / itemsPerRow);

        for (int row = 0; row < totalRows; row++) {
            int start = row * itemsPerRow;
            int end = Math.min(start + itemsPerRow, photos.size());

            HeaderItem header = row == 0 ? new HeaderItem("地点: " + placeName + " (" + photos.size() + "张)") : null;
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);

            for (int i = start; i < end; i++) {
                FnHttpApi.GalleryPhoto photo = photos.get(i);

                String thumbUrl = null;
                String mediaUrl = null;

                if (photo.additional != null && photo.additional.thumbnail != null) {
                    FnHttpApi.GalleryThumbnail thumbnail = photo.additional.thumbnail;
                    thumbUrl = absoluteUrl(thumbnail.mUrl != null ? thumbnail.mUrl : thumbnail.sUrl);
                    mediaUrl = absoluteUrl(thumbnail.originalUrl);
                    if ("video".equals(photo.category)) mediaUrl = absoluteUrl(thumbnail.videoUrl);
                }

                if ("video".equals(photo.category) && mediaUrl == null) {
                    mediaUrl = baseUrl + "/p/api/v1/stream/v/" + photo.id;
                } else if (mediaUrl == null) {
                    mediaUrl = thumbUrl;
                }
                MediaItem item = new MediaItem(
                    String.valueOf(photo.id),
                    photo.fileName,
                    photo.category,
                    thumbUrl,
                    mediaUrl
                );
                currentMediaList.add(item);
                rowAdapter.add(item);
            }

            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }
        addBoundaryNotice(geoBoundaryMessage);
    }

    public void loadPeople() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        beginRootScreen();
        showLoadingOverlay("正在加载人物...");
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/ai-person/list", "GET", "getAll=true&limit=-1&orderBy=0");

        enqueue(api.getPersonList(token, authx, true, -1, 0), new Callback<FnHttpApi.PersonListResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.PersonListResponse> call,
                                   Response<FnHttpApi.PersonListResponse> response) {
                hideLoadingOverlay();
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.PersonListResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayPeople(result.data.list);
                    } else {
                        showEmptyState("暂无人物");
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.PersonListResponse> call, Throwable t) {
                showRootLoadFailure(t);
            }
        });
    }

    private void displayPeople(List<FnHttpApi.PersonItem> persons) {
        if (rejectInvalidMetadata(persons)) return;
        peopleLimited |= persons.size() > MetadataLimits.MAX_ITEMS;
        persons = boundedMetadata(persons);
        isPhotoListView = false;
        timelineItems = null;
        savedPersonList = new ArrayList<>(persons);
        mRowsAdapter.clear();

        int itemsPerRow = 6;
        int totalRows = (int) Math.ceil((double) persons.size() / itemsPerRow);

        for (int row = 0; row < totalRows; row++) {
            int start = row * itemsPerRow;
            int end = Math.min(start + itemsPerRow, persons.size());

            HeaderItem header = row == 0 ? new HeaderItem("人物 (" + persons.size() + "人)") : null;
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);

            for (int i = start; i < end; i++) {
                FnHttpApi.PersonItem person = persons.get(i);

                String thumbUrl = person.faceId > 0 ? baseUrl + "/p/api/v1/stream/face/" + person.faceId : null;

                MediaItem item = new MediaItem(
                    String.valueOf(person.id),
                    person.name,
                    "person",
                    thumbUrl,
                    null
                );
                item.setPhotoCount(person.itemCount);
                rowAdapter.add(item);
            }

            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }
        if (peopleLimited) addUnpagedLimitNotice("人物目录");
    }

    public void loadPersonTimeline(int personId, String personName) {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        personLastVisibleIdx = -1;
        currentPersonId = personId;
        currentPersonName = personName;
        savedPersonTimelineItems = null;
        personTimelineLimited = false;

        beginScreen();
        showLoadingOverlay("正在加载 " + personName + " 的时间线...");
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/ai-person/photoLibrary/timeLine", "GET", "id=" + personId);

        enqueue(api.getPersonTimeline(token, authx, personId), new Callback<FnHttpApi.PersonTimelineResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.PersonTimelineResponse> call,
                                   Response<FnHttpApi.PersonTimelineResponse> response) {
                hideLoadingOverlay();
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.PersonTimelineResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayPersonTimeline(result.data.list);
                    } else {
                        showEmptyState(personName + " 暂无照片");
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.PersonTimelineResponse> call, Throwable t) {
                showRootLoadFailure(t);
            }
        });
    }

    private void displayPersonTimeline(List<FnHttpApi.PersonTimelineItem> items) {
        if (rejectInvalidMetadata(items)) return;
        personTimelineLimited |= items.size() > MetadataLimits.MAX_ITEMS;
        items = boundedMetadata(items);
        previewScreen = 2;
        isPhotoListView = false;
        mRowsAdapter.clear();
        savedPersonTimelineItems = new ArrayList<>(items);
        personDateItems.clear();
        personTimelineItemsList.clear();
        personLoadedIdx.clear();

        String currentYearMonth = "";
        ArrayObjectAdapter currentRowAdapter = null;

        for (FnHttpApi.PersonTimelineItem item : items) {
            String yearMonth = item.year + "年" + item.month + "月";
            String dateStr = item.year + "-" + String.format("%02d", item.month) + "-" + String.format("%02d", item.day);
            String title = item.month + "月" + item.day + "日";
            String suffix = " (" + item.itemCount + "张)";

            if (!yearMonth.equals(currentYearMonth)) {
                currentYearMonth = yearMonth;
                String headerTitle = currentPersonName != null ? currentPersonName + " - " + yearMonth : yearMonth;
                HeaderItem header = new HeaderItem(headerTitle);
                currentRowAdapter = new ArrayObjectAdapter(mCardPresenter);
                mRowsAdapter.add(new ListRow(header, currentRowAdapter));
            }

            MediaItem mediaItem = new MediaItem(dateStr, title + suffix, item.itemCount);
            mediaItem.setType("person_date");
            currentRowAdapter.add(mediaItem);
            previewRows.put(mediaItem, currentRowAdapter);
            personDateItems.add(mediaItem);
            personTimelineItemsList.add(item);
        }

        if (personTimelineLimited) addUnpagedLimitNotice("人物日期");
        // 触发第一个可见区域的预览加载
        lazyLoadHandler.postDelayed(() -> {
            loadVisiblePersonPreviews(0);
        }, 300);
        restorePreviewPosition(personDateItems, personLastVisibleIdx);
    }

    public void loadPersonPhotosByDate(String dateStr, int itemCount) {
        if (api == null || token == null || token.isEmpty() || currentPersonId < 0) return;
        beginScreen();
        photoDate = dateStr;
        photoPersonId = currentPersonId;
        startPhotoPaging("person", currentPersonName + " - " + dateStr);
    }

    private void openMediaDetail(MediaItem mediaItem) {
        if (currentMediaList == null || currentMediaList.isEmpty()) {
            currentMediaList = new ArrayList<>();
            currentMediaList.add(mediaItem);
        }

        int index = 0;
        for (int i = 0; i < currentMediaList.size(); i++) {
            if (currentMediaList.get(i).getId().equals(mediaItem.getId())) {
                index = i;
                break;
            }
        }

        if (photoPaging == null) {
            final String boundary = geoBoundaryMessage;
            MediaListHolder.set(currentMediaList, boundary == null ? null : new MediaListHolder.PageSource() {
                @Override public boolean hasMore() { return false; }
                @Override public String getBoundaryMessage() { return boundary; }
                @Override public void loadMore(MediaListHolder.PageCallback callback) {
                    callback.onPaused(boundary);
                }
            });
        } else {
            final long generation = requests.generation();
            MediaListHolder.set(currentMediaList, new MediaListHolder.PageSource() {
                private final MainPagingState.DeliveryCursor delivery =
                        new MainPagingState.DeliveryCursor(currentMediaList.size());
                @Override public boolean hasMore() {
                    return requests.isCurrent(generation) && photoPaging != null
                            && (delivery.hasPending(currentMediaList.size()) || (photoPaging.hasMore()
                            && !isWindowFull(photoPaging, currentMediaList.size())));
                }
                @Override public String getBoundaryMessage() {
                    if (!requests.isCurrent(generation) || photoPaging == null)
                        return "列表已切换，请返回列表重新打开";
                    if (isWindowFull(photoPaging, currentMediaList.size()))
                        return "本批已达内存上限，请返回列表选择下一批";
                    if (photoPaging.isStalled()) return "服务器返回重复或矛盾空页；结果可能不完整，请返回列表";
                    if (photoPaging.isOffsetLimited()) return "已到接口偏移上限，请返回列表缩小查询范围";
                    return null;
                }
                @Override public void loadMore(MediaListHolder.PageCallback callback) {
                    if (!hasMore() || detailPageCallback != null) {
                        callback.onFailure();
                        return;
                    }
                    // A prefetch can finish (including its terminal page) after detail copied its list.
                    // Deliver that buffered tail before consulting/fetching the next network page.
                    if (delivery.hasPending(currentMediaList.size())) {
                        delivery.delivered(currentMediaList.size());
                        callback.onLoaded(new ArrayList<>(currentMediaList), photoPaging.hasMore()
                                && !isWindowFull(photoPaging, currentMediaList.size()));
                        return;
                    }
                    detailPageCallback = new MediaListHolder.PageCallback() {
                        @Override public void onLoaded(List<MediaItem> items, boolean hasMore) {
                            delivery.delivered(items.size());
                            callback.onLoaded(items, hasMore);
                        }
                        @Override public void onFailure() { callback.onFailure(); }
                        @Override public void onPaused(String message) { callback.onPaused(message); }
                    };
                    // A list prefetch may already be in flight; its completion also serves this callback.
                    if (!photoPaging.isLoading()) loadPhotoPage(true);
                }
            });
        }
        Intent intent = new Intent(getActivity(), MediaDetailActivity.class);
        intent.putExtra("CURRENT_INDEX", index);
        startActivity(intent);
    }

    private void openFolderBrowse(MediaItem folderItem) {
        // 获取文件夹路径
        String folderPath = folderItem.getMediaUrl(); // 我们之前将路径保存在 mediaUrl 中
        if (folderPath == null || folderPath.isEmpty()) {
            Log.e(TAG, "文件夹路径为空");
            return;
        }

        Intent intent = new Intent(getActivity(), FolderBrowseActivity.class);
        intent.putExtra("FOLDER_PATH", folderPath);
        intent.putExtra("FOLDER_NAME", folderItem.getTitle());
        startActivity(intent);
    }

    @Override
    public void onDestroy() {
        beginScreen();
        requests.close();
        super.onDestroy();
        lazyLoadHandler.removeCallbacksAndMessages(null);
        positionHandler.removeCallbacksAndMessages(null);
        if (tokenChangeListener != null && getActivity() != null) {
            getActivity().getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(tokenChangeListener);
        }
    }
}
