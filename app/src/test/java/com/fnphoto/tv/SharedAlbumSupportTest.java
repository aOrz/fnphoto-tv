package com.fnphoto.tv;

import com.fnphoto.tv.api.FnHttpApi;
import com.google.gson.Gson;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;
import static org.junit.Assert.*;

public class SharedAlbumSupportTest {
    private static final String NAS = "https://nas.example:5667";

    private FnHttpApi api() {
        return new Retrofit.Builder().baseUrl(NAS + "/")
                .addConverterFactory(GsonConverterFactory.create()).build().create(FnHttpApi.class);
    }

    private String sortedDecodedQuery(HttpUrl url) {
        StringBuilder query = new StringBuilder();
        for (String key : new TreeSet<>(url.queryParameterNames())) {
            if (query.length() > 0) query.append('&');
            query.append(key).append('=').append(url.queryParameter(key));
        }
        return query.toString();
    }

    @Test public void listRequestMatchesSignedDocumentedContract() {
        Request request = api().getSharedAlbums("test-token", "test-signature", 1000,
                SharedAlbumSupport.ALBUM_PAGE_SIZE, "share_mod_time", "desc").request();
        assertEquals("GET", request.method());
        assertEquals(SharedAlbumSupport.LIST_PATH, request.url().encodedPath());
        assertEquals(SharedAlbumSupport.listQuery(1000), sortedDecodedQuery(request.url()));
        assertEquals("test-token", request.header("accesstoken"));
        assertEquals("test-signature", request.header("authx"));
    }

    @Test public void contentRequestUsesCamelCaseAndSignsUnencodedTimes() {
        Request request = api().getSharedAlbumPhotos("test-token", "test-signature", 123,
                SharedAlbumSupport.START_TIME, SharedAlbumSupport.END_TIME, 50,
                SharedAlbumSupport.MEDIA_PAGE_SIZE, "desc").request();
        assertEquals(SharedAlbumSupport.CONTENT_PATH, request.url().encodedPath());
        assertEquals(SharedAlbumSupport.contentQuery(123, 50), sortedDecodedQuery(request.url()));
        assertTrue(request.url().encodedQuery().contains("%20"));
        assertNull(request.url().queryParameter("album_id"));
        assertNull(request.url().queryParameter("sortBy"));
        assertNull(request.url().queryParameter("start_time"));
    }

    @Test public void optionalResponseFieldsAreNotAssumedOrCoercedToPermissions() {
        FnHttpApi.SharedAlbumListResponse response = new Gson().fromJson(
                "{\"code\":0,\"data\":{\"list\":[{\"albumId\":123,\"albumName\":\"家庭\","
                + "\"photoCount\":2,\"videoCount\":1,\"ownerName\":\"家人\",\"permission\":{\"unknown\":true}}]}}",
                FnHttpApi.SharedAlbumListResponse.class);
        assertEquals(0, response.code);
        assertNull(response.data.total);
        assertEquals("家人 · 3 项", SharedAlbumSupport.albumCard(NAS, response.data.list.get(0)).getDateStr());
        assertNull(response.data.list.get(0).posterUrl);
    }

    @Test public void missingBusinessCodeIsNotSuccess() {
        Gson gson = new Gson();
        assertNotEquals(0, gson.fromJson("{\"data\":{\"list\":[]}}", FnHttpApi.SharedAlbumListResponse.class).code);
        assertNotEquals(0, gson.fromJson("{\"data\":{\"list\":[]}}", FnHttpApi.GalleryListResponse.class).code);
    }

    @Test public void nullableHasNextAndTotalStayNullable() {
        FnHttpApi.GalleryListResponse response = new Gson().fromJson("{\"code\":0,\"data\":{\"list\":[]}}",
                FnHttpApi.GalleryListResponse.class);
        assertNull(response.data.hasNext);
        assertNull(response.data.count);
        assertEquals(Boolean.TRUE, SharedAlbumSupport.hasMoreAlbums(2000, 1000, 500));
        assertEquals(Boolean.FALSE, SharedAlbumSupport.hasMoreAlbums(1500, 1000, 500));
        assertNull(SharedAlbumSupport.hasMoreAlbums(null, 0, 1000));
        assertNull(SharedAlbumSupport.hasMoreAlbums(1, 0, 1000));
    }

    @Test public void albumCoverResolvesOnlyAuthenticatedNasOrigin() {
        assertEquals(NAS + "/cover", SharedAlbumSupport.sameOriginUrl(NAS, "/cover"));
        assertEquals(NAS + "/cover", SharedAlbumSupport.sameOriginUrl(NAS, NAS + "/cover"));
        for (String url : Arrays.asList("https://other.example/cover", "//other.example/cover",
                "http://nas.example:5667/cover", "https://nas.example/cover", "data:text/plain,x",
                "https://user:pass@nas.example:5667/cover")) {
            assertNull(url, SharedAlbumSupport.sameOriginUrl(NAS, url));
        }
        assertNull(SharedAlbumSupport.sameOriginUrl(NAS, ""));
        assertNull(SharedAlbumSupport.sameOriginUrl(NAS, null));
    }

    @Test public void missingOrUntrustedCoverUsesSameOriginFallbackAndNeverTokenDestination() {
        FnHttpApi.SharedAlbum album = new FnHttpApi.SharedAlbum();
        album.albumId = 123;
        album.posterUrl = "https://attacker.example/cover";
        album.posterImgUrl = "/safe-cover";
        MediaItem card = SharedAlbumSupport.albumCard(NAS, album);
        assertEquals("shared_album", card.getType());
        assertEquals("未命名共享相册", card.getTitle());
        assertEquals(NAS + "/safe-cover", card.getThumbnailUrl());
        assertEquals("", card.getDateStr());
        assertNull(card.getMediaUrl());
    }

    private FnHttpApi.GalleryPhoto photo(String category, String uuid) {
        FnHttpApi.GalleryPhoto photo = new FnHttpApi.GalleryPhoto();
        photo.id = 456;
        photo.fileName = "test";
        photo.category = category;
        photo.photoUUID = uuid;
        return photo;
    }

    @Test public void documentedPhotoResponseNeedsNoAdditionalThumbnailObject() {
        MediaItem item = SharedAlbumSupport.mediaItem(NAS, photo("photo", "sample-uuid"));
        assertEquals(NAS + "/p/api/v1/stream/p/t/456/s/sample-uuid", item.getThumbnailUrl());
        assertEquals(NAS + "/p/api/v1/stream/p/t/456/m/sample-uuid", item.getMediaUrl());
    }

    @Test public void uuidCannotInjectPathOrQuery() {
        MediaItem item = SharedAlbumSupport.mediaItem(NAS, photo("photo", "abc/def?x#y"));
        HttpUrl url = HttpUrl.parse(item.getMediaUrl());
        assertEquals("abc/def?x#y", url.pathSegments().get(url.pathSize() - 1));
        assertNull(url.query());
        assertNull(url.fragment());
    }

    @Test public void videoUsesDocumentedStreamEvenWithoutUuid() {
        MediaItem item = SharedAlbumSupport.mediaItem(NAS, photo("video", null));
        assertEquals(NAS + "/p/api/v1/stream/v/456", item.getMediaUrl());
        assertNull(item.getThumbnailUrl());
    }

    @Test public void missingUuidDoesNotBuildNullStreamPath() {
        for (String uuid : new String[] {null, "", "  ", ".", ".."}) {
            MediaItem item = SharedAlbumSupport.mediaItem(NAS, photo("photo", uuid));
            assertNull(item.getMediaUrl());
            assertNull(item.getThumbnailUrl());
        }
    }

    @Test public void missingUuidCanUseReturnedSameOriginThumbnailOnly() {
        FnHttpApi.GalleryPhoto photo = photo("photo", null);
        photo.additional = new FnHttpApi.GalleryPhotoAdditional();
        photo.additional.thumbnail = new FnHttpApi.GalleryThumbnail();
        photo.additional.thumbnail.sUrl = "/s.jpg";
        photo.additional.thumbnail.mUrl = "https://attacker.example/m.jpg";
        photo.additional.thumbnail.originalUrl = "https://attacker.example/original.jpg";
        MediaItem item = SharedAlbumSupport.mediaItem(NAS, photo);
        assertEquals(NAS + "/s.jpg", item.getThumbnailUrl());
        assertEquals(NAS + "/s.jpg", item.getMediaUrl());
    }

    @Test public void sharedPagingHandlesShortHasNextAndRetriesSameRawOffset() {
        MainPagingState state = new MainPagingState(SharedAlbumSupport.MEDIA_PAGE_SIZE);
        assertEquals(0, state.begin());
        state.accept(Arrays.asList("a", "b", "b"), true);
        assertEquals(3, state.begin());
        state.fail();
        assertEquals(3, state.begin());
        assertEquals(Collections.singletonList(1), state.accept(Arrays.asList("b", "c"), false));
        assertEquals(5, state.getOffset());
        assertFalse(state.hasMore());
    }
    @Test public void sharedWindowsRespectTheMergedMetadataBudgetWithoutTruncatingPages() {
        assertTrue(MainMetadataWindow.fitsNextPage(0, SharedAlbumSupport.ALBUM_PAGE_SIZE));
        assertFalse(MainMetadataWindow.fitsNextPage(1000, SharedAlbumSupport.ALBUM_PAGE_SIZE));
        assertTrue(MainMetadataWindow.fitsNextPage(1150, SharedAlbumSupport.MEDIA_PAGE_SIZE));
        assertFalse(MainMetadataWindow.fitsNextPage(1200, SharedAlbumSupport.MEDIA_PAGE_SIZE));
    }

    @Test public void undocumentedShortPageDoesNotProveEndOfSharedAlbum() {
        MainPagingState state = new MainPagingState(SharedAlbumSupport.MEDIA_PAGE_SIZE);
        state.begin();
        state.accept(Collections.singletonList("a"), null);
        assertTrue(state.hasMore());
        assertEquals(1, state.begin());
        state.accept(Collections.emptyList(), null);
        assertFalse(state.hasMore());
    }
}
