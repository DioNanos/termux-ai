package org.junit;

// Minimal assert surface used by the compat tests.
public class Assert {
    public static void assertTrue(boolean c) { if (!c) throw new AssertionError("assertTrue failed"); }
    public static void assertTrue(String m, boolean c) { if (!c) throw new AssertionError(m); }
    public static void assertFalse(boolean c) { if (c) throw new AssertionError("assertFalse failed"); }
    public static void assertFalse(String m, boolean c) { if (c) throw new AssertionError(m); }
    public static void assertEquals(Object e, Object a) {
        if (e == null ? a != null : !e.equals(a)) throw new AssertionError("expected " + e + " got " + a);
    }
    public static void assertEquals(String m, Object e, Object a) {
        if (e == null ? a != null : !e.equals(a)) throw new AssertionError(m + ": expected " + e + " got " + a);
    }
    public static void assertNull(Object o) { if (o != null) throw new AssertionError("expected null got " + o); }
    public static void assertNull(String m, Object o) { if (o != null) throw new AssertionError(m + ": expected null got " + o); }
    public static void assertNotNull(Object o) { if (o == null) throw new AssertionError("expected not null"); }
    public static void assertNotNull(String m, Object o) { if (o == null) throw new AssertionError(m + ": expected not null"); }
    public static void fail(String m) { throw new AssertionError(m); }
    public static void assertArrayEquals(byte[] expected, byte[] actual) {
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError("arrays differ: expected " + expected.length
                + " bytes, got " + (actual == null ? -1 : actual.length));
        }
    }
    public static void assertArrayEquals(String m, byte[] expected, byte[] actual) {
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError(m + ": arrays differ: expected " + expected.length
                + " bytes, got " + (actual == null ? -1 : actual.length));
        }
    }
}
