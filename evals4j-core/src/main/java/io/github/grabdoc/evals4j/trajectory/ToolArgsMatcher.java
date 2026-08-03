package io.github.grabdoc.evals4j.trajectory;

import java.util.Map;

/** Decides whether an actual tool call's arguments match the reference call's. */
@FunctionalInterface
public interface ToolArgsMatcher {

    boolean matches(Map<String, Object> outputArgs, Map<String, Object> referenceArgs);
}
