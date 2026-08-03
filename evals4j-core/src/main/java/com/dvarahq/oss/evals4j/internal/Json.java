package com.dvarahq.oss.evals4j.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** Shared JSON plumbing. Internal — not part of the public API. */
public final class Json {

    /** For general serialization: insertion order preserved, matching Python's {@code json.dumps}. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * For canonical comparison. OpenEvals' exact-match sorts keys before comparing, so
     * {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} are equal but list order still matters.
     */
    public static final ObjectMapper CANONICAL_MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize value to JSON: " + value, e);
        }
    }

    /** Serializes with map keys sorted, so structurally equal objects produce equal strings. */
    public static String writeCanonical(Object value) {
        try {
            JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : CANONICAL_MAPPER.valueToTree(value);
            return CANONICAL_MAPPER.writeValueAsString(CANONICAL_MAPPER.treeToValue(node, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize value to JSON: " + value, e);
        }
    }

    public static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not parse JSON: " + json, e);
        }
    }

    public static JsonNode toNode(Object value) {
        return value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    /** Parses a tool call's {@code arguments} string; a blank or malformed value yields an empty map. */
    public static Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            return node.isObject() ? toMap(node) : Map.of();
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
