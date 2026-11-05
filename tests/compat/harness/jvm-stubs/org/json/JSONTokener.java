package org.json;

// Minimal JSON tokenizer: objects, arrays, strings (with escapes),
// numbers, true/false/null. Sufficient for the synthetic fixtures of the
// compat check harness; the platform parser remains the CI authority.
public class JSONTokener {
    private final String s;
    private int i;

    public JSONTokener(String s) { this.s = s; }

    public Object nextValue() throws JSONException {
        skipWs();
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                JSONObject o = new JSONObject();
                skipWs();
                if (s.charAt(i) == '}') { i++; return o; }
                while (true) {
                    skipWs();
                    String key = nextString();
                    skipWs();
                    expect(':');
                    o.put(key, nextValue());
                    skipWs();
                    char d = s.charAt(i++);
                    if (d == '}') return o;
                    if (d != ',') throw new RuntimeException("bad object");
                }
            }
            case '[': {
                i++;
                JSONArray a = new JSONArray();
                skipWs();
                if (s.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.put(nextValue());
                    skipWs();
                    char d = s.charAt(i++);
                    if (d == ']') return a;
                    if (d != ',') throw new RuntimeException("bad array");
                }
            }
            case '"':
                return nextString();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                String num = s.substring(start, i);
                if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
                long l = Long.parseLong(num);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            }
        }
    }

    private String nextString() throws JSONException {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: throw new RuntimeException("bad escape");
                }
            } else {
                b.append(c);
            }
        }
    }

    private void expect(char c) throws JSONException {
        skipWs();
        if (s.charAt(i) != c) throw new RuntimeException("expected " + c + " at " + i);
        i++;
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    public static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int j = 0; j < v.length(); j++) {
            char c = v.charAt(j);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    public static String valueToString(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return quote((String) v);
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                // org.json prints integral doubles without a trailing .0
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }
}
