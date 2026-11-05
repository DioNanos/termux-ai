package com.termux.app.terminal.ai.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * The numbers the adapter writes into the compat output: Integer and Long as the exact integers they are, finite
 * Double and Float as doubles. Any other kind of number (BigDecimal, BigInteger, Short, Byte, a custom Number) and
 * any non-finite value is refused with E_INVALID_RESPONSE, never converted through longValue or doubleValue, and
 * a refusal publishes no stdout. The adapter is driven through its public entry point and the client through its
 * public run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
public class CompatNumberValueTest {

    /** A Number nobody knows: it counts every conversion that is asked of it. */
    private static final class Custom extends Number {
        int conversions;
        @Override public int intValue() { conversions++; return 7; }
        @Override public long longValue() { conversions++; return 7L; }
        @Override public float floatValue() { conversions++; return 7f; }
        @Override public double doubleValue() { conversions++; return 7d; }
        @Override public String toString() { return "custom-7"; }
    }

    private static final CompatMethod.Plan CAMERA =
        new CompatMethod.Plan(CompatMethod.Branch.CAMERA_INFO, "camera.info", new JSONObject(), false, null);

    private static JSONObject data(Object focal, Object mixed) throws Exception {
        JSONObject camera = new JSONObject()
            .put("id", "0").put("facing", "back").put("facing_id", 0)
            .put("jpeg_output_sizes", new JSONArray().put(new JSONObject().put("width", 640).put("height", 480)))
            .put("focal_lengths", new JSONArray().put(focal))
            .put("auto_exposure_modes", new JSONArray().put(mixed))
            .put("physical_size", new JSONObject().put("width", 5.5).put("height", 4.25))
            .put("capabilities", new JSONArray().put("c"));
        return new JSONObject().put("cameras", new JSONArray().put(camera));
    }

    private static String adapt(Object focal, Object mixed) throws Exception {
        return new String(CompatResultAdapter.adapt(CAMERA, data(focal, mixed)).stdout, StandardCharsets.UTF_8);
    }

    private static void assertRefused(String label, Object focal, Object mixed) throws Exception {
        try {
            String out = adapt(focal, mixed);
            fail(label + " must be refused, it was written as: " + out);
        } catch (CompatResultAdapter.AdapterException e) {
            assertEquals(label, CompatError.E_INVALID_RESPONSE, e.code);
        }
    }

    // ---- supported numbers keep their exact output

    @Test public void integerLongDoubleAndFloatAreWrittenExactly() throws Exception {
        String out = adapt(Integer.valueOf(3), "x");
        assertTrue(out, out.contains("\"focal_lengths\": [\n      3\n    ]"));
        out = adapt(Long.valueOf(Long.MAX_VALUE), "x");
        assertTrue(out, out.contains("\"focal_lengths\": [\n      9223372036854775807\n    ]"));
        out = adapt(Long.valueOf(Long.MIN_VALUE), "x");
        assertTrue(out, out.contains("      -9223372036854775808\n"));
        out = adapt(Double.valueOf(4.25), "x");
        assertTrue(out, out.contains("\"focal_lengths\": [\n      4.25\n    ]"));
        out = adapt(Double.valueOf(29.0), "x");
        assertTrue(out, out.contains("      29.0\n"));
        out = adapt(Float.valueOf(2.5f), "x");
        assertTrue(out, out.contains("      2.5\n"));
    }

    @Test public void theMixedArrayWritesTheSameNumbersAndStringsUnchanged() throws Exception {
        String out = adapt(1.5, Integer.valueOf(2));
        assertTrue(out, out.contains("\"auto_exposure_modes\": [\n      2\n    ]"));
        out = adapt(1.5, Long.valueOf(5_000_000_000L));
        assertTrue(out, out.contains("      5000000000\n"));
        out = adapt(1.5, Double.valueOf(0.5));
        assertTrue(out, out.contains("      0.5\n"));
        out = adapt(1.5, "CONTROL_AE_MODE_ON");
        assertTrue(out, out.contains("\"auto_exposure_modes\": [\n      \"CONTROL_AE_MODE_ON\"\n    ]"));
    }

    // ---- refused numbers: E_INVALID_RESPONSE, in both arrays

    @Test public void aBigDecimalIsRefusedAndNotTruncated() throws Exception {
        assertRefused("BigDecimal 4.25", new BigDecimal("4.25"), "x");
        assertRefused("BigDecimal 4.25 in the mixed array", 1.5, new BigDecimal("4.25"));
        assertRefused("BigDecimal with a zero fraction", new BigDecimal("4"), "x");
    }

    @Test public void aBigIntegerIsRefusedWhetherOrNotItFitsALong() throws Exception {
        assertRefused("integer beyond Long", new BigInteger("9223372036854775808"), "x");
        assertRefused("integer far beyond Long", new BigInteger("123456789012345678901234567890"), "x");
        assertRefused("integer beyond Long in the mixed array", 1.5, new BigInteger("9223372036854775808"));
        assertRefused("BigInteger that would fit a long", BigInteger.TEN, "x");
    }

    @Test public void shortAndByteAreNotAcceptedDeliberately() throws Exception {
        assertRefused("Short", Short.valueOf((short) 3), "x");
        assertRefused("Byte", Byte.valueOf((byte) 3), "x");
    }

    @Test public void aCustomNumberIsRefusedWithoutAnyConversion() throws Exception {
        Custom inFocal = new Custom();
        assertRefused("custom Number", inFocal, "x");
        assertEquals("no conversion method was called on it", 0, inFocal.conversions);
        Custom inMixed = new Custom();
        assertRefused("custom Number in the mixed array", 1.5, inMixed);
        assertEquals(0, inMixed.conversions);
    }

    @Test public void nonFiniteNumbersAreRefused() throws Exception {
        for (Object bad : new Object[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertRefusedOrNotBuilt("non-finite " + bad, bad, "x");
            assertRefusedOrNotBuilt("non-finite " + bad + " in the mixed array", 1.5, bad);
        }
    }

    /**
     * As {@link #assertRefused}; a JSON container that refuses to hold the value at all has refused it first. The
     * camera arrays are built with JSONArray.put(Object), which holds the value, so here the adapter is reached;
     * the container and the adapter are measured apart in the non-finite tests further down.
     */
    private static void assertRefusedOrNotBuilt(String label, Object focal, Object mixed) throws Exception {
        try {
            assertRefused(label, focal, mixed);
        } catch (org.json.JSONException containerRefusal) {
            // the container library does not hold a non-finite number: nothing can reach the adapter
        }
    }

    // ---- the same contract on the fields that are read: integer fields, long fields, decimal fields

    private static final CompatMethod.Plan BATTERY =
        new CompatMethod.Plan(CompatMethod.Branch.BATTERY_STATUS, "sys.battery", new JSONObject(), false, null);

    /** A battery document whose one field under test carries {@code value}; the others are ordinary. */
    private static JSONObject battery(String field, Object value) throws Exception {
        JSONObject d = new JSONObject().put("level", 88).put("charging", true).put("current_ua", -1).put("charge_uah", 1L);
        d.put(field, value);
        return d;
    }

    private static String battery(String field, Number value) throws Exception {
        return new String(CompatResultAdapter.adapt(BATTERY, battery(field, (Object) value)).stdout, StandardCharsets.UTF_8);
    }

    private static void assertFieldRefused(String label, String field, Object value) throws Exception {
        try {
            String out = new String(CompatResultAdapter.adapt(BATTERY, battery(field, value)).stdout, StandardCharsets.UTF_8);
            fail(label + " must be refused, it was written as: " + out);
        } catch (CompatResultAdapter.AdapterException e) {
            assertEquals(label, CompatError.E_INVALID_RESPONSE, e.code);
        }
    }

    /** As {@link #assertFieldRefused} on a document the caller has already built, and measured, before the adapter runs. */
    private static void assertDocumentRefused(String label, JSONObject document) throws Exception {
        try {
            String out = new String(CompatResultAdapter.adapt(BATTERY, document).stdout, StandardCharsets.UTF_8);
            fail(label + " must be refused, it was written as: " + out);
        } catch (CompatResultAdapter.AdapterException e) {
            assertEquals(label, CompatError.E_INVALID_RESPONSE, e.code);
        }
    }

    @Test public void aBigIntegerInTheLongFieldIsRefusedAndNeverRoundedThroughADouble() throws Exception {
        // 2^53 + 1 is a long, exactly; as a double it would become 2^53: a different measure, written silently.
        assertFieldRefused("BigInteger 2^53+1", "charge_uah", new BigInteger("9007199254740993"));
        assertFieldRefused("BigInteger that fits a long", "charge_uah", BigInteger.TEN);
        assertFieldRefused("BigDecimal with a zero fraction", "charge_uah", new BigDecimal("5"));
        assertFieldRefused("BigDecimal 4.25", "charge_uah", new BigDecimal("4.25"));
    }

    @Test public void unsupportedKindsInTheIntegerFieldsAreRefused() throws Exception {
        assertFieldRefused("BigInteger", "level", BigInteger.valueOf(88));
        assertFieldRefused("BigDecimal", "current_ua", new BigDecimal("-1"));
        assertFieldRefused("Short", "voltage", Short.valueOf((short) 4351));
        assertFieldRefused("Byte", "scale", Byte.valueOf((byte) 100));
    }

    @Test public void unsupportedKindsInTheDecimalFieldAreRefused() throws Exception {
        assertFieldRefused("BigDecimal", "temperature", new BigDecimal("29.5"));
        assertFieldRefused("BigInteger", "temperature", BigInteger.valueOf(29));
        assertFieldRefused("Short", "temperature", Short.valueOf((short) 29));
        assertFieldRefused("Byte", "temperature", Byte.valueOf((byte) 29));
    }

    @Test public void aCustomNumberInAReadFieldIsRefusedWithoutAnyConversion() throws Exception {
        for (String field : new String[] {"level", "charge_uah", "temperature", "voltage"}) {
            Custom custom = new Custom();
            // Android's JSONObject.put validates every Number with JSON.checkDouble(value.doubleValue()): the
            // container asks one conversion before the adapter can see the value, so only the adapter is measured.
            JSONObject document = battery(field, (Object) custom);
            int askedByTheContainer = custom.conversions;
            assertDocumentRefused("custom Number in " + field, document);
            assertEquals("the adapter asked no conversion of it in " + field, askedByTheContainer, custom.conversions);
        }
    }

    @Test public void nonFiniteNumbersInTheDecimalAndIntegerFieldsAreRefused() throws Exception {
        for (Object bad : new Object[] {Double.NaN, Double.POSITIVE_INFINITY, Float.NaN, Float.NEGATIVE_INFINITY}) {
            for (String field : new String[] {"temperature", "voltage", "charge_uah"}) {
                try {
                    assertFieldRefused("non-finite " + bad + " in " + field, field, bad);
                } catch (org.json.JSONException containerRefusal) {
                    // the container library does not hold a non-finite number: this is the container's refusal,
                    // not the adapter's; the readers' own guards are measured by
                    // aNonFiniteValueInAReadFieldIsRefusedByTheReaderItself
                }
            }
        }
    }

    @Test public void theBoundariesOfTheIntegerAndLongFieldsStayAsTheyWere() throws Exception {
        assertTrue(battery("charge_uah", Long.valueOf(Long.MAX_VALUE)).contains("\"charge_counter\": 9223372036854775807"));
        assertTrue(battery("charge_uah", Long.valueOf(Long.MIN_VALUE)).contains("\"charge_counter\": -9223372036854775808"));
        assertTrue(battery("charge_uah", Integer.valueOf(-5)).contains("\"charge_counter\": -5"));
        assertTrue(battery("charge_uah", Double.valueOf(-9.223372036854775808E18)).contains("\"charge_counter\": -9223372036854775808"));
        assertTrue(battery("voltage", Long.valueOf(Integer.MAX_VALUE)).contains("\"voltage\": 2147483647"));
        assertTrue(battery("voltage", Double.valueOf(4351.0)).contains("\"voltage\": 4351"));
        assertTrue(battery("voltage", Float.valueOf(100f)).contains("\"voltage\": 100"));
        assertFieldRefused("a fraction in an integer field", "voltage", Double.valueOf(1.5));
        assertFieldRefused("a fraction in the long field", "charge_uah", Double.valueOf(2.5));
        assertFieldRefused("one above the int range", "voltage", Long.valueOf(2147483648L));
        assertFieldRefused("one below the int range", "voltage", Long.valueOf(-2147483649L));
        assertFieldRefused("a double outside the int range", "voltage", Double.valueOf(4294967296.0));
        assertFieldRefused("2^63 as a double is outside the long range", "charge_uah", Double.valueOf(9.223372036854775808E18));
    }

    @Test public void theDecimalFieldWritesIntegerLongDoubleAndFloatAsBefore() throws Exception {
        assertTrue(battery("temperature", Integer.valueOf(29)).contains("\"temperature\": 29.0"));
        assertTrue(battery("temperature", Long.valueOf(1L << 53)).contains("\"temperature\": 9.007199254740992E15"));
        assertTrue(battery("temperature", Double.valueOf(29.5)).contains("\"temperature\": 29.5"));
        assertTrue(battery("temperature", Float.valueOf(2.5f)).contains("\"temperature\": 2.5"));
    }

    @Test public void aLongThatADoubleCannotHoldIsRefusedInTheDecimalField() throws Exception {
        assertFieldRefused("2^53 + 1 as a decimal", "temperature", Long.valueOf((1L << 53) + 1));
        assertFieldRefused("Long.MAX_VALUE as a decimal", "temperature", Long.valueOf(Long.MAX_VALUE));
    }


    // ---- negative zero: the writer and the decimal reader keep its sign, the integer readers read it as 0

    @Test public void negativeZeroKeepsItsSignInTheWriterAndTheDecimalField() throws Exception {
        String out = adapt(Double.valueOf(-0.0), "x");
        assertTrue(out, out.contains("\"focal_lengths\": [\n      -0.0\n    ]"));
        out = adapt(Float.valueOf(-0.0f), "x");
        assertTrue(out, out.contains("\"focal_lengths\": [\n      -0.0\n    ]"));
        out = adapt(1.5, Double.valueOf(-0.0));
        assertTrue(out, out.contains("\"auto_exposure_modes\": [\n      -0.0\n    ]"));
        out = battery("temperature", Double.valueOf(-0.0));
        assertTrue(out, out.contains("\"temperature\": -0.0,"));
        out = battery("temperature", Float.valueOf(-0.0f));
        assertTrue(out, out.contains("\"temperature\": -0.0,"));
    }

    @Test public void negativeZeroIsReadAsZeroByTheIntegerAndLongFields() throws Exception {
        String out = battery("voltage", Double.valueOf(-0.0));
        assertTrue(out, out.contains("\"voltage\": 0,"));
        assertFalse(out, out.contains("-0"));
        out = battery("charge_uah", Double.valueOf(-0.0));
        assertTrue(out, out.contains("\"charge_counter\": 0\n"));
        assertFalse(out, out.contains("-0"));
        out = battery("voltage", Float.valueOf(-0.0f));
        assertTrue(out, out.contains("\"voltage\": 0,"));
    }

    // ---- non-finite numbers: the container's own refusal and the adapter's guards, measured apart

    private static final Object[] NON_FINITE = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};

    @Test public void theObjectContainerRefusesANonFiniteValueBeforeTheAdapterRuns() {
        // JSONObject.put validates every number it is given: a non-finite value never enters the object, so a
        // refusal seen here belongs to the container and proves nothing about the adapter.
        for (Object bad : NON_FINITE) {
            try {
                new JSONObject().put("temperature", bad);
                fail("the container held non-finite " + bad);
            } catch (org.json.JSONException expected) {
                // the container's refusal
            }
        }
    }

    @Test public void aNonFiniteArrayElementReachesTheWriterAndIsRefusedThere() throws Exception {
        // JSONArray.put(Object) holds the value without validating it, so the element does reach the adapter: the
        // refusal below is the writer's own guard, not the container's.
        for (Object bad : NON_FINITE) {
            JSONArray probe = new JSONArray().put(bad);
            assertEquals("the array held " + bad, bad, probe.opt(0));
            assertRefused("non-finite " + bad + " in the number array", bad, "x");
            assertRefused("non-finite " + bad + " in the mixed array", 1.5, bad);
        }
    }

    /**
     * A battery document that answers {@code value} for {@code field} directly from opt, the accessor the adapter
     * reads. No path through put can carry a non-finite number into an object field, so the readers' own guards
     * are measured through this answer, on every runtime, rather than claimed from the container.
     */
    private static JSONObject batteryAnswering(final String field, final Object value) throws Exception {
        JSONObject d = new JSONObject() {
            @Override public Object opt(String key) {
                return field.equals(key) ? value : super.opt(key);
            }
        };
        d.put("level", 88).put("charging", true).put("current_ua", -1).put("charge_uah", 1L);
        d.put(field, 0); // present for has(); its value is the one opt answers
        return d;
    }

    @Test public void aNonFiniteValueInAReadFieldIsRefusedByTheReaderItself() throws Exception {
        // The decimal reader's finiteness guard is the only one that stops a non-finite temperature. In the integer
        // and long fields the same values are also refused by the exact-integer and range checks that follow, so
        // the refusal there is measured but is not attributed to the finiteness guard alone.
        for (Object bad : NON_FINITE) {
            for (String field : new String[] {"temperature", "voltage", "charge_uah", "level"}) {
                assertDocumentRefused("non-finite " + bad + " in " + field, batteryAnswering(field, bad));
            }
        }
    }

    @Test public void theAnsweringDocumentIsReadAsAnOrdinaryOneForAFiniteValue() throws Exception {
        // control: the answering document itself is accepted, so the refusals above come from the value
        String out = new String(CompatResultAdapter.adapt(BATTERY, batteryAnswering("temperature", Double.valueOf(29.5))).stdout,
            StandardCharsets.UTF_8);
        assertTrue(out, out.contains("\"temperature\": 29.5,"));
    }

    // ---- multi-element arrays refused at their last element: nothing of the valid prefix is published

    private static JSONArray array(Object... values) {
        JSONArray a = new JSONArray();
        for (Object v : values) a.put(v);
        return a;
    }

    private static JSONObject cameraArrays(JSONArray focal, JSONArray mixed) throws Exception {
        JSONObject camera = new JSONObject()
            .put("id", "0").put("facing", "back").put("facing_id", 0)
            .put("jpeg_output_sizes", new JSONArray().put(new JSONObject().put("width", 640).put("height", 480)))
            .put("focal_lengths", focal)
            .put("auto_exposure_modes", mixed)
            .put("physical_size", new JSONObject().put("width", 5.5).put("height", 4.25))
            .put("capabilities", new JSONArray().put("c"));
        return new JSONObject().put("cameras", new JSONArray().put(camera));
    }

    private static void assertArraysRefused(String label, JSONArray focal, JSONArray mixed) throws Exception {
        try {
            CompatResultAdapter.Outcome outcome = CompatResultAdapter.adapt(CAMERA, cameraArrays(focal, mixed));
            fail(label + " must be refused, it was written as: " + new String(outcome.stdout, StandardCharsets.UTF_8));
        } catch (CompatResultAdapter.AdapterException e) {
            assertEquals(label, CompatError.E_INVALID_RESPONSE, e.code);
        }
    }

    @Test public void aNumberArrayWithAValidPrefixAndAnInvalidLastElementIsRefusedWhole() throws Exception {
        JSONArray mixed = array("CONTROL_AE_MODE_ON");
        // control: the prefix alone is written
        String out = new String(CompatResultAdapter.adapt(CAMERA, cameraArrays(array(1.5, 2, 5_000_000_000L), mixed)).stdout,
            StandardCharsets.UTF_8);
        assertTrue(out, out.contains("\"focal_lengths\": [\n      1.5,\n      2,\n      5000000000\n    ]"));
        assertArraysRefused("BigDecimal last", array(1.5, 2, 5_000_000_000L, new BigDecimal("4.25")), mixed);
        assertArraysRefused("BigInteger last", array(1.5, 2, BigInteger.TEN), mixed);
        assertArraysRefused("NaN last", array(1.5, 2, Double.NaN), mixed);
        assertArraysRefused("a string last", array(1.5, 2, "4.25"), mixed);
        Custom custom = new Custom();
        assertArraysRefused("custom Number last", array(1.5, 2, custom), mixed);
        assertEquals("no conversion was asked of it", 0, custom.conversions);
    }

    @Test public void aMixedArrayWithAValidPrefixAndAnInvalidLastElementIsRefusedWhole() throws Exception {
        JSONArray focal = array(1.5);
        String out = new String(CompatResultAdapter.adapt(CAMERA, cameraArrays(focal, array("CONTROL_AE_MODE_ON", 3, 0.5))).stdout,
            StandardCharsets.UTF_8);
        assertTrue(out, out.contains("\"auto_exposure_modes\": [\n      \"CONTROL_AE_MODE_ON\",\n      3,\n      0.5\n    ]"));
        assertArraysRefused("BigDecimal last", focal, array("CONTROL_AE_MODE_ON", 3, new BigDecimal("4.25")));
        assertArraysRefused("Short last", focal, array("CONTROL_AE_MODE_ON", 3, Short.valueOf((short) 3)));
        assertArraysRefused("infinity last", focal, array("CONTROL_AE_MODE_ON", 3, Double.POSITIVE_INFINITY));
        assertArraysRefused("a boolean last", focal, array("CONTROL_AE_MODE_ON", 3, Boolean.TRUE));
    }

    // ---- the boundaries of the decimal field

    /**
     * The decimal field writes the double the long converts to without loss, as Double.toString of the running
     * runtime spells it. That text is the shortest one that reads back to the same double, not every decimal digit
     * of the long (for -2^63 it is -9.223372036854776E18), and its exact spelling may differ between runtimes, so
     * the test asserts the runtime's own spelling and that it reads back to exactly the long that was given.
     */
    private static void assertDecimalHoldsLongExactly(long value) throws Exception {
        String out = battery("temperature", Long.valueOf(value));
        String expected = Double.toString((double) value);
        assertTrue(out, out.contains("\"temperature\": " + expected + ","));
        double readBack = Double.parseDouble(expected);
        assertTrue("the written text reads back inside the long range: " + expected, readBack < 9.223372036854775808E18);
        assertEquals("the written text reads back to " + value + " exactly", value, (long) readBack);
    }

    @Test public void longsADoubleHoldsExactlyAreWrittenInTheDecimalField() throws Exception {
        assertDecimalHoldsLongExactly(Long.MIN_VALUE);       // -2^63, a power of two
        assertDecimalHoldsLongExactly((1L << 53) + 2);       // above 2^53, still exact as a double
        assertDecimalHoldsLongExactly(1L << 62);
        assertDecimalHoldsLongExactly(-(1L << 53));
    }

    @Test public void aNegativeLongADoubleWouldRoundIsRefusedInTheDecimalField() throws Exception {
        // -2^53 - 1 would become -2^53 as a double: a different measure
        assertFieldRefused("-2^53 - 1 as a decimal", "temperature", Long.valueOf(-(1L << 53) - 1));
        assertFieldRefused("Long.MIN_VALUE + 1 as a decimal", "temperature", Long.valueOf(Long.MIN_VALUE + 1));
    }

    // ---- the public outcome of the client

    private static final class Run {
        final String stdout;
        final String stderr;
        final int exit;
        Run(String dataJson) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            this.exit = TermuxApiCompatClient.run(new String[] {"CameraInfo"}, FakeCompatTransport.noStdin(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new FakeCompatTransport().answering("{\"ok\":true,\"data\":" + dataJson + "}"), CompatClock.SYSTEM);
            this.stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
            this.stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String cameras(String focalLiteral) {
        return "{\"cameras\":[{\"id\":\"0\",\"facing\":\"back\",\"facing_id\":0,"
            + "\"jpeg_output_sizes\":[{\"width\":640,\"height\":480}],\"focal_lengths\":[" + focalLiteral + "],"
            + "\"auto_exposure_modes\":[\"CONTROL_AE_MODE_ON\"],\"physical_size\":{\"width\":5.5,\"height\":4.25},"
            + "\"capabilities\":[\"c\"]}]}";
    }

    @Test public void theClientNeverPrintsATruncatedIntegerForAnOversizedLiteral() {
        String literal = "123456789012345678901234567890";
        String truncated = String.valueOf(new BigInteger(literal).longValue());
        Run r = new Run(cameras(literal));
        assertFalse("a truncated integer was printed: " + r.stdout, r.stdout.contains(truncated));
        if (r.exit == 0) {
            // A platform parser that reads the literal as a double prints that double, nothing else.
            assertTrue(r.stdout, r.stdout.contains("1.2345678901234568E29"));
            assertEquals("", r.stderr);
        } else {
            // A parser that keeps it as a big integer: refused, no stdout, a fixed stderr code.
            assertEquals(4, r.exit);
            assertEquals("E_INVALID_RESPONSE\n", r.stderr);
            assertEquals("", r.stdout);
        }
    }

    @Test public void theClientRefusesANonFiniteLiteralWithAFixedCodeAndNoStdout() {
        Run r = new Run(cameras("1e999"));
        assertEquals(r.stdout, 4, r.exit);
        assertEquals("E_INVALID_RESPONSE\n", r.stderr);
        assertEquals("", r.stdout);
    }

    private static String cameras(String focalLiteral, String mixedLiteral) {
        return "{\"cameras\":[{\"id\":\"0\",\"facing\":\"back\",\"facing_id\":0,"
            + "\"jpeg_output_sizes\":[{\"width\":640,\"height\":480}],\"focal_lengths\":[" + focalLiteral + "],"
            + "\"auto_exposure_modes\":[" + mixedLiteral + "],\"physical_size\":{\"width\":5.5,\"height\":4.25},"
            + "\"capabilities\":[\"c\"]}]}";
    }

    private static void assertClientRefused(String label, Run r) {
        assertEquals(label + ": " + r.stdout, 4, r.exit);
        assertEquals(label, "E_INVALID_RESPONSE\n", r.stderr);
        assertEquals(label, "", r.stdout);
    }

    @Test public void theClientPublishesNothingOfAnArrayRefusedAtItsLastElement() {
        Run control = new Run(cameras("1.5,2,5000000000", "\"CONTROL_AE_MODE_ON\",3"));
        assertEquals(control.stderr, 0, control.exit);
        assertTrue(control.stdout, control.stdout.contains("      5000000000\n"));
        assertClientRefused("number array, a boolean last", new Run(cameras("1.5,2,true", "\"CONTROL_AE_MODE_ON\"")));
        assertClientRefused("number array, a string last", new Run(cameras("1.5,2,\"4.25\"", "\"CONTROL_AE_MODE_ON\"")));
        assertClientRefused("number array, 1e999 last", new Run(cameras("1.5,2,1e999", "\"CONTROL_AE_MODE_ON\"")));
        assertClientRefused("mixed array, a boolean last", new Run(cameras("1.5", "\"CONTROL_AE_MODE_ON\",3,true")));
        assertClientRefused("mixed array, 1e999 last", new Run(cameras("1.5", "\"CONTROL_AE_MODE_ON\",3,1e999")));
    }
}
