package com.fnphoto.tv;

import com.fnphoto.tv.api.FnHttpApi;
import okhttp3.HttpUrl;

/** Recipient-only contract from the supplied handoff, separate from ordinary albums. */
final class SharedAlbumSupport {
    static final String LIST_PATH = "/p/api/v1/album_grant/list_to_me";
    static final String CONTENT_PATH = "/p/api/v1/album/normal/getList";
    static final int ALBUM_PAGE_SIZE = 1000;
    static final int MEDIA_PAGE_SIZE = 50;
    // These bounds match the handoff; server timezone and out-of-range media remain unverified.
    static final String START_TIME = "2000:01:01 00:00:00";
    static final String END_TIME = "2099:12:31 23:59:59";

    private SharedAlbumSupport() {}

    static String listQuery(int offset) {
        return "limit=" + ALBUM_PAGE_SIZE + "&offset=" + offset
                + "&sort_by=share_mod_time&sort_direction=desc";
    }

    static String contentQuery(int albumId, int offset) {
        return contentQuery(albumId, offset, MEDIA_PAGE_SIZE);
    }

    static String contentQuery(int albumId, int offset, int limit) {
        return "albumId=" + albumId + "&endTime=" + END_TIME + "&limit=" + limit
                + "&offset=" + offset + "&sortDirection=desc&startTime=" + START_TIME;
    }

    static Boolean hasMoreAlbums(Integer total, int offset, int returned) {
        long consumed = (long) offset + returned;
        return total != null && total >= consumed ? consumed < total : null;
    }

    /** Reject external initial URLs because the loader attaches the NAS token.
     * Redirect hops are enforced separately by CredentialRedirectInterceptor. */
    static String sameOriginUrl(String baseUrl, String value) {
        if (value == null || value.trim().isEmpty()) return null;
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base == null) return null;
        HttpUrl url = base.resolve(value);
        if (url == null || !base.scheme().equals(url.scheme()) || !base.host().equals(url.host())
                || base.port() != url.port() || !url.username().isEmpty() || !url.password().isEmpty()) return null;
        return url.toString();
    }

    static MediaItem albumCard(String baseUrl, FnHttpApi.SharedAlbum album) {
        String poster = sameOriginUrl(baseUrl, album.posterUrl);
        if (poster == null) poster = sameOriginUrl(baseUrl, album.posterImgUrl);
        String title = album.albumName == null || album.albumName.trim().isEmpty()
                ? "未命名共享相册" : album.albumName;
        MediaItem item = new MediaItem(String.valueOf(album.albumId), title, "shared_album", poster, null);
        long count = (long) Math.max(0, album.photoCount) + Math.max(0, album.videoCount);
        String owner = album.ownerName == null ? "" : album.ownerName;
        item.setDateStr(owner + (count > 0 ? (owner.isEmpty() ? "" : " · ") + count + " 项" : ""));
        return item;
    }

    static MediaItem mediaItem(String baseUrl, FnHttpApi.GalleryPhoto photo) {
        String thumbnail = null;
        String media = null;
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base != null && photo.photoUUID != null && !photo.photoUUID.trim().isEmpty()
                && !".".equals(photo.photoUUID) && !"..".equals(photo.photoUUID)) {
            // UUID is a single opaque path segment, never a path/query supplied by the server.
            thumbnail = base.newBuilder().encodedPath("/p/api/v1/stream/p/t/" + photo.id + "/s/")
                    .addPathSegment(photo.photoUUID).query(null).fragment(null).build().toString();
            media = base.newBuilder().encodedPath("/p/api/v1/stream/p/t/" + photo.id + "/m/")
                    .addPathSegment(photo.photoUUID).query(null).fragment(null).build().toString();
        } else if (photo.additional != null && photo.additional.thumbnail != null) {
            FnHttpApi.GalleryThumbnail urls = photo.additional.thumbnail;
            thumbnail = sameOriginUrl(baseUrl, urls.sUrl);
            if (thumbnail == null) thumbnail = sameOriginUrl(baseUrl, urls.mUrl);
            media = sameOriginUrl(baseUrl, urls.mUrl);
            if (media == null) media = thumbnail;
        }
        if ("video".equals(photo.category)) media = sameOriginUrl(baseUrl, "/p/api/v1/stream/v/" + photo.id);
        return new MediaItem(String.valueOf(photo.id), photo.fileName, photo.category, thumbnail, media);
    }
}
