package com.dvarahq.oss.evals4j.message;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentTest {

    @Test
    @SuppressWarnings("unchecked")
    void aBareUrlBecomesAnImageUrlBlock() {
        Map<String, Object> block = Attachment.ofUrl("https://example.com/photo.png").toContentBlock();

        assertThat(block.get("type")).isEqualTo("image_url");
        assertThat((Map<String, Object>) block.get("image_url"))
                .containsEntry("url", "https://example.com/photo.png");
    }

    @Test
    void imageDataBecomesAnImageUrlBlock() {
        Map<String, Object> block =
                Attachment.of("image/png", "data:image/png;base64,AAAA").toContentBlock();

        assertThat(block.get("type")).isEqualTo("image_url");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPdfBecomesAFileBlockWithADefaultFilename() {
        Map<String, Object> block =
                Attachment.of("application/pdf", "data:application/pdf;base64,AAAA").toContentBlock();

        assertThat(block.get("type")).isEqualTo("file");
        assertThat((Map<String, Object>) block.get("file")).containsEntry("filename", "attachment.pdf");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPdfKeepsASuppliedFilename() {
        Map<String, Object> block =
                Attachment.of("application/pdf", "AAAA", "invoice.pdf").toContentBlock();

        assertThat((Map<String, Object>) block.get("file")).containsEntry("filename", "invoice.pdf");
    }

    @Test
    @SuppressWarnings("unchecked")
    void audioStripsADataUriPrefixAndReportsTheFormat() {
        Map<String, Object> block =
                Attachment.of("audio/wav", "data:audio/wav;base64,QUJD").toContentBlock();

        assertThat(block.get("type")).isEqualTo("input_audio");
        assertThat((Map<String, Object>) block.get("input_audio"))
                .containsEntry("data", "QUJD")
                .containsEntry("format", "wav");
    }

    @Test
    @SuppressWarnings("unchecked")
    void audioWithoutADataUriIsPassedThroughUntouched() {
        Map<String, Object> block = Attachment.of("audio/wav", "QUJD").toContentBlock();

        assertThat((Map<String, Object>) block.get("input_audio")).containsEntry("data", "QUJD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizesMimeAliasesToTheFormatsProvidersAccept() {
        // audio/mpeg -> audio/mp3 and audio/wave -> audio/wav, matching upstream.
        assertThat((Map<String, Object>) Attachment.of("audio/mpeg", "QUJD").toContentBlock().get("input_audio"))
                .containsEntry("format", "mp3");
        assertThat((Map<String, Object>) Attachment.of("audio/wave", "QUJD").toContentBlock().get("input_audio"))
                .containsEntry("format", "wav");
        assertThat((Map<String, Object>) Attachment.of("audio/x-wav", "QUJD").toContentBlock().get("input_audio"))
                .containsEntry("format", "wav");
    }

    @Test
    void mimeTypesAreCaseAndWhitespaceInsensitive() {
        assertThat(Attachment.of("  IMAGE/PNG ", "AAAA").toContentBlock().get("type"))
                .isEqualTo("image_url");
    }

    @Test
    void rejectsAMimeTypeItCannotRender() {
        assertThatThrownBy(() -> Attachment.of("video/mp4", "AAAA").toContentBlock())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported attachment MIME type");
    }
}
