package com.fnphoto.tv.cache;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ImagePipelineTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void unmeasuredCustomTargetHasBoundedFallback() {
        assertEquals(320, ImageDimensions.safeWidth(0));
        assertEquals(180, ImageDimensions.safeHeight(0));
        assertEquals(320, ImageDimensions.safeWidth(Integer.MIN_VALUE));
        assertEquals(180, ImageDimensions.safeHeight(-1));
    }
    @Test public void measuredDimensionsArePreserved() {
        assertEquals(1920, ImageDimensions.safeWidth(1920));
        assertEquals(1080, ImageDimensions.safeHeight(1080));
    }
    @Test public void rebindInvalidatesOldImageBeforeCancellingIt() {
        ImageRequestScope scope = new ImageRequestScope();
        long oldGeneration = scope.generation();
        AtomicInteger calls = new AtomicInteger();
        scope.add(() -> {
            assertFalse(scope.isCurrent(oldGeneration));
            calls.incrementAndGet();
        });
        scope.clear();
        assertEquals(1, calls.get());
        assertTrue(scope.isCurrent(scope.generation()));
        scope.clear();
        assertEquals(1, calls.get());
    }
    @Test public void recycleCancelsEveryPreviewAndNextBindIsIndependent() {
        ImageRequestScope scope = new ImageRequestScope();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 4; i++) scope.add(calls::incrementAndGet);
        scope.clear();
        assertEquals(4, calls.get());
        scope.add(calls::incrementAndGet);
        scope.clear();
        assertEquals(5, calls.get());
    }
    @Test public void emptyAndMissingCacheHaveZeroSize() {
        assertEquals(0, CacheFiles.size(null));
        assertEquals(0, CacheFiles.size(new File(temporary.getRoot(), "absent")));
        assertTrue(CacheFiles.deleteContents(new File(temporary.getRoot(), "absent")));
    }
    @Test public void nestedCacheSizeAndDeletionIncludeEveryFile() throws Exception {
        File root = temporary.newFolder("cache");
        File nested = new File(root, "nested");
        assertTrue(nested.mkdir());
        try (FileOutputStream first = new FileOutputStream(new File(root, "one"));
             FileOutputStream second = new FileOutputStream(new File(nested, "two"))) {
            first.write(new byte[17]);
            second.write(new byte[23]);
        }
        assertEquals(40, CacheFiles.size(root));
        assertTrue(CacheFiles.deleteContents(root));
        assertTrue(root.isDirectory());
        assertEquals(0, CacheFiles.size(root));
        assertTrue(CacheFiles.deleteContents(root));
    }
}
