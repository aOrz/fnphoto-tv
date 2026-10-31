package com.fnphoto.tv.api;

import com.google.gson.Gson;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** An empty data list is only valid after the server explicitly reports success. */
@RunWith(Parameterized.class)
public class ListResponseStatusTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> responseTypes() {
        return Arrays.asList(new Object[][] {
                {FnHttpApi.AlbumListResponse.class},
                {FnHttpApi.FolderListResponse.class},
                {FnHttpApi.TimelineResponse.class},
                {FnHttpApi.GeoListResponse.class},
                {FnHttpApi.PersonListResponse.class},
                {FnHttpApi.PersonTimelineResponse.class},
                {FnHttpApi.SubFolderListResponse.class},
                {FnHttpApi.FolderFileListResponse.class}
        });
    }

    private final Class<?> responseType;

    public ListResponseStatusTest(Class<?> responseType) {
        this.responseType = responseType;
    }

    private int code(String json) throws Exception {
        Object response = new Gson().fromJson(json, responseType);
        return responseType.getField("code").getInt(response);
    }

    @Test public void missingStatusDoesNotTurnEmptyDataIntoSuccess() throws Exception {
        assertNotEquals(0, code("{\"data\":{\"list\":[]}}"));
    }

    @Test public void nullStatusDoesNotTurnEmptyDataIntoSuccess() throws Exception {
        assertNotEquals(0, code("{\"code\":null,\"data\":{\"list\":[]}}"));
    }

    @Test public void explicitSuccessAllowsEmptyData() throws Exception {
        assertEquals(0, code("{\"code\":0,\"data\":{\"list\":[]}}"));
    }

    @Test public void explicitFailureIsPreservedEvenWithEmptyData() throws Exception {
        assertEquals(403, code("{\"code\":403,\"data\":{\"list\":[]}}"));
    }
}
