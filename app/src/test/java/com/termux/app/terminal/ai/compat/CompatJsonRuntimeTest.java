package com.termux.app.terminal.ai.compat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.security.CodeSource;

import static org.junit.Assert.*;

/**
 * Guards the runtime of the compat tests: they run under the Robolectric
 * runner so that the classes under test load the org.json of the Android
 * platform, not the org.json:json library that other JVM tests keep on the
 * classpath. Every assertion here is evaluated in the same sandbox, on the
 * same SDK levels, as {@link TermuxApiCompatClientTest},
 * {@link GoldenOracleCompatTest} and {@link NativeFixtureContractTest}; if the
 * wrong library were loaded, the byte-exact tests would diverge on key order,
 * decimal types and raw newlines, and these checks name the cause first.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
public class CompatJsonRuntimeTest {

    @Test
    public void theCompatClassesAndOrgJsonShareTheSandboxAndOrgJsonIsThePlatformOne() {
        assertTrue("org.json must be loaded by the same loader as the compat classes",
            TermuxApiCompatClient.class.getClassLoader() == JSONObject.class.getClassLoader());
        // org.json:json declares toMap(); the Android platform class does not.
        for (Method m : JSONObject.class.getMethods()) {
            assertFalse("org.json:json is on the compat classpath (toMap found)", "toMap".equals(m.getName()));
        }
        CodeSource source = JSONObject.class.getProtectionDomain().getCodeSource();
        String where = source == null ? "" : String.valueOf(source.getLocation());
        assertFalse("org.json:json jar is the source of JSONObject: " + where, where.contains("json-2025"));
    }

    @Test
    public void putOrderIsInsertionOrder() throws Exception {
        JSONObject request = new JSONObject();
        request.put("cmd", "sys.battery");
        request.put("args", new JSONObject());
        assertEquals("{\"cmd\":\"sys.battery\",\"args\":{}}", request.toString());
    }

    @Test
    public void aDecimalLiteralIsADoubleAndKeepsItsValue() throws Exception {
        Object value = new JSONArray("[4.25]").opt(0);
        assertEquals(Double.class, value.getClass());
        assertTrue(((Double) value).doubleValue() == 4.25d);
    }

    @Test
    public void aDecimalWithAZeroFractionIsStillADouble() throws Exception {
        assertEquals(Double.class, new JSONObject("{\"temperature\":29.0}").get("temperature").getClass());
    }

    @Test
    public void aRawNewlineInsideAStringIsAccepted() throws Exception {
        JSONObject parsed = new JSONObject("{\"e\":\"x\ny\"}");
        assertEquals("x\ny", parsed.getString("e"));
    }
}
