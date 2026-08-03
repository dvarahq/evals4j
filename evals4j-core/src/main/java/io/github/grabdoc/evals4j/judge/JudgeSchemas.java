package io.github.grabdoc.evals4j.judge;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.grabdoc.evals4j.internal.Json;

import java.util.List;

/**
 * Builds the JSON Schema a judge is asked to conform to.
 *
 * <p>Port of OpenEvals' {@code _construct_default_output_json_schema}. Three details are behavioural
 * rather than cosmetic and are reproduced exactly:
 *
 * <ul>
 *   <li>{@code choices} beats {@code continuous} beats boolean — setting both is not an error, the
 *       first wins.
 *   <li>{@code reasoning} is declared <em>before</em> {@code score}, so a model generating fields in
 *       order has to commit to its reasoning before its verdict.
 *   <li>the reasoning description ends with the literal instruction to close with
 *       "Thus, the score should be: ...", which measurably improves score consistency.
 * </ul>
 */
public final class JudgeSchemas {

    static final String BOOLEAN_DESCRIPTION =
            "A score that is true if criteria in the prompt are met, and false otherwise.";

    static final String CONTINUOUS_DESCRIPTION =
            "A number that represents the degree to which the criteria in the prompt are met, from 0.0 to"
                    + " 1.0. 1.0 means the criteria are met perfectly. 0.0 means none of the criteria are"
                    + " met, 0.5 means exactly half of the criteria are met.";

    static final String CHOICES_DESCRIPTION =
            "A number that represents the degree to which the criteria in the prompt are met.";

    static final String REASONING_DESCRIPTION =
            "A human-readable explanation of the score. You MUST end the reasoning with a sentence that"
                    + " says: Thus, the score should be: SCORE_YOU_ASSIGN.";

    private JudgeSchemas() {}

    /**
     * @param continuous score as a number in [0,1] rather than a boolean
     * @param choices restricts the score to these values; overrides {@code continuous}
     * @param useReasoning require a chain-of-thought {@code reasoning} field
     */
    public static ObjectNode defaultSchema(boolean continuous, List<Double> choices, boolean useReasoning) {
        ObjectNode schema = Json.object();
        schema.put("type", "object");

        ObjectNode scoreSchema = Json.object();
        if (choices != null && !choices.isEmpty()) {
            scoreSchema.put("type", "number");
            scoreSchema.put("description", CHOICES_DESCRIPTION);
            ArrayNode enumNode = scoreSchema.putArray("enum");
            choices.forEach(enumNode::add);
        } else if (continuous) {
            scoreSchema.put("type", "number");
            scoreSchema.put("description", CONTINUOUS_DESCRIPTION);
        } else {
            scoreSchema.put("type", "boolean");
            scoreSchema.put("description", BOOLEAN_DESCRIPTION);
        }

        ObjectNode properties = Json.object();
        ArrayNode required = Json.object().arrayNode();
        if (useReasoning) {
            ObjectNode reasoning = Json.object();
            reasoning.put("type", "string");
            reasoning.put("description", REASONING_DESCRIPTION);
            properties.set("reasoning", reasoning);
            required.add("reasoning");
        }
        properties.set("score", scoreSchema);
        required.add("score");

        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** The description of the score field, used as the schema's own description upstream. */
    static String scoreDescription(boolean continuous, List<Double> choices) {
        if (choices != null && !choices.isEmpty()) {
            return CHOICES_DESCRIPTION;
        }
        return continuous ? CONTINUOUS_DESCRIPTION : BOOLEAN_DESCRIPTION;
    }

    /** Names and describes a schema, as providers require for a named structured-output format. */
    public static ObjectNode named(ObjectNode schema, String title, String description) {
        ObjectNode copy = schema.deepCopy();
        copy.put("title", title);
        if (description != null) {
            copy.put("description", description);
        }
        return copy;
    }
}
