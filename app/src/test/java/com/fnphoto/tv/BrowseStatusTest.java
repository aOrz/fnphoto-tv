package com.fnphoto.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class BrowseStatusTest {
    @Test public void emptyRequiresSuccessfulEndNotLoadingFailureOrPause() {
        MainPagingState state = new MainPagingState(35);
        assertEquals(BrowseStatus.Page.CONTINUE, BrowseStatus.page(state, 0, false));
        state.begin();
        assertEquals(BrowseStatus.Page.LOADING, BrowseStatus.page(state, 0, false));
        state.fail();
        assertEquals(BrowseStatus.Page.ERROR, BrowseStatus.page(state, 0, false));
        assertEquals(0, state.begin());
        state.accept(Arrays.asList("1"), null);
        assertEquals(BrowseStatus.Page.CONTINUE, BrowseStatus.page(state, 0, true));
        assertEquals(1, state.begin());
        state.accept(Collections.emptyList(), false);
        assertEquals(BrowseStatus.Page.EMPTY, BrowseStatus.page(state, 0, false));
    }

    @Test public void contradictoryEmptyPageIsIncomplete() {
        MainPagingState state = new MainPagingState(35);
        state.begin();
        state.accept(Collections.emptyList(), true);
        assertEquals(BrowseStatus.Page.INCOMPLETE, BrowseStatus.page(state, 0, false));
    }

    @Test public void failureWithContentKeepsContentVisible() {
        MainPagingState state = new MainPagingState(35);
        state.begin();
        state.fail();
        assertEquals(BrowseStatus.Page.CONTENT, BrowseStatus.page(state, 1, false));
    }

    @Test public void permissionAndAuthenticationDoNotClaimEmpty() {
        assertEquals("登录已失效", BrowseStatus.httpFailure(401).title);
        assertEquals("没有访问权限", BrowseStatus.httpFailure(403).title);
        assertEquals("加载失败", BrowseStatus.httpFailure(500).title);
        assertEquals("加载失败", BrowseStatus.httpFailure(200).title);
    }
}
