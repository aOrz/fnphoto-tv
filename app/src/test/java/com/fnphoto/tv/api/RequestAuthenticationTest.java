package com.fnphoto.tv.api;

import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestAuthenticationTest {
    @Test public void getUsesDecodedQueryAsInitialSigningDoes() throws Exception {
        Request r = new Request.Builder().url("http://nas/p/api/v1/photo/search?keyword=%E7%85%A7%E7%89%87&offset=0").build();
        assertEquals("keyword=照片&offset=0", RequestAuthentication.payload(r));
    }
    @Test public void collectRetrySignsActualFormNotQuery() throws Exception {
        FormBody form = new FormBody.Builder().add("id", "27").add("collect", "0").build();
        Request r = new Request.Builder().url("http://nas/p/api/v1/photo/collect").post(form).build();
        assertEquals("id=27&collect=0", RequestAuthentication.payload(r));
        Request signed = RequestAuthentication.sign(r, "new-token");
        assertSame(form, signed.body());
        assertEquals("new-token", signed.header("accesstoken"));
        assertTrue(signed.header("authx").contains("&sign="));
    }
    @Test public void jsonRetryPreservesExactBody() throws Exception {
        String json = "{\"keyword\":\"照片\",\"filters\":[]}";
        Request r = new Request.Builder().url("http://nas/p/api/v1/search/results")
            .post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), json)).build();
        assertEquals(json, RequestAuthentication.payload(r));
    }
    @Test(expected=java.io.IOException.class) public void unknownUploadIsNotConsumedForRetry() throws Exception {
        Request r = new Request.Builder().url("http://nas/p/api/upload")
            .post(RequestBody.create(MediaType.parse("application/octet-stream"), new byte[]{1, 2})).build();
        RequestAuthentication.payload(r);
    }
}
