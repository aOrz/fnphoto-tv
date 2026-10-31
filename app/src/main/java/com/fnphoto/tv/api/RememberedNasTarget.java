package com.fnphoto.tv.api;

/** Keep the user-entered FN ID separate from the last resolved transport endpoint. */
public final class RememberedNasTarget {
    private RememberedNasTarget() {}

    public static String source(String savedSource, String savedEndpoint) {
        return savedSource == null || savedSource.trim().isEmpty() ? savedEndpoint : savedSource.trim();
    }

    public static boolean requiresDiscovery(String source) {
        return source != null && FnConnectApi.isFnId(source);
    }

    public interface Discovery { String resolve(String fnId) throws java.io.IOException; }

    public static String resolve(String source, Discovery discovery) throws java.io.IOException {
        if (source == null || source.trim().isEmpty()) throw new java.io.IOException("Missing NAS address");
        if (!requiresDiscovery(source)) return UrlUtils.normalizeUrl(source);
        String endpoint = discovery.resolve(source);
        if (endpoint == null || endpoint.isEmpty()) throw new java.io.IOException("No reachable address for FN ID");
        return endpoint;
    }
}
