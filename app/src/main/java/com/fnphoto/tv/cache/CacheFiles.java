package com.fnphoto.tv.cache;

import java.io.File;

/** Disk-only operations. Call from the cache executor, never from a bind callback. */
public final class CacheFiles {
    private CacheFiles() {}
    public static long size(File root) {
        if (root == null || !root.exists()) return 0;
        if (root.isFile()) return root.length();
        long bytes = 0;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) bytes += size(child);
        return bytes;
    }
    public static boolean deleteContents(File root) {
        if (root == null || !root.exists()) return true;
        File[] children = root.listFiles();
        if (children == null) return false;
        boolean success = true;
        for (File child : children) {
            if (child.isDirectory()) success &= deleteContents(child);
            success &= child.delete();
        }
        return success;
    }
}
