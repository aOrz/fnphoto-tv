package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.fnphoto.tv.api.FnAuthUtils;
import com.fnphoto.tv.api.FnHttpApi;
import com.fnphoto.tv.api.HttpClientProvider;
import java.util.ArrayList;
import java.util.List;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends FragmentActivity {
    private static final String TAG = "MainActivity";
    private DrawerLayout drawerLayout;
    private VerticalGridView menuGrid;
    private MenuAdapter menuAdapter;
    private TextView tvMode;
    private TvStateView browseState;
    private boolean browseLoading;
    private boolean browseMenuActive;
    private FnHttpApi api;
    private String token;
    private String secret;
    private String baseUrl;
    private SharedPreferences prefs;
    private BrowseSelection browseSelection;
    private BrowseMode currentBrowseMode = BrowseMode.GALLERY;
    private BrowseMode initialBrowseMode;
    private boolean initialBrowseStarted;
    
    // 双击返回键退出相关
    private boolean isBackPressedOnce = false;
    private final Handler backHandler = new Handler(Looper.getMainLooper());
    private static final int BACK_PRESS_INTERVAL = 2000; // 2秒内双击
    
    // 菜单项数据类
    public static class MenuItem {
        private String title;
        private String action;

        public MenuItem(String title, String action) {
            this.title = title;
            this.action = action;
        }

        public String getTitle() { return title; }
        public String getAction() { return action; }
    }
    
    // RecyclerView Adapter for menu
    public class MenuAdapter extends RecyclerView.Adapter<MenuAdapter.ViewHolder> {
        private List<MenuItem> items = new ArrayList<>();
        
        public void add(MenuItem item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }
        
        public MenuItem get(int position) {
            return items.get(position);
        }
        
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_menu, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            MenuItem item = items.get(position);
            holder.textView.setText(item.title);
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            
            ViewHolder(View itemView) {
                super(itemView);
                textView = itemView.findViewById(R.id.menu_text);
                
                itemView.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        handleMenuSelection(items.get(pos));
                    }
                });
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置全屏模式，隐藏状态栏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        setContentView(R.layout.activity_main);
        browseState = new TvStateView(this);
        ((FrameLayout) findViewById(R.id.browse_state_container)).addView(browseState,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("nas_url", "");
        token = prefs.getString("api_token", "");
        secret = prefs.getString("secret", "");

        String account = prefs.getString(BrowseSelection.ACCOUNT_KEY, "");
        if (account.isEmpty()) {
            // Upgrade existing remembered sessions without binding preferences to a rotating token.
            String source = com.fnphoto.tv.api.RememberedNasTarget.source(
                    prefs.getString("saved_source", ""), prefs.getString("saved_url", ""));
            account = BrowseSelection.accountId(source, prefs.getString("saved_user", ""));
            if (!account.isEmpty()) prefs.edit().putString(BrowseSelection.ACCOUNT_KEY, account).apply();
        }
        browseSelection = new BrowseSelection(new BrowseSelection.Store() {
            @Override public String get(String key) {
                try { return prefs.getString(key, ""); }
                catch (ClassCastException invalidPreference) { prefs.edit().remove(key).apply(); return ""; }
            }
            @Override public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
            @Override public void remove(String key) { prefs.edit().remove(key).apply(); }
        }, account);
        initialBrowseMode = browseSelection.initial();

        // 初始化侧滑菜单
        setupSideMenu();

        // 添加 MainFragment
        setupMainFragment();

        setModeText(initialBrowseMode.title);

        // 初始化 API 并获取相册应用版本
        initApiAndGetVersion();
    }

    private void setupSideMenu() {
        drawerLayout = findViewById(R.id.drawer_layout);
        menuGrid = findViewById(R.id.menu_grid);
        tvMode = findViewById(R.id.tv_current_mode);

        // 设置菜单适配器
        menuAdapter = new MenuAdapter();
        
        // 添加菜单项
        // 第一组：浏览
        menuAdapter.add(new MenuItem("图库", "gallery"));
        menuAdapter.add(new MenuItem("文件夹", "folders"));
        menuAdapter.add(new MenuItem("收藏", "favorites"));
        menuAdapter.add(new MenuItem("最近添加", "recent"));
        menuAdapter.add(new MenuItem("搜索", "search"));
        
        // 第二组：相册分类
        menuAdapter.add(new MenuItem("相册", "albums"));
        menuAdapter.add(new MenuItem("共享", "shared"));
        menuAdapter.add(new MenuItem("人物", "people"));
        menuAdapter.add(new MenuItem("地点", "places"));
        menuAdapter.add(new MenuItem("标签", "tags"));
        menuAdapter.add(new MenuItem("智能分类", "smart"));
        menuAdapter.add(new MenuItem("媒体类型", "media_types"));
        
        // 第三组：设置
        menuAdapter.add(new MenuItem("设置", "settings"));
        
        // 第四组：退出登录
        menuAdapter.add(new MenuItem("退出登录", "logout"));

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerSlide(View drawerView, float slideOffset) {
                if (slideOffset > 0) browseMenuActive = true;
            }
            @Override public void onDrawerStateChanged(int state) {
                // Reversing an opening animation can finish without onDrawerClosed in DrawerLayout 1.1.1.
                if (state == DrawerLayout.STATE_IDLE && browseMenuActive
                        && !drawerLayout.isDrawerVisible(GravityCompat.END)) {
                    browseMenuActive = false;
                    MainFragment fragment = (MainFragment) getSupportFragmentManager()
                            .findFragmentById(R.id.main_content_container);
                    if (fragment != null) fragment.focusBrowseContent();
                }
            }
            @Override public void onDrawerClosed(View drawerView) {
                browseMenuActive = false;
                MainFragment fragment = (MainFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.main_content_container);
                if (fragment != null) fragment.focusBrowseContent();
            }
        });

        menuGrid.setAdapter(menuAdapter);
        menuGrid.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_HIGH_EDGE);
        menuGrid.setWindowAlignmentOffset(48);
        menuGrid.setWindowAlignmentOffsetPercent(VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);

        // 顶栏「浏览」按钮打开导航面板
        View menuButton = findViewById(R.id.btn_menu);
        if (menuButton != null) {
            menuButton.setFocusable(true);
            menuButton.setOnClickListener(v -> openBrowseMenu());
        }
    }

    private void setupMainFragment() {
        // FragmentManager has already recreated this instance after process/configuration recreation.
        // Replacing it here would cancel its startup load and leave the replacement uninitialized.
        if (getSupportFragmentManager().findFragmentById(R.id.main_content_container) != null) return;
        MainFragment fragment = new MainFragment();
        Bundle args = new Bundle();
        args.putString("nas_url", baseUrl);
        args.putString("api_token", token);
        args.putString("secret", secret);
        fragment.setArguments(args);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_content_container, fragment)
                .commit();
    }

    void openBrowseMenu() {
        // Block async focus immediately, including the first frame of the opening animation.
        browseMenuActive = true;
        for (int i = 0; i < menuAdapter.getItemCount(); i++) {
            if (currentBrowseMode.menuAction().equals(menuAdapter.get(i).getAction())) {
                menuGrid.setSelectedPosition(i);
                break;
            }
        }
        drawerLayout.openDrawer(GravityCompat.END);
        menuGrid.requestFocus();
    }

    private void closeBrowseMenu() {
        browseMenuActive = false;
        drawerLayout.closeDrawer(GravityCompat.END);
        // If opening was canceled before its first frame, no drawer callback may follow.
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) fragment.focusBrowseContent();
    }

    @Override protected void onStart() {
        super.onStart();
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) startInitialBrowse(fragment);
    }

    private void startInitialBrowse(MainFragment fragment) {
        if (initialBrowseStarted || browseSelection == null || isFinishing()) return;
        if (getSupportFragmentManager().findFragmentById(R.id.main_content_container) != fragment) return;
        initialBrowseStarted = true;
        fragment.loadBrowseMode(initialBrowseMode);
    }

    void retryBrowseMode(BrowseMode mode) { selectBrowseMode(mode); }

    private void selectBrowseMode(BrowseMode mode) {
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null && browseSelection.select(mode.id)) fragment.loadBrowseMode(mode);
    }

    void onBrowseModeStarted(BrowseMode mode) {
        currentBrowseMode = mode;
        setModeText(mode.title);
    }

    void onBrowseModeLoaded(BrowseMode mode) {
        if (isFinishing() || mode != currentBrowseMode) return;
        browseSelection.succeeded(mode, prefs.getString(BrowseSelection.ACCOUNT_KEY, ""));
    }

    void onBrowseModeFailed(BrowseMode mode) {
        if (isFinishing()) return;
        BrowseMode fallback = browseSelection.failed(mode);
        if (fallback != null) {
            Toast.makeText(this, "上次浏览类型暂不可用，已返回图库；保留原选择供下次重试", Toast.LENGTH_LONG).show();
            MainFragment fragment = (MainFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.main_content_container);
            if (fragment != null) fragment.loadBrowseMode(fallback);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && drawerLayout != null && getCurrentFocus() == null) {
            MainFragment fragment = (MainFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.main_content_container);
            if (fragment != null) fragment.focusBrowseContent();
        }
    }

    boolean canFocusBrowseContent() {
        return !isFinishing() && hasWindowFocus() && !browseMenuActive
                && !drawerLayout.isDrawerVisible(GravityCompat.END);
    }

    void focusBrowseMenuButton() {
        if (canFocusBrowseContent()) findViewById(R.id.btn_menu).requestFocus();
    }

    private void setModeText(String mode) {
        if (tvMode != null) tvMode.setText(mode);
    }

    public void showLoading(String message) {
        showBrowseState(message == null ? "正在加载" : message,
                "正在从 NAS 获取内容，请稍候。也可切换其他浏览方式。", "浏览其他内容", this::openBrowseMenu);
        browseLoading = true;
        browseState.setLoading(true);
    }

    public void hideLoading() {
        if (browseLoading) clearBrowseState();
    }

    void showBrowseState(String title, String message, String action, Runnable onAction) {
        browseLoading = false;
        browseState.bind(title, message, action, onAction);
        findViewById(R.id.main_content_container).setVisibility(View.INVISIBLE);
        findViewById(R.id.browse_state_container).setVisibility(View.VISIBLE);
    }

    void clearBrowseState() {
        browseLoading = false;
        browseState.setVisibility(View.GONE);
        findViewById(R.id.browse_state_container).setVisibility(View.GONE);
        findViewById(R.id.main_content_container).setVisibility(View.VISIBLE);
    }

    boolean focusBrowseState() {
        return canFocusBrowseContent() && browseState.focusAction();
    }

    boolean hasBrowseStateFocus() {
        return browseState.isActionFocused();
    }

    private void handleMenuSelection(MenuItem item) {
        Log.d(TAG, "Menu selected: " + item.getAction());
        BrowseMode mode = BrowseMode.fromId(item.getAction());
        if (mode != null) {
            closeBrowseMenu();
            selectBrowseMode(mode);
            return;
        }
        switch (item.getAction()) {
            case "search":
                openSearch();
                return;
            case "settings":
                openSettings();
                return;
            case "logout":
                logout();
                return;
            case "media_types":
                closeBrowseMenu();
                new android.app.AlertDialog.Builder(this)
                    .setTitle("媒体类型")
                    .setItems(new String[]{"照片", "视频"}, (dialog, which) -> {
                        selectBrowseMode(which == 0 ? BrowseMode.PHOTOS : BrowseMode.VIDEOS);
                    }).setNegativeButton("取消", null).show();
                return;
            case "map":
            case "tags":
            case "smart":
                new android.app.AlertDialog.Builder(this)
                    .setTitle(item.getTitle())
                    .setMessage("此功能尚未接入：当前缺少可验证的飞牛相册接口协议。需要对应 NAS 版本的接口文档或脱敏请求/响应样本，不能将其他列表冒充为此功能。")
                    .setPositiveButton("知道了", null).show();
                closeBrowseMenu();
                return;
        }
        
        closeBrowseMenu();
    }
    
    private void openSettings() {
        closeBrowseMenu();
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }
    
    private void openSearch() {
        closeBrowseMenu();
        Intent intent = new Intent(this, SearchActivity.class);
        startActivity(intent);
    }
    
    private void logout() {
        browseSelection.logout();
        com.fnphoto.tv.api.FnWebSocketClient.closeAuthenticatedSession();
        // 清除所有登录信息
        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        prefs.edit()
            .remove(BrowseSelection.ACCOUNT_KEY)
            .remove("nas_url")
            .remove("login_session")
            .remove("api_token")
            .remove("secret")
            .remove("backId")
            .remove("has_credentials")
            .remove("saved_url")
            .remove("saved_source")
            .remove("saved_user")
            .remove("saved_pass")
            .apply();
        
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
        
        // 跳转到登录界面
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // 菜单键切换侧滑菜单
            if (browseMenuActive || drawerLayout.isDrawerVisible(GravityCompat.END)) {
                closeBrowseMenu();
            } else {
                openBrowseMenu();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (browseMenuActive || drawerLayout.isDrawerVisible(GravityCompat.END)) {
                closeBrowseMenu();
                return true;
            }
            
            // 检查 MainFragment 是否需要处理返回键（返回到时间线）
            MainFragment fragment = (MainFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.main_content_container);
            if (fragment != null && fragment.onBackPressed()) {
                return true;  // MainFragment 处理了返回键
            }
            
            // 处理双击返回键退出
            if (isBackPressedOnce) {
                // 第二次点击，退出应用
                finish();
                return true;
            }
            
            // 第一次点击
            isBackPressedOnce = true;
            Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
            
            // 2秒后重置状态
            backHandler.postDelayed(() -> isBackPressedOnce = false, BACK_PRESS_INTERVAL);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void initApiAndGetVersion() {
        if (baseUrl.isEmpty() || token.isEmpty() || secret.isEmpty()) {
            Log.e(TAG, "Missing credentials - cannot fetch version");
            return;
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(HttpClientProvider.getClient(this))
                .build();
        api = retrofit.create(FnHttpApi.class);

        // 生成认证头
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/app/version", "GET", null);
        
        Log.d(TAG, "Fetching album app version from: " + baseUrl);
        
        api.getAppVersion(token, authx).enqueue(new Callback<FnHttpApi.AppVersionResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.AppVersionResponse> call, 
                                  Response<FnHttpApi.AppVersionResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.AppVersionResponse result = response.body();
                    if (result.isSuccessful() && result.data != null) {
                        Log.i(TAG, "========================================");
                        Log.i(TAG, "相册应用版本信息:");
                        Log.i(TAG, "  版本号: " + result.data.version);
                        Log.i(TAG, "========================================");
                        // Optional metadata must never trigger or replace the chosen browse screen.
                        getPhotoStats();
                    } else {
                        Log.w(TAG, "获取版本失败: errno=" + result.errno + ", result=" + result.result);
                    }
                } else {
                    Log.e(TAG, "HTTP错误: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.AppVersionResponse> call, Throwable t) {
                Log.e(TAG, "网络请求失败: " + t.getMessage(), t);
            }
        });
    }

    private void getPhotoStats() {
        if (api == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化或token为空");
            return;
        }

        // 生成认证头
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/user_photo/stat", "GET", null);

        Log.d(TAG, "Fetching photo stats...");

        api.getPhotoStats(token, authx).enqueue(new Callback<FnHttpApi.PhotoStatsResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.PhotoStatsResponse> call,
                                   Response<FnHttpApi.PhotoStatsResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.PhotoStatsResponse result = response.body();
                    if (result.code == 0 && result.data != null) {
                        Log.i(TAG, "========================================");
                        Log.i(TAG, "相册统计信息:");
                        Log.i(TAG, "  照片数量: " + result.data.photoCount);
                        Log.i(TAG, "  视频数量: " + result.data.videoCount);
                        Log.i(TAG, "  是否管理员: " + result.data.isAdmin);
                        Log.i(TAG, "========================================");
                    } else {
                        Log.w(TAG, "获取统计失败: code=" + result.code + ", msg=" + result.msg);
                    }
                } else {
                    Log.e(TAG, "HTTP错误: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.PhotoStatsResponse> call, Throwable t) {
                Log.e(TAG, "获取统计信息失败: " + t.getMessage(), t);
            }
        });
    }

    @Override protected void onDestroy() {
        backHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
