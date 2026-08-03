package com.dvarahq.oss.evals4j.internal;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recovers a JSON object from a model's reply when the provider cannot enforce a schema.
 *
 * <p>Models asked for JSON tend to add things around it — a code fence, a sentence of preamble, a
 * closing remark. Internal; not part of the public API.
 */
public final class JsonExtraction {

    private JsonExtraction() {}

    /** The instruction appended to a prompt when the schema has to be enforced by asking nicely. */
    public static String schemaInstruction(JsonNode schema) {
        return "\n\nRespond with a single JSON object conforming to this JSON Schema. "
                + "Output only the JSON — no prose, no code fences.\n\n"
                + schema.toPrettyString();
    }

    /**
     * The first balanced JSON object in the text.
     *
     * @throws IllegalArgumentException if there is none, or it does not parse
     */
    public static JsonNode firstObject(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The model returned an empty response, expected JSON");
        }
        String candidate = balancedObject(text);
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "No JSON object found in the model response: " + abbreviate(text));
        }
        try {
            return Json.MAPPER.readTree(candidate);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "The model response was not valid JSON: " + abbreviate(candidate), e);
        }
    }

    /**
     * Scans for a brace-balanced region, ignoring braces inside string literals.
     *
     * <p>A plain "first { to last }" would break on a reply containing two objects, and a regex would
     * break on braces inside a string value.
     */
    private static String balancedObject(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < text.length(); i++) {
                char character = text.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (character == '\\') {
                    escaped = true;
                    continue;
                }
                if (character == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    private static String abbreviate(String text) {
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= 300 ? collapsed : collapsed.substring(0, 300) + "…";
    }
}
