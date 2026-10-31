package com.fnphoto.tv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class DetailPreloadScopeTest {
    @Test public void forwardNavigationKeepsHandoffUntilFullImageCompletes() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("b"));
        scope.select("b");
        scope.setNext("c", () -> cancelled.add("c"));

        // Navigation and the new view waiting for measurement must not cancel b.
        assertTrue(scope.contains("b"));
        assertTrue(scope.contains("c"));
        assertTrue(cancelled.isEmpty());
        scope.complete("b");
        assertEquals(Arrays.asList("b"), cancelled);
        assertFalse(scope.contains("b"));
        assertTrue(scope.contains("c"));
        scope.complete("b");
        scope.clear();
        scope.clear();
        assertEquals(Arrays.asList("b", "c"), cancelled);
    }

    @Test public void rapidForwardNavigationReleasesOldHandoffAndKeepsOnlyNewCurrentAndNext() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final String url = "photo-" + i;
            scope.setNext(url, () -> cancelled.add(url));
            scope.select(url);
            if (i > 0) assertFalse(scope.contains("photo-" + (i - 1)));
            assertTrue(scope.contains(url));
            assertEquals(i, cancelled.size());
        }
        scope.setNext("ahead", () -> cancelled.add("ahead"));
        assertEquals(99, cancelled.size());
        scope.clear();
        assertEquals(101, cancelled.size());
    }

    @Test public void backwardNavigationOrJumpClearsUnrelatedRequests() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("b"));
        scope.select("b");
        scope.setNext("c", () -> cancelled.add("c"));
        scope.select("a");
        assertEquals(Arrays.asList("b", "c"), cancelled);
        assertFalse(scope.contains("b"));
        assertFalse(scope.contains("c"));
    }

    @Test public void sameUrlSelectionKeepsCurrentButDoesNotAccumulateDuplicatePreloads() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("original"));
        scope.select("b");
        scope.select("b");
        scope.setNext("b", () -> cancelled.add("duplicate"));
        assertEquals(Arrays.asList("duplicate"), cancelled);
        assertTrue(scope.contains("b"));
        scope.complete("b");
        assertEquals(Arrays.asList("duplicate", "original"), cancelled);
    }

    @Test public void completionOfOldPhotoCannotReleaseNewCurrentOrNext() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("b"));
        scope.select("b");
        scope.setNext("c", () -> cancelled.add("c"));
        scope.select("c");
        scope.setNext("d", () -> cancelled.add("d"));
        scope.complete("b");
        scope.complete("d");
        assertEquals(Arrays.asList("b"), cancelled);
        assertTrue(scope.contains("c"));
        assertTrue(scope.contains("d"));
    }

    @Test public void fullImageMemoryHitCanCompleteBeforeFollowingPreloadStarts() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("b"));
        scope.select("b");
        scope.complete("b");
        scope.setNext("c", () -> cancelled.add("c"));
        assertEquals(Arrays.asList("b"), cancelled);
        assertTrue(scope.contains("c"));
        scope.select("c");
        assertEquals(Arrays.asList("b"), cancelled);
    }

    @Test public void replacingNextDoesNotCancelCurrentHandoff() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("b"));
        scope.select("b");
        scope.setNext("c", () -> cancelled.add("c"));
        scope.setNext("d", () -> cancelled.add("d"));
        assertEquals(Arrays.asList("c"), cancelled);
        assertTrue(scope.contains("b"));
        assertTrue(scope.contains("d"));
    }

    @Test public void videoOrMissingPhotoAndLifecycleStopReleaseEverythingExactlyOnce() {
        DetailPreloadScope scope = new DetailPreloadScope();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("b"));
        scope.select("b");
        scope.setNext("c", () -> cancelled.add("c"));
        scope.select(null);
        scope.complete("b");
        scope.clear();
        assertEquals(Arrays.asList("b", "c"), cancelled);
        assertFalse(scope.contains(null));
        scope.setNext("", () -> cancelled.add("empty"));
        scope.setNext(null, () -> cancelled.add("null"));
        scope.clear();
        assertEquals(Arrays.asList("b", "c", "empty", "null"), cancelled);
    }

    @Test public void generationGuardPreventsOldSameUrlCompletionAfterReturning() {
        DetailPreloadScope scope = new DetailPreloadScope();
        DetailLoadState generation = new DetailLoadState();
        List<String> cancelled = new ArrayList<>();
        scope.setNext("b", () -> cancelled.add("first-b"));
        scope.select("b");
        long first = generation.next();
        generation.next();
        scope.select("a");
        scope.setNext("b", () -> cancelled.add("second-b"));
        scope.select("b");
        generation.next();
        if (generation.isCurrent(first)) scope.complete("b");
        assertEquals(Arrays.asList("first-b"), cancelled);
        assertTrue(scope.contains("b"));
    }
}
