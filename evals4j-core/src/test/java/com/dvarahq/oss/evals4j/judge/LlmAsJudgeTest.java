package com.dvarahq.oss.evals4j.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.Attachment;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.prompt.FewShotExample;
import com.dvarahq.oss.evals4j.prompt.PromptTemplate;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.testing.FakeJudgeModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmAsJudgeTest {

    private static List<String> propertyNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void scoresWithReasoningByDefault() {
        FakeJudgeModel model = FakeJudgeModel.scoring(false, "Too verbose. Thus, the score should be: false.");

        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.CONCISENESS_PROMPT)
                .feedbackKey("conciseness")
                .model(model)
                .build()
                .evaluate("How is the weather?", "Thanks for asking! It is sunny and 90 degrees.");

        assertThat(result.key()).isEqualTo("conciseness");
        assertThat(result.score()).isEqualTo(Score.of(false));
        assertThat(result.comment()).contains("Too verbose");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void omitsCommentWhenReasoningIsDisabled() {
        ObjectNode response = Json.object();
        response.put("score", true);

        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.CONCISENESS_PROMPT)
                .model(FakeJudgeModel.returning(response))
                .useReasoning(false)
                .build()
                .evaluate("q", "a");

        assertThat(result.comment()).isNull();
        assertThat(result.key()).isEqualTo("score");
    }

    @Test
    void namesTheRunAfterTheFeedbackKey() {
        LlmAsJudge defaultKey =
                LlmAsJudge.builder().prompt("{outputs}").model(FakeJudgeModel.scoring(true, "ok")).build();
        LlmAsJudge customKey = LlmAsJudge.builder()
                .prompt("{outputs}")
                .feedbackKey("correctness")
                .model(FakeJudgeModel.scoring(true, "ok"))
                .build();

        assertThat(defaultKey.runName()).isEqualTo("llm_as_judge");
        assertThat(customKey.runName()).isEqualTo("llm_as_correctness_judge");
    }

    @Test
    void formatsPromptVariablesIncludingExtras() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "grounded");

        LlmAsJudge.builder()
                .prompt(Prompts.RAG_GROUNDEDNESS_PROMPT)
                .model(model)
                .build()
                .evaluate(EvalRequest.builder()
                        .outputs("Paris is the capital of France.")
                        .variable("context", "France's capital is Paris.")
                        .build());

        String prompt = model.lastPromptText();
        assertThat(prompt).contains("France's capital is Paris.");
        assertThat(prompt).contains("Paris is the capital of France.");
        assertThat(prompt).doesNotContain("{context}");
    }

    @Test
    void serializesNonStringValuesAsJson() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "ok");

        LlmAsJudge.builder()
                .prompt("<in>{inputs}</in><out>{outputs}</out>")
                .model(model)
                .build()
                .evaluate(Map.of("city", "SF"), List.of(1, 2, 3));

        assertThat(model.lastPromptText()).contains("{\"city\":\"SF\"}").contains("[1,2,3]");
    }

    @Test
    void failsLoudlyWhenAPromptVariableIsMissing() {
        LlmAsJudge judge = LlmAsJudge.builder()
                .prompt(Prompts.CORRECTNESS_PROMPT)
                .model(FakeJudgeModel.scoring(true, "ok"))
                .build();

        // CORRECTNESS_PROMPT needs reference_outputs; judging without one would be meaningless.
        assertThatThrownBy(() -> judge.evaluate("q", "a"))
                .isInstanceOf(PromptTemplate.MissingPromptVariableException.class)
                .hasMessageContaining("reference_outputs");
    }

    @Test
    void buildsABooleanSchemaByDefault() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "ok");
        LlmAsJudge.builder().prompt("{outputs}").model(model).build().evaluate(EvalRequest.ofOutputs("x"));

        JsonNode schema = model.lastSchema();
        assertThat(schema.path("properties").path("score").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        // reasoning must be declared before score so the model commits to reasoning first
        assertThat(propertyNamesOf(schema.path("properties"))).containsExactly("reasoning", "score");
        assertThat(schema.path("required").toString()).isEqualTo("[\"reasoning\",\"score\"]");
    }

    @Test
    void continuousProducesANumericSchema() {
        FakeJudgeModel model = FakeJudgeModel.scoring(0.75, "partly");
        EvaluatorResult result = LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(model)
                .continuous(true)
                .build()
                .evaluate(EvalRequest.ofOutputs("x"));

        assertThat(model.lastSchema().path("properties").path("score").path("type").asText())
                .isEqualTo("number");
        assertThat(result.score()).isEqualTo(Score.of(0.75));
    }

    @Test
    void choicesTakePrecedenceOverContinuous() {
        FakeJudgeModel model = FakeJudgeModel.scoring(0.5, "half");
        LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(model)
                .continuous(true)
                .choices(0.0, 0.5, 1.0)
                .build()
                .evaluate(EvalRequest.ofOutputs("x"));

        JsonNode score = model.lastSchema().path("properties").path("score");
        assertThat(score.path("type").asText()).isEqualTo("number");
        assertThat(score.path("enum").toString()).isEqualTo("[0.0,0.5,1.0]");
    }

    @Test
    void dropsTheReasoningPropertyWhenDisabled() {
        FakeJudgeModel model = FakeJudgeModel.returning(Json.object().put("score", true));
        LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(model)
                .useReasoning(false)
                .build()
                .evaluate(EvalRequest.ofOutputs("x"));

        assertThat(propertyNamesOf(model.lastSchema().path("properties"))).containsExactly("score");
        assertThat(model.lastSchema().path("required").toString()).isEqualTo("[\"score\"]");
    }

    @Test
    void appendsFewShotExamplesToTheLastUserMessage() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "ok");
        LlmAsJudge.builder()
                .prompt("Grade this: {outputs}")
                .model(model)
                .fewShotExample(FewShotExample.of("q1", "a1", true, "it was right"))
                .fewShotExample(FewShotExample.of("q2", "a2", false, null))
                .build()
                .evaluate(EvalRequest.ofOutputs("answer"));

        String prompt = model.lastPromptText();
        assertThat(prompt)
                .contains("<example>\n<input>q1</input>\n<output>a1</output>"
                        + "\n<reasoning>it was right</reasoning>\n<score>true</score>\n</example>");
        assertThat(prompt).contains("<input>q2</input>");
        assertThat(prompt).doesNotContain("<reasoning>null</reasoning>");
    }

    @Test
    void prependsTheSystemMessage() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "ok");
        LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(model)
                .system("You are a strict grader.")
                .build()
                .evaluate(EvalRequest.ofOutputs("x"));

        assertThat(model.lastPrompt().get(0).role()).isEqualTo(ChatMessage.ROLE_SYSTEM);
        assertThat(model.lastPrompt().get(0).text()).isEqualTo("You are a strict grader.");
    }

    @Test
    void rejectsASystemMessageWithAProgrammaticPrompt() {
        assertThatThrownBy(() -> LlmAsJudge.builder()
                        .prompt(request -> List.of(ChatMessage.user("hi")))
                        .system("nope")
                        .model(FakeJudgeModel.scoring(true, "ok"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string template");
    }

    @Test
    void splicesAttachmentsIntoTheContentBlocks() {
        FakeJudgeModel model = FakeJudgeModel.scoring(false, "clean");

        LlmAsJudge.builder()
                .prompt(Prompts.EXPLICIT_CONTENT_PROMPT)
                .model(model)
                .build()
                .evaluate(EvalRequest.builder()
                        .inputs("describe this")
                        .outputs("a cat")
                        .attachment(Attachment.ofUrl("https://example.com/cat.png"))
                        .build());

        Object content = model.lastPrompt().get(0).content();
        assertThat(content).isInstanceOf(List.class);
        List<?> blocks = (List<?>) content;
        assertThat(blocks).hasSize(3);
        assertThat(((Map<?, ?>) blocks.get(0)).get("type")).isEqualTo("text");
        assertThat(((Map<?, ?>) blocks.get(1)).get("type")).isEqualTo("image_url");
        assertThat(((Map<?, ?>) blocks.get(2)).get("type")).isEqualTo("text");
    }

    @Test
    void usesAProgrammaticPromptVerbatim() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "ok");
        LlmAsJudge.builder()
                .prompt(request -> List.of(
                        ChatMessage.system("be terse"),
                        ChatMessage.user("grade " + request.outputs())))
                .model(model)
                .build()
                .evaluate(EvalRequest.ofOutputs("the answer"));

        assertThat(model.lastPrompt()).hasSize(2);
        assertThat(model.lastPrompt().get(1).text()).isEqualTo("grade the answer");
    }

    @Test
    void returnsRawOutputForACustomSchema() {
        ObjectNode response = Json.object();
        response.put("category", "billing");
        response.put("confidence", 0.9);

        ObjectNode schema = Json.object();
        schema.put("type", "object");

        JsonNode raw = LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(FakeJudgeModel.returning(response))
                .outputSchema(schema)
                .build()
                .evaluateRaw(EvalRequest.ofOutputs("x"));

        assertThat(raw.path("category").asText()).isEqualTo("billing");
    }

    @Test
    void pointsAtEvaluateRawWhenACustomSchemaHasNoScore() {
        ObjectNode response = Json.object();
        response.put("category", "billing");

        LlmAsJudge judge = LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(FakeJudgeModel.returning(response))
                .outputSchema(Json.object().put("type", "object"))
                .build();

        assertThatThrownBy(() -> judge.evaluate(EvalRequest.ofOutputs("x")))
                .hasMessageContaining("evaluateRaw");
    }
}
