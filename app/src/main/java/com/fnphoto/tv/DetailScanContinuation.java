package com.fnphoto.tv;

/** One bounded directional scan session; no per-photo or per-page history is retained. */
final class DetailScanContinuation {
    private String anchorId;
    private int anchorRaw = -1;
    private boolean previous;

    /**
     * Rebase only when entering an unknown gap. An unchanged anchor/direction is
     * continuing the contiguous scan already performed, even after its raw page
     * has been evicted. Errors retry that same frontier; a direction or anchor
     * change starts a new session and must not assume the gap has been searched.
     */
    boolean shouldRelocate(String id, int raw, boolean backwards, boolean outsideWindow) {
        boolean continuing = id != null && id.equals(anchorId)
                && raw == anchorRaw && backwards == previous;
        anchorId = id;
        anchorRaw = raw;
        previous = backwards;
        return raw >= 0 && outsideWindow && !continuing;
    }
}
