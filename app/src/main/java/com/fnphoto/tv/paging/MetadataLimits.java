package com.fnphoto.tv.paging;

/** Client safety budgets, not claims about the size or completeness of the NAS library. */
public final class MetadataLimits {
    public static final int MAX_ITEMS = 1200;
    public static final int MAX_SCAN_PAGES = 5;
    public static final long MAX_METADATA_RESPONSE_BYTES = 8L * 1024 * 1024;
    public static final String WINDOW_MESSAGE = "已到本批浏览上限，请返回列表选择下一批；并非全部内容";
    private MetadataLimits() {}
}
