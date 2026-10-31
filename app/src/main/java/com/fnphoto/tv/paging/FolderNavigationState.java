package com.fnphoto.tv.paging;

import java.util.ArrayList;
import java.util.List;

/** Keeps paths, never parent media lists. Back navigation reloads the parent from the NAS. */
public final class FolderNavigationState {
    public static final int MAX_HISTORY = 32;
    public static final int MAX_PATH_CHARS = 4096;
    public static final int MAX_NAME_CHARS = 256;
    private final List<String> paths = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    public FolderNavigationState(String path, String name) {
        if (!validPath(path)) throw new IllegalArgumentException("Invalid folder path");
        paths.add(path);
        names.add(boundedName(name));
    }

    private static boolean validPath(String path) {
        return path != null && path.length() <= MAX_PATH_CHARS;
    }

    private static String boundedName(String name) {
        if (name == null) return "文件夹";
        return name.length() <= MAX_NAME_CHARS ? name : name.substring(0, MAX_NAME_CHARS);
    }

    public boolean enter(String path, String name) {
        if (!validPath(path) || paths.size() > MAX_HISTORY || paths.contains(path)) return false;
        paths.add(path);
        names.add(boundedName(name));
        return true;
    }

    public boolean back() {
        if (paths.size() <= 1) return false;
        paths.remove(paths.size() - 1);
        names.remove(names.size() - 1);
        return true;
    }

    public String getPath() { return paths.get(paths.size() - 1); }
    public String getName() { return names.get(names.size() - 1); }
    public int getDepth() { return paths.size() - 1; }
    public ArrayList<String> savedPaths() { return new ArrayList<>(paths); }
    public ArrayList<String> savedNames() { return new ArrayList<>(names); }

    public static FolderNavigationState restore(List<String> paths, List<String> names) {
        if (paths == null || names == null || paths.isEmpty() || paths.size() != names.size()
                || paths.size() > MAX_HISTORY + 1) throw new IllegalArgumentException("Invalid folder history");
        FolderNavigationState state = new FolderNavigationState(paths.get(0), names.get(0));
        for (int i = 1; i < paths.size(); i++) {
            if (!state.enter(paths.get(i), names.get(i))) throw new IllegalArgumentException("Invalid folder history");
        }
        return state;
    }
}
