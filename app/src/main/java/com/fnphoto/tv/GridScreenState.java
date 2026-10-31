package com.fnphoto.tv;

import com.fnphoto.tv.paging.GridPageState;

/** Presentation state only: does not change offsets, request ownership, or scan/window budgets. */
final class GridScreenState {
    enum Kind { INPUT, LOADING, EMPTY, NO_RESULTS, EMPTY_WINDOW, ERROR, AUTHENTICATION, PERMISSION, BOUNDARY, CONTENT }
    enum Failure {
        REQUEST, AUTHENTICATION, PERMISSION;

        static Failure fromHttpStatus(int status) {
            if (status == 401) return AUTHENTICATION;
            if (status == 403) return PERMISSION;
            return REQUEST;
        }
    }

    static Kind search(String query, boolean queryReady, int count, GridPageState pages, Failure failure) {
        if (query.isEmpty()) return Kind.INPUT;
        if (pages.hasError()) return failed(failure);
        if (boundary(pages)) return Kind.BOUNDARY;
        if (!queryReady || pages.isLoading()) return Kind.LOADING;
        if (count > 0) return Kind.CONTENT;
        // A request not yet started or an unfinished page sequence is never proof of no matches.
        if (pages.hasMore()) return Kind.LOADING;
        return hasPreviousWindow(pages) ? Kind.EMPTY_WINDOW : Kind.NO_RESULTS;
    }

    static Kind folder(boolean foldersLoading, boolean foldersFailed, int folderCount, int photoCount,
            GridPageState pages, Failure failure) {
        if (foldersFailed || pages.hasError()) return failed(failure);
        if (boundary(pages)) return Kind.BOUNDARY;
        if (foldersLoading || pages.isLoading()) return Kind.LOADING;
        if (folderCount > 0 || photoCount > 0) return Kind.CONTENT;
        if (pages.hasMore()) return Kind.LOADING;
        return hasPreviousWindow(pages) ? Kind.EMPTY_WINDOW : Kind.EMPTY;
    }

    private static boolean hasPreviousWindow(GridPageState pages) {
        // nextWindow clears retained rows but preserves the raw server offset.
        // An empty terminal page then says nothing about the discarded earlier results.
        return pages.getOffset() > pages.getWindowRows();
    }

    private static boolean boundary(GridPageState pages) {
        return pages.isWindowFull() || pages.isOffsetLimit() || pages.isStalled();
    }

    private static Kind failed(Failure failure) {
        if (failure == Failure.AUTHENTICATION) return Kind.AUTHENTICATION;
        if (failure == Failure.PERMISSION) return Kind.PERMISSION;
        return Kind.ERROR;
    }

    private GridScreenState() {}
}
