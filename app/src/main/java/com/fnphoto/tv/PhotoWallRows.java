package com.fnphoto.tv;

import java.util.ArrayList;
import java.util.List;

/** Reflows only the bounded, already-loaded window; item identities and section boundaries survive. */
final class PhotoWallRows {
    static final class Row<T, H> {
        final H header;
        final List<T> items;
        final boolean fixed;

        Row(H header, List<T> items, boolean fixed) {
            this.header = header;
            this.items = items;
            this.fixed = fixed;
        }
    }

    static <T, H> List<Row<T, H>> reflow(List<Row<T, H>> rows, int columns) {
        if (columns < 1) throw new IllegalArgumentException("columns must be positive");
        List<Row<T, H>> result = new ArrayList<>();
        Row<T, H> target = null;
        for (Row<T, H> source : rows) {
            if (source.fixed || source.items.isEmpty()) {
                target = null;
                result.add(source);
                continue;
            }
            if (source.header != null) target = null;
            H header = source.header;
            for (T item : source.items) {
                if (target == null || target.items.size() == columns) {
                    target = new Row<>(header, new ArrayList<>(), false);
                    result.add(target);
                }
                target.items.add(item);
                header = null;
            }
        }
        return result;
    }
}
