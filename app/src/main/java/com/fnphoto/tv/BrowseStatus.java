package com.fnphoto.tv;

/** Presentation only: never advances, resets or retries a paging cursor. */
final class BrowseStatus {
    enum Page { CONTENT, LOADING, ERROR, INCOMPLETE, CONTINUE, EMPTY }

    final String title;
    final String message;

    BrowseStatus(String title, String message) {
        this.title = title;
        this.message = message;
    }

    static BrowseStatus empty(BrowseMode mode) {
        switch (mode) {
            case FOLDERS: return new BrowseStatus("还没有可浏览的文件夹", "可在 NAS 相册中检查已添加的媒体目录，或切换其他浏览方式。");
            case FAVORITES: return new BrowseStatus("还没有收藏", "在照片或视频详情中添加收藏后，可在这里查看。");
            case RECENT: return new BrowseStatus("最近没有新增内容", "新添加到 NAS 相册的照片和视频会显示在这里。");
            case ALBUMS: return new BrowseStatus("还没有相册", "可在 NAS 相册中创建相册并添加照片，或先浏览图库。");
            case SHARED: return new BrowseStatus("还没有共享相册", "其他人分享给你的相册会显示在这里。");
            case PEOPLE: return new BrowseStatus("暂无人物分类", "NAS 相册返回的人物分类会显示在这里，也可按日期浏览。");
            case PLACES: return new BrowseStatus("暂无地点分类", "带有地点信息的分类会显示在这里，也可按日期浏览。");
            case PHOTOS: return new BrowseStatus("暂无照片", "当前图库中没有可显示的照片，可切换其他浏览方式。");
            case VIDEOS: return new BrowseStatus("暂无视频", "当前图库中没有可显示的视频，可切换其他浏览方式。");
            default: return new BrowseStatus("图库中还没有照片或视频", "添加到 NAS 相册的内容会显示在这里，也可查看其他分类。");
        }
    }

    static BrowseStatus httpFailure(int status) {
        if (status == 401) return new BrowseStatus("登录已失效", "请检查登录状态；可重试，或从浏览菜单退出后重新登录。");
        if (status == 403) return new BrowseStatus("没有访问权限", "请确认当前账号有权访问这些内容，再按确定重试。");
        return new BrowseStatus("加载失败", "未能取得有效结果，请检查网络和 NAS 服务后重试。");
    }

    static Page page(MainPagingState state, int count, boolean scanPaused) {
        if (count > 0) return Page.CONTENT;
        if (state.isLoading()) return Page.LOADING;
        if (state.hasFailed() || state.hasInvalidPage()) return Page.ERROR;
        if (state.isStalled() || state.isOffsetLimited()) return Page.INCOMPLETE;
        if (scanPaused || state.hasMore()) return Page.CONTINUE;
        return Page.EMPTY;
    }
}
