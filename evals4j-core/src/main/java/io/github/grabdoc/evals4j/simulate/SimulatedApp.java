package io.github.grabdoc.evals4j.simulate;

import io.github.grabdoc.evals4j.message.ChatMessage;

/**
 * The application under test.
 *
 * <p>Receives only the newest user message, not the whole conversation — the app is expected to keep
 * its own history, keyed by {@code threadId}. That is what makes the simulation a real test of the
 * app's memory rather than of a transcript replayed into it.
 *
 * <p>The return value may be a single {@link ChatMessage}, a list of them, or a map with a
 * {@code "messages"} key.
 */
@FunctionalInterface
public interface SimulatedApp {

    Object respond(ChatMessage userMessage, String threadId);
}
