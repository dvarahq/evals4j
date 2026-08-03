package com.dvarahq.oss.evals4j.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.ToolCall;
import com.dvarahq.oss.evals4j.prompt.FewShotExample;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.testing.FakeJudgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrajectoryLlmAsJudgeTest {

    private static List<ChatMessage> trajectory() {
        return List.of(
                ChatMessage.user("Book me a flight to Berlin"),
                ChatMessage.assistant(
                        "",
                        List.of(new ToolCall("call_1", "book_flight", "{\"destination\":\"Berlin\"}"))),
                ChatMessage.tool("BA123", "call_1"),
                ChatMessage.assistant("You are booked on BA123."));
    }

    @Test
    void scoresATrajectoryUnderTheDefaultFeedbackKey() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "took a sensible path");

        EvaluatorResult result = TrajectoryLlmAsJudge.builder()
                .model(model)
                .build()
                .evaluate(EvalRequest.ofOutputs(trajectory()));

        assertThat(result.key()).isEqualTo(TrajectoryLlmAsJudge.DEFAULT_FEEDBACK_KEY);
        assertThat(result.score()).isEqualTo(Score.of(true));
        assertThat(result.comment()).isEqualTo("took a sensible path");
    }

    @Test
    void rendersTheTrajectoryAsTextBeforeTheJudgeSeesIt() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "fine");

        TrajectoryLlmAsJudge.builder()
                .model(model)
                .build()
                .evaluate(EvalRequest.ofOutputs(trajectory()));

        String prompt = model.lastPromptText();
        // MessageRenderer's tag format is what the trajectory prompts were written against.
        assertThat(prompt).contains("book_flight");
        assertThat(prompt).contains("You are booked on BA123.");
        // The raw message list must not leak through as JSON.
        assertThat(prompt).doesNotContain("\"toolCalls\"");
    }

    @Test
    void aMissingReferenceRendersAsEmptyRatherThanFailing() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "fine");

        // The default prompt has a {reference_outputs} placeholder, and a null value would be
        // dropped from the template values and throw for a missing variable.
        EvaluatorResult result = TrajectoryLlmAsJudge.builder()
                .prompt(Prompts.TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE)
                .model(model)
                .build()
                .evaluate(EvalRequest.ofOutputs(trajectory()));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void rendersTheReferenceTrajectoryWhenThereIsOne() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "matched");

        TrajectoryLlmAsJudge.builder()
                .model(model)
                .build()
                .evaluate(EvalRequest.of(
                        null,
                        trajectory(),
                        List.of(ChatMessage.assistant("A reference reply worth spotting"))));

        assertThat(model.lastPromptText()).contains("A reference reply worth spotting");
    }

    @Test
    void supportsContinuousScores() {
        EvaluatorResult result = TrajectoryLlmAsJudge.builder()
                .model(FakeJudgeModel.scoring(0.6, "partially right"))
                .continuous(true)
                .build()
                .evaluate(EvalRequest.ofOutputs(trajectory()));

        assertThat(result.score()).isEqualTo(Score.of(0.6));
    }

    @Test
    void usesACustomFeedbackKeyAndRunName() {
        TrajectoryLlmAsJudge judge = TrajectoryLlmAsJudge.builder()
                .model(FakeJudgeModel.scoring(true, "fine"))
                .feedbackKey("tool_selection")
                .build();

        assertThat(judge.feedbackKey()).isEqualTo("tool_selection");
        assertThat(judge.runName()).isEqualTo("llm_as_tool_selection_judge");
        assertThat(judge.evaluate(EvalRequest.ofOutputs(trajectory())).key())
                .isEqualTo("tool_selection");
    }

    @Test
    void appliesTheSystemMessageAndFewShotExamples() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "fine");

        TrajectoryLlmAsJudge.builder()
                .model(model)
                .system("You grade travel agents.")
                .fewShotExamples(List.of(FewShotExample.of("q", "a", true, "because")))
                .build()
                .evaluate(EvalRequest.ofOutputs(trajectory()));

        assertThat(model.lastPrompt().get(0).role()).isEqualTo(ChatMessage.ROLE_SYSTEM);
        assertThat(model.lastPromptText()).contains("You grade travel agents.");
        assertThat(model.lastPromptText()).contains("because");
    }

    @Test
    void aCustomOutputSchemaIsReachableThroughEvaluateRaw() {
        ObjectNode response = Json.object();
        response.put("verdict", "reasonable");

        JsonNode raw = TrajectoryLlmAsJudge.builder()
                .model(FakeJudgeModel.returning(response))
                .outputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("verdict", Map.of("type", "string"))))
                .build()
                .evaluateRaw(EvalRequest.ofOutputs(trajectory()));

        assertThat(raw.get("verdict").asText()).isEqualTo("reasonable");
    }

    @Test
    void handlesASingleMessageRatherThanAList() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "fine");

        EvaluatorResult result = TrajectoryLlmAsJudge.builder()
                .model(model)
                .build()
                .evaluate(EvalRequest.ofOutputs(ChatMessage.assistant("Just the one message")));

        assertThat(result.passed()).isTrue();
        assertThat(model.lastPromptText()).contains("Just the one message");
    }
}
