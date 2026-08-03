package io.github.grabdoc.evals4j.code;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls source code out of a model's response.
 *
 * <p>Models rarely emit bare code — it arrives wrapped in prose and fenced blocks, mixed with shell
 * commands the caller does not want to compile. Port of OpenEvals' markdown extraction.
 */
public final class CodeExtraction {

    /**
     * Fenced blocks anchored at line start, not preceded by a backtick, with the closing fence at
     * end of line. Group 1 is the language tag, group 2 the body.
     */
    private static final Pattern FENCED_BLOCK =
            Pattern.compile("(?m)^(?<!`)```(\\w*)\\n([\\s\\S]*?)^(?<!`)```$");

    /** Blocks in these languages are installation or output, never the code under test. */
    private static final Set<String> EXCLUDED_LANGUAGES =
            Set.of("bash", "sh", "shell", "zsh", "fish", "console", "terminal", "json");

    private CodeExtraction() {}

    /**
     * The concatenated bodies of every fenced code block, or {@code null} when there are none.
     *
     * <p>{@code null} rather than an empty string because "the model produced no code" is a distinct
     * outcome from "the model produced empty code", and the code evaluators score it as a failure
     * with {@code code_extraction_failed} metadata.
     */
    public static String fromMarkdownCodeBlocks(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = FENCED_BLOCK.matcher(text);
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) {
            String language = matcher.group(1).trim();
            if (!EXCLUDED_LANGUAGES.contains(language)) {
                blocks.add(matcher.group(2));
            }
        }
        return blocks.isEmpty() ? null : String.join("\n", blocks);
    }
}
