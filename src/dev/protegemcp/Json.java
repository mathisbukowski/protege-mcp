package dev.protegemcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader and writer: Map, List, String, Long, Double, Boolean, null. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw p.err("trailing data");
        }
        return v;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("Invalid JSON at offset " + i + ": " + m);
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private Object value() {
        ws();
        if (i >= s.length()) {
            throw err("unexpected end");
        }
        char c = s.charAt(i);
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw err("expected string key");
            }
            String k = string();
            ws();
            if (peek() != ':') {
                throw err("expected ':'");
            }
            i++;
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("expected ',' or ']'");
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw err("unexpected end");
        }
        return s.charAt(i);
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = peek();
            i++;
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = peek();
            i++;
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw err("bad unicode escape");
                    }
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                case '"': case '\\': case '/':
                    b.append(e);
                    break;
                default:
                    throw err("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(start, i);
        if (n.isEmpty()) {
            throw err("unexpected character");
        }
        try {
            if (n.matches("-?\\d+")) {
                return Long.valueOf(n);
            }
            return Double.valueOf(n);
        } catch (NumberFormatException e) {
            throw err("bad number");
        }
    }

    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(o, b);
        return b.toString();
    }

    private static void write(Object o, StringBuilder b) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object v : (Iterable<?>) o) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(v, b);
            }
            b.append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            b.append(o);
        } else {
            quote(o.toString(), b);
        }
    }

    private static void quote(String v, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
