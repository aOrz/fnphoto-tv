package com.fnphoto.tv.paging;

import org.junit.Test;
import java.util.Arrays;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class FolderNavigationStateTest {
    @Test public void backReturnsPathOnlyAndDoesNotRetainMediaLists() {
        FolderNavigationState state = new FolderNavigationState("/root", "Root");
        assertTrue(state.enter("/root/child", "Child"));
        assertTrue(state.enter("/root/child/grandchild", "Grandchild"));
        assertEquals(2, state.getDepth());
        assertTrue(state.back());
        assertEquals("/root/child", state.getPath());
        assertEquals("Child", state.getName());
        assertTrue(state.back());
        assertFalse(state.back());
        assertEquals("/root", state.getPath());
    }

    @Test public void repeatedCyclesCannotGrowHistory() {
        FolderNavigationState state = new FolderNavigationState("/root", "Root");
        assertFalse(state.enter("/root", "Same"));
        assertTrue(state.enter("/child", "Child"));
        assertFalse(state.enter("/root", "Cycle"));
        assertEquals(1, state.getDepth());
        assertEquals("/child", state.getPath());
    }

    @Test public void depthBoundRejectsWithoutChangingCurrentFolder() {
        FolderNavigationState state = new FolderNavigationState("/root", "Root");
        for (int i = 0; i < FolderNavigationState.MAX_HISTORY; i++) assertTrue(state.enter("/" + i, "Folder"));
        assertFalse(state.enter("/too-deep", "Too deep"));
        assertEquals(FolderNavigationState.MAX_HISTORY, state.getDepth());
        assertTrue(state.back());
        assertTrue(state.enter("/other", "Other"));
    }

    @Test public void pathLengthIsBoundedAndInvalidNavigationIsAtomic() {
        FolderNavigationState state = new FolderNavigationState("/root", "Root");
        char[] chars = new char[FolderNavigationState.MAX_PATH_CHARS + 1];
        Arrays.fill(chars, 'a');
        assertFalse(state.enter(new String(chars), "Long"));
        assertFalse(state.enter(null, "Null"));
        assertEquals("/root", state.getPath());
        assertEquals(0, state.getDepth());
    }

    @Test public void displayNamesAreBoundedWithoutChangingServerPath() {
        char[] chars = new char[10000];
        Arrays.fill(chars, 'n');
        FolderNavigationState state = new FolderNavigationState("/exact-server-path", new String(chars));
        assertEquals("/exact-server-path", state.getPath());
        assertEquals(FolderNavigationState.MAX_NAME_CHARS, state.getName().length());
    }

    @Test public void savedHistoryRestoresBackNavigationAndIsDefensivelyCopied() {
        FolderNavigationState state = new FolderNavigationState("/root", "Root");
        state.enter("/child", "Child");
        ArrayList<String> paths = state.savedPaths();
        ArrayList<String> names = state.savedNames();
        FolderNavigationState restored = FolderNavigationState.restore(paths, names);
        paths.clear(); names.clear();
        assertEquals("/child", restored.getPath());
        assertTrue(restored.back());
        assertEquals("Root", restored.getName());
        assertEquals("/child", state.getPath());
    }

    @Test(expected = IllegalArgumentException.class) public void invalidSavedHistoryRejected() {
        FolderNavigationState.restore(Arrays.asList("/root", "/root"), Arrays.asList("Root", "Root"));
    }

    @Test(expected = IllegalArgumentException.class) public void oversizedSavedHistoryRejectedBeforeCopy() {
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < 100000; i++) { paths.add("/" + i); names.add("Folder"); }
        FolderNavigationState.restore(paths, names);
    }
}
