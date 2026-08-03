package io.github.grabdoc.evals4j.message;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagesTest {

    @Test
    void normalizesNullToAnEmptyList() {
        assertThat(Messages.normalize(null)).isEmpty();
    }

    @Test
    void normalizesASingleMessage() {
        assertThat(Messages.normalize(ChatMessage.user("hi"))).hasSize(1);
    }

    @Test
    void normalizesAMapWithAMessagesKey() {
        Object payload = Map.of("messages", List.of(Map.of("role", "user", "content", "hi")));
        assertThat(Messages.normalize(payload)).singleElement().satisfies(message -> {
            assertThat(message.role()).isEqualTo("user");
            assertThat(message.text()).isEqualTo("hi");
        });
    }

    @Test
    void rejectsAMapWithNeitherRoleNorMessages() {
        assertThatThrownBy(() -> Messages.normalize(Map.of("foo", "bar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'messages' key");
    }

    @Test
    void readsBothToolCallWireShapes() {
        List<ChatMessage> openAiShape = Messages.normalize(List.of(Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls",
                List.of(Map.of("function", Map.of("name", "get_weather", "arguments", "{\"city\":\"SF\"}"))))));

        List<ChatMessage> langChainShape = Messages.normalize(List.of(Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of("name", "get_weather", "args", Map.of("city", "SF"))))));

        assertThat(openAiShape.get(0).toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(langChainShape.get(0).toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(langChainShape.get(0).toolCalls().get(0).arguments()).isEqualTo("{\"city\":\"SF\"}");
    }

    @Test
    void defaultsMissingIdsToEmptyRatherThanFailing() {
        // Hand-written reference trajectories should not have to invent tool-call ids.
        List<ChatMessage> messages = Messages.normalize(List.of(Map.of(
                "role", "assistant",
                "tool_calls", List.of(Map.of("name", "search", "args", Map.of())))));

        assertThat(messages.get(0).toolCalls().get(0).id()).isEmpty();
        assertThat(messages.get(0).content()).isEqualTo("");
    }

    @Test
    void extractsTheFinalOutputAsAString() {
        assertThat(Messages.finalOutputAsString("plain")).isEqualTo("plain");
        assertThat(Messages.finalOutputAsString(Map.of("content", "from content"))).isEqualTo("from content");
        assertThat(Messages.finalOutputAsString(
                        Map.of("messages", List.of(Map.of("role", "user", "content", "first"),
                                Map.of("role", "assistant", "content", "last")))))
                .isEqualTo("last");
    }

    @Test
    void rendersAConversationInTheJudgeFormat() {
        String rendered = MessageRenderer.render(List.of(
                ChatMessage.user("What is the weather in SF?"),
                ChatMessage.assistant("", List.of(new ToolCall("1", "get_weather", "{\"city\":\"SF\"}"))),
                ChatMessage.tool("80 and sunny", "1")));

        assertThat(rendered)
                .isEqualTo(
                        """
                        <user>
                        What is the weather in SF?
                        </user>

                        <assistant>
                        <tool_call>
                        <name>get_weather</name>
                        <arguments>{"city":"SF"}</arguments>
                        </tool_call>
                        </assistant>

                        <tool>
                        <tool_result>
                        <id>1</id>
                        <content>80 and sunny</content>
                        </tool_result>
                        </tool>""");
    }
}
