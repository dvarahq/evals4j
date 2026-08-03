package com.dvarahq.oss.evals4j.prompt;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {name}} placeholders in a prompt.
 *
 * <p>The prompts are Python {@code str.format} templates upstream. This implementation differs in one
 * deliberate way: a brace run that is not a well-formed {@code {identifier}} is left alone, where
 * Python would raise. That matters because evaluated values are frequently JSON, and a stray
 * {@code {"a": 1}} appearing in a nested template should not blow up formatting.
 *
 * <p>A placeholder that <em>is</em> well-formed but has no supplied value still throws, matching
 * Python's {@code KeyError} — a prompt silently missing its {@code {reference_outputs}} would score
 * nonsense.
 */
public final class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private PromptTemplate() {}

    /** Thrown when a template references a variable the caller did not supply. */
    public static class MissingPromptVariableException extends IllegalArgumentException {
        private final String variable;

        public MissingPromptVariableException(String variable) {
            super("Prompt template references {" + variable + "} but no value was provided. "
                    + "Pass it via EvalRequest inputs/outputs/referenceOutputs or .variable(\""
                    + variable + "\", ...).");
            this.variable = variable;
        }

        public String variable() {
            return variable;
        }
    }

    public static String format(String template, Map<String, ?> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 128);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!values.containsKey(name)) {
                throw new MissingPromptVariableException(name);
            }
            Object value = values.get(name);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The placeholder names a template uses, in order of first appearance. */
    public static Set<String> variablesOf(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    public static boolean references(String template, String variable) {
        return variablesOf(template).contains(variable);
    }
}
