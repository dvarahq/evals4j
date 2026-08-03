package com.dvarahq.oss.evals4j.trajectory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The built-in {@link ToolArgsMatcher}s, and per-tool overrides.
 *
 * <p>Overrides exist because one match mode rarely fits every tool in a trajectory: a search tool's
 * query has to be exact, while a booking tool carries a timestamp nobody wants to pin down. Port of
 * OpenEvals' tool-args matching.
 */
public final class ToolArgsMatchers {

    private ToolArgsMatchers() {}

    /** Argument maps must be equal. */
    public static ToolArgsMatcher exact() {
        return Objects::equals;
    }

    /** Output arguments must all appear in the reference with the same value. */
    public static ToolArgsMatcher subset() {
        return (output, reference) -> containsAll(reference, output);
    }

    /** Reference arguments must all appear in the output with the same value. */
    public static ToolArgsMatcher superset() {
        return (output, reference) -> containsAll(output, reference);
    }

    /** Arguments are not compared. */
    public static ToolArgsMatcher ignore() {
        return (output, reference) -> true;
    }

    public static ToolArgsMatcher of(ToolArgsMatchMode mode) {
        return switch (mode) {
            case EXACT -> exact();
            case IGNORE -> ignore();
            case SUBSET -> subset();
            case SUPERSET -> superset();
        };
    }

    /**
     * Compares only the given argument paths, ignoring everything else.
     *
     * <p>Paths are dotted, so {@code "time.start"} reaches into a nested object. This is the
     * escape hatch for tools whose arguments carry incidental detail — ids, timestamps, request
     * nonces — that should not fail a match.
     */
    public static ToolArgsMatcher onKeys(List<String> keyPaths) {
        List<String> paths = List.copyOf(keyPaths);
        return (output, reference) -> {
            for (String path : paths) {
                if (!Objects.equals(valueAt(output, path), valueAt(reference, path))) {
                    return false;
                }
            }
            return true;
        };
    }

    public static ToolArgsMatcher onKeys(String... keyPaths) {
        return onKeys(List.of(keyPaths));
    }

    private static boolean containsAll(Map<String, Object> haystack, Map<String, Object> needles) {
        for (Map.Entry<String, Object> entry : needles.entrySet()) {
            if (!haystack.containsKey(entry.getKey())) {
                return false;
            }
            if (!Objects.equals(haystack.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Follows a dotted path; any missing or non-map segment yields {@code null}. */
    @SuppressWarnings("unchecked")
    static Object valueAt(Map<String, Object> args, String path) {
        Object current = args;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
