package io.github.grabdoc.evals4j.prompt;

import io.github.grabdoc.evals4j.result.Score;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A worked example appended to a judge prompt to calibrate its scoring.
 *
 * @param inputs the example's input
 * @param outputs the example's output
 * @param score the score a correct judge would assign
 * @param reasoning why; omitted from the rendering when {@code null}
 */
public record FewShotExample(Object inputs, Object outputs, Score score, String reasoning) {

    public FewShotExample {
        Objects.requireNonNull(score, "score");
    }

    public static FewShotExample of(Object inputs, Object outputs, boolean score, String reasoning) {
        return new FewShotExample(inputs, outputs, Score.of(score), reasoning);
    }

    public static FewShotExample of(Object inputs, Object outputs, double score, String reasoning) {
        return new FewShotExample(inputs, outputs, Score.of(score), reasoning);
    }

    /**
     * Renders examples as the {@code <example>} blocks OpenEvals appends to the last user message.
     *
     * <p>Values are rendered with {@code toString}, not JSON — matching upstream, where a map example
     * renders as its Python repr. The judge only needs it to be legible.
     */
    public static String render(List<FewShotExample> examples) {
        StringJoiner joiner = new StringJoiner("\n");
        for (FewShotExample example : examples) {
            StringBuilder block = new StringBuilder();
            block.append("<example>\n<input>")
                    .append(example.inputs)
                    .append("</input>\n<output>")
                    .append(example.outputs)
                    .append("</output>");
            if (example.reasoning != null) {
                block.append("\n<reasoning>").append(example.reasoning).append("</reasoning>");
            }
            block.append("\n<score>").append(example.score).append("</score>");
            block.append("\n</example>");
            joiner.add(block.toString());
        }
        return joiner.toString();
    }
}
