package com.dvarahq.oss.evals4j.message;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A multimodal attachment spliced into a judge prompt at the {@code {attachments}} placeholder.
 *
 * <p>Either a bare {@code url} (treated as an image URL) or {@code mimeType} + {@code data}, where
 * {@code data} is base64 or a {@code data:} URI. Mirrors OpenEvals'
 * {@code _attachment_to_content_block}.
 */
public record Attachment(String url, String mimeType, String data, String name) {

    public static Attachment ofUrl(String url) {
        return new Attachment(Objects.requireNonNull(url, "url"), null, null, null);
    }

    public static Attachment of(String mimeType, String data) {
        return new Attachment(null, Objects.requireNonNull(mimeType, "mimeType"),
                Objects.requireNonNull(data, "data"), null);
    }

    public static Attachment of(String mimeType, String data, String name) {
        return new Attachment(null, Objects.requireNonNull(mimeType, "mimeType"),
                Objects.requireNonNull(data, "data"), name);
    }

    /**
     * Normalizes MIME aliases exactly as OpenEvals does, so {@code audio/mpeg} and {@code audio/wave}
     * reach the model as the formats providers actually accept.
     */
    static String normalizeMimeType(String mimeType) {
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "audio/mpeg" -> "audio/mp3";
            case "audio/wave", "audio/x-wav" -> "audio/wav";
            default -> normalized;
        };
    }

    /** Renders this attachment as an OpenAI-style content block. */
    public Map<String, Object> toContentBlock() {
        if (url != null) {
            return Map.of("type", "image_url", "image_url", Map.of("url", url));
        }
        String mime = normalizeMimeType(mimeType);
        if (mime.startsWith("image/")) {
            return Map.of("type", "image_url", "image_url", Map.of("url", data));
        }
        if (mime.equals("application/pdf")) {
            return Map.of(
                    "type", "file",
                    "file", Map.of("filename", name == null ? "attachment.pdf" : name, "file_data", data));
        }
        if (mime.startsWith("audio/")) {
            String payload = data.startsWith("data:") ? data.substring(data.indexOf(',') + 1) : data;
            return Map.of(
                    "type", "input_audio",
                    "input_audio", Map.of("data", payload, "format", mime.substring(mime.indexOf('/') + 1)));
        }
        throw new IllegalArgumentException("Unsupported attachment MIME type: " + mimeType);
    }
}
