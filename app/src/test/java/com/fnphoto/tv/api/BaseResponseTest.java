package com.fnphoto.tv.api;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public class BaseResponseTest {
    private boolean success(String json) {
        return new Gson().fromJson(json, FnHttpApi.BaseResponse.class).isSuccessful();
    }
    @Test public void acceptsExplicitLegacyAndModernSuccess() {
        assertTrue(success("{\"errno\":0,\"result\":\"succ\"}"));
        assertTrue(success("{\"errno\":0}"));
        assertTrue(success("{\"code\":0}"));
        assertTrue(success("{\"result\":\"succ\"}"));
    }
    @Test public void rejectsMissingOrContradictoryStatus() {
        assertFalse(success("{}"));
        assertFalse(success("{\"code\":null}"));
        assertFalse(success("{\"code\":401}"));
        assertFalse(success("{\"errno\":1,\"result\":\"succ\"}"));
        assertFalse(success("{\"code\":0,\"errno\":1}"));
        assertFalse(success("{\"code\":0,\"result\":\"fail\"}"));
    }
}
