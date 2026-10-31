package com.fnphoto.tv;

import com.fnphoto.tv.paging.MetadataLimits;
import java.util.ArrayList;
import java.util.List;

/** A conservative whole-page boundary: no response tail is silently discarded. */
final class MainMetadataWindow {
    static boolean fitsNextPage(int retained, int pageSize) {
        return retained >= 0 && pageSize > 0
                && (long) retained + pageSize <= MetadataLimits.MAX_ITEMS;
    }

    static <T> List<T> copyUnpaged(List<T> rows) {
        List<T> result = new ArrayList<>();
        // Never retain subList's backing array for an oversized response.
        for (int i = 0; i < Math.min(rows.size(), MetadataLimits.MAX_ITEMS); i++) {
            T row = rows.get(i);
            if (row != null) result.add(row);
        }
        return result;
    }
}
