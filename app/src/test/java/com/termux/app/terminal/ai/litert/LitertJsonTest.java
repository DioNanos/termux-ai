package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/** Tool arguments cross into the SDK and back as plain values: a JSON null stays a null, nothing is dropped. */
public class LitertJsonTest {

    @Test
    public void aNullStaysANullInAnObjectAndInAnArrayWithoutShiftingIndexes() throws Exception {
        Map<String, Object> map = LitertJson.toMap(new JSONObject("{\"x\":null,\"a\":[1,null,3],\"n\":{\"y\":null,\"z\":[null]}}"));
        assertTrue("the key of a null value is still there", map.containsKey("x"));
        assertNull(map.get("x"));
        List<?> a = (List<?>) map.get("a");
        assertEquals(3, a.size());
        assertEquals(1, a.get(0));
        assertNull(a.get(1));
        assertEquals(3, a.get(2));
        Map<?, ?> n = (Map<?, ?>) map.get("n");
        assertTrue(n.containsKey("y"));
        assertNull(n.get("y"));
        assertEquals(1, ((List<?>) n.get("z")).size());
    }

    @Test
    public void theValueTypesAreKept() throws Exception {
        Map<String, Object> map = LitertJson.toMap(new JSONObject("{\"s\":\"t\",\"i\":7,\"d\":1.5,\"b\":true,\"o\":{\"k\":\"v\"}}"));
        assertEquals("t", map.get("s"));
        assertEquals(7, map.get("i"));
        assertEquals(1.5, ((Number) map.get("d")).doubleValue(), 0);
        assertEquals(true, map.get("b"));
        assertEquals("v", ((Map<?, ?>) map.get("o")).get("k"));
    }

    @Test
    public void backToJsonTheArgumentsAreExactlyWhatTheyWere() throws Exception {
        String original = "{\"x\":null,\"a\":[1,null,3],\"n\":{\"y\":null,\"z\":[null,\"q\"]},\"s\":\"t\"}";
        JSONObject back = LitertJson.fromMap(LitertJson.toMap(new JSONObject(original)));
        assertEquals(new JSONObject(original).toString(), back.toString());
        assertTrue(back.has("x"));
        assertTrue(back.isNull("x"));
        assertTrue(back.getJSONArray("a").isNull(1));
        assertEquals(3, back.getJSONArray("a").length());
    }
}
