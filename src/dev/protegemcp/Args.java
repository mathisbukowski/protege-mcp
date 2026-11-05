package dev.protegemcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Typed access to the arguments of a tool call. */
final class Args {
    private final Map<String, Object> m;

    Args(Map<String, Object> m) {
        this.m = m == null ? Collections.emptyMap() : m;
    }

    boolean has(String k) {
        return m.get(k) != null;
    }

    String str(String k) throws ToolError {
        String v = opt(k, null);
        if (v == null || v.isEmpty()) {
            throw new ToolError("Missing required argument: " + k);
        }
        return v;
    }

    String opt(String k, String dflt) throws ToolError {
        Object v = m.get(k);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof String)) {
            throw new ToolError("Argument " + k + " must be a string");
        }
        return (String) v;
    }

    boolean bool(String k, boolean dflt) throws ToolError {
        Object v = m.get(k);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof Boolean)) {
            throw new ToolError("Argument " + k + " must be a boolean");
        }
        return (Boolean) v;
    }

    int integer(String k, int dflt) throws ToolError {
        Object v = m.get(k);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof Number)) {
            throw new ToolError("Argument " + k + " must be a number");
        }
        return ((Number) v).intValue();
    }

    List<String> strings(String k) throws ToolError {
        Object v = m.get(k);
        if (v == null) {
            return Collections.emptyList();
        }
        if (v instanceof String) {
            return Collections.singletonList((String) v);
        }
        if (!(v instanceof List)) {
            throw new ToolError("Argument " + k + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String)) {
                throw new ToolError("Argument " + k + " must be an array of strings");
            }
            out.add((String) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    List<Args> objects(String k) throws ToolError {
        Object v = m.get(k);
        if (!(v instanceof List)) {
            throw new ToolError("Argument " + k + " must be an array of objects");
        }
        List<Args> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof Map)) {
                throw new ToolError("Argument " + k + " must be an array of objects");
            }
            out.add(new Args((Map<String, Object>) o));
        }
        return out;
    }
}
