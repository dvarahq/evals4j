package io.github.grabdoc.evals4j.spi;

/**
 * How an adapter makes a model return JSON matching a schema.
 *
 * <p>Providers vary: some enforce a JSON Schema server-side, others only promise "some JSON", and
 * others nothing at all. This is the knob for choosing between them.
 */
public enum StructuredOutputMode {

    /** Use the provider's schema enforcement when available, otherwise fall back to prompting. */
    AUTO,

    /** Require provider-side schema enforcement, failing if the provider does not support it. */
    NATIVE,

    /** Put the schema in the prompt and parse the JSON out of the reply. Works everywhere. */
    PROMPTED
}
