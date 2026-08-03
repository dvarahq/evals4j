package io.github.grabdoc.evals4j.judge;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.message.ChatMessage;

import java.util.List;

/**
 * Builds a judge prompt programmatically, for cases a string template cannot express — few-shot
 * messages with real roles, image blocks assembled per input, or prompts assembled from a registry.
 *
 * <p>Unlike a string template, this receives the request's <em>raw</em> values, not their stringified
 * forms.
 */
@FunctionalInterface
public interface PromptFunction {

    List<ChatMessage> buildPrompt(EvalRequest request);
}
