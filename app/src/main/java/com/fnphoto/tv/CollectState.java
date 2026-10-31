package com.fnphoto.tv;

/** Never infer “already collected” from a failed write. Read state, then invert it. */
final class CollectState {
    static int target(int current) {
        return current == 0 ? 1 : current == 1 ? 0 : -1;
    }
}
