package com.dvarahq.oss.evals4j.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.ScorerRunner;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.prompt.PromptTemplate;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Scores structured output key by key.
 *
 * <p>Most keys are compared for equality. Keys named in the {@code rubric} are handed to an LLM
 * instead, with the rubric text as the criterion — which is what makes this usable on extraction
 * tasks where one field is a free-text summary and the rest are exact values.
 *
 * <pre>{@code
 * Evaluator evaluator = JsonMatchEvaluator.builder()
 *         .excludeKeys("id")
 *         .rubric("summary", "Does the summary capture the main points?")
 *         .model(judgeModel)
 *         .aggregator(Aggregator.AVERAGE)
 *         .build();
 * }</pre>
 *
 * <p>Port of OpenEvals' {@code create_json_match_evaluator}.
 */
public final class JsonMatchEvaluator implements Evaluator {

    public static final String SCORE_KEY = "json_match";
    public static final String RUN_NAME = "json_match_evaluator";

    static final String SYSTEM_PROMPT =
            """
            You are an LLM that evaluates the accuracy of structured outputs.
            Make sure to evaluate each key the users ask you to evaluate separately. Assign the score
            for each key based on its own criteria - DO NOT convolute the scores of different keys.
            Also only evaluate the output vs. the reference output based on the criteria. DO NOT EVALUATE
            BASED ON ANYTHING ELSE. If the output does not match the reference output in some way that
            is not mentioned in the criteria that is not a problem and you should ignore those discrepancies.
            Only focus on finding discrepancies based on the criteria. If there is a None value being compared
            to a non-None value, you should assign a score of 0.
            """;

    static final String USER_PROMPT =
            """
            Please evaluate the accuracy of the following output keys according to these criteria:
            {rubric}
            <Outputs>
            {outputs}
            </Outputs>
            <Expected Outputs>
            {reference_outputs}
            </Expected Outputs>""";

    /** How per-key scores are combined into one. */
    public enum Aggregator {
        /** Mean of the key scores. */
        AVERAGE,
        /** 1 only if every key scored 1. */
        ALL
    }

    /** How scores are combined across the elements of a list. */
    public enum ListAggregator {
        AVERAGE,
        ALL
    }

    /** How output list elements are paired with reference list elements. */
    public enum ListMatchMode {
        /** Pair greedily by best key overlap; unmatched references on either side score 0. */
        SAME_ELEMENTS,
        /** Pair greedily; unmatched references are ignored, so outputs may cover only part of the reference. */
        SUBSET,
        /** Pair each reference to its best output; extra outputs are ignored. */
        SUPERSET,
        /** Pair by position. */
        ORDERED
    }

    private final Aggregator aggregator;
    private final ListAggregator listAggregator;
    private final Map<String, String> rubric;
    private final Set<String> excludeKeys;
    private final ListMatchMode listMatchMode;
    private final boolean useReasoning;
    private final JudgeModel model;
    private final EvalTracer tracer;

    private JsonMatchEvaluator(Builder builder) {
        this.aggregator = builder.aggregator;
        this.listAggregator = builder.listAggregator;
        this.rubric = Map.copyOf(builder.rubric);
        this.excludeKeys = Set.copyOf(builder.excludeKeys);
        this.listMatchMode = builder.listMatchMode;
        this.useReasoning = builder.useReasoning;
        this.model = builder.model;
        this.tracer = builder.tracer == null ? EvalTracer.NO_OP : builder.tracer;

        if (model == null && !rubric.isEmpty()) {
            throw new IllegalArgumentException(
                    "A judge model is required when a rubric is provided — call .model(...)");
        }
        if (rubric.isEmpty() && model != null) {
            throw new IllegalArgumentException(
                    "A judge model is only used when grading specific keys against a rubric. "
                            + "Either add a rubric or drop the model.");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return ScorerRunner.run(RUN_NAME, SCORE_KEY, this::score, request, tracer);
    }

    @Override
    public CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(EvalRequest request) {
        return ScorerRunner.runAsync(RUN_NAME, SCORE_KEY, this::scoreAsync, request, tracer);
    }

    private ScorerOutput score(EvalRequest request) {
        Prepared prepared = prepare(request.outputs(), request.referenceOutputs());
        if (prepared.rubricKeys.isEmpty()) {
            return assemble(prepared, Map.of());
        }
        // Every future is already complete, so this joins without ever parking the thread.
        return assemble(prepared, judge(prepared, this::invokeBlocking).join());
    }

    private CompletableFuture<ScorerOutput> scoreAsync(EvalRequest request) {
        Prepared prepared = prepare(request.outputs(), request.referenceOutputs());
        if (prepared.rubricKeys.isEmpty()) {
            return CompletableFuture.completedFuture(assemble(prepared, Map.of()));
        }
        return judge(prepared, model::invokeStructuredAsync)
                .thenApply(judged -> assemble(prepared, judged));
    }

    private ScorerOutput assemble(Prepared prepared, Map<String, Object> judged) {
        Map<String, Object> allScores = new LinkedHashMap<>(prepared.scores);
        allScores.putAll(judged);

        Map<String, Object> aggregated =
                aggregate(SCORE_KEY, allScores, prepared.useListReducer, aggregator, listAggregator);

        Map<String, ScorerOutput.Single> results = new LinkedHashMap<>();
        aggregated.forEach((key, value) -> results.put(key, toSingle(value)));
        return ScorerOutput.keyed(results);
    }

    /**
     * How a judge call is made.
     *
     * <p>The scoring logic is identical whether or not the caller wants to block, and only the model
     * call differs — so it is the model call that varies, rather than there being two copies of the
     * key-narrowing and aggregation. The blocking implementation returns an already-completed future,
     * which keeps the synchronous path synchronous: calls are made one at a time on the calling
     * thread, exactly as before.
     */
    @FunctionalInterface
    private interface JudgeInvoker {
        CompletableFuture<JsonNode> invoke(List<ChatMessage> messages, JsonNode schema);
    }

    private CompletableFuture<JsonNode> invokeBlocking(List<ChatMessage> messages, JsonNode schema) {
        return CompletableFuture.completedFuture(model.invokeStructured(messages, schema));
    }

    private static ScorerOutput.Single toSingle(Object value) {
        if (value instanceof Judged judged) {
            return new ScorerOutput.Single(
                    Score.of(judged.score), judged.reasoning, Map.of(), null);
        }
        return new ScorerOutput.Single(Score.of(numeric(value)), null, Map.of(), null);
    }

    // ------------------------------------------------------------------ judging

    /**
     * Asks the judge to score the rubric keys.
     *
     * <p>With an aggregator, all rubric keys go in one call — the aggregate is what the caller sees,
     * so per-key isolation buys nothing. Without one, each base key is judged in its own call with a
     * narrowed schema, matching upstream: judging keys in isolation stops one key's rubric from
     * colouring another's score.
     */
    private CompletableFuture<Map<String, Object>> judge(Prepared prepared, JudgeInvoker invoker) {
        if (aggregator != null) {
            return invokeJudge(
                    prepared, prepared.rubricKeys, prepared.formattedRubric, prepared.schema, invoker);
        }

        List<CompletableFuture<Map<String, Object>>> perKey = new ArrayList<>();
        for (String baseKey : new TreeSet<>(baseKeysOf(prepared.rubricKeys, prepared.useListReducer))) {
            Set<String> rawKeys = new LinkedHashSet<>();
            for (String rawKey : prepared.rubricKeys) {
                if (baseKeyOf(rawKey, prepared.useListReducer).equals(baseKey)) {
                    rawKeys.add(rawKey);
                }
            }
            ObjectNode narrowed = narrowSchema(prepared.schema, rawKeys);
            String keyRubric = "Key: " + baseKey + ", Criteria: " + rubric.get(baseKey) + "\n";
            perKey.add(invokeJudge(prepared, rawKeys, keyRubric, narrowed, invoker));
        }

        // Asynchronously these calls are in flight together; the merge still walks them in key order,
        // so the scores come out in the same order either way.
        return CompletableFuture.allOf(perKey.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<String, Object> scores = new LinkedHashMap<>();
            for (CompletableFuture<Map<String, Object>> judged : perKey) {
                scores.putAll(judged.join());
            }
            return scores;
        });
    }

    private CompletableFuture<Map<String, Object>> invokeJudge(
            Prepared prepared,
            Set<String> rawKeys,
            String formattedRubric,
            ObjectNode schema,
            JudgeInvoker invoker) {

        String outputsText = renderKeys(prepared.outputs, rawKeys);
        String referenceText = renderKeys(prepared.reference, rawKeys);

        String userPrompt = PromptTemplate.format(
                USER_PROMPT,
                Map.of("rubric", formattedRubric, "outputs", outputsText, "reference_outputs", referenceText));

        return invoker.invoke(
                        List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(userPrompt)), schema)
                .thenApply(response -> readScores(response, rawKeys));
    }

    private static Map<String, Object> readScores(JsonNode response, Set<String> rawKeys) {
        Map<String, Object> scores = new LinkedHashMap<>();
        for (String rawKey : rawKeys) {
            JsonNode value = response.get(rawKey);
            if (value == null) {
                // The judge skipped a key; treat as a failure rather than silently dropping it.
                scores.put(rawKey, new Judged(0.0, "The judge returned no score for key " + rawKey));
            } else if (value.isObject()) {
                JsonNode scoreNode = value.get("score");
                JsonNode reasoningNode = value.get("reasoning");
                scores.put(
                        rawKey,
                        new Judged(
                                scoreNode == null ? 0.0 : toDouble(scoreNode),
                                reasoningNode == null || reasoningNode.isNull() ? null : reasoningNode.asText()));
            } else {
                scores.put(rawKey, new Judged(toDouble(value), null));
            }
        }
        return scores;
    }

    private static double toDouble(JsonNode node) {
        return node.isBoolean() ? (node.asBoolean() ? 1.0 : 0.0) : node.asDouble();
    }

    private static String renderKeys(Map<String, Object> values, Set<String> keys) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            if (values.containsKey(key)) {
                text.append(key).append(": ").append(values.get(key)).append('\n');
            }
        }
        return text.length() == 0 ? "" : text.substring(0, text.length() - 1);
    }

    private ObjectNode narrowSchema(ObjectNode schema, Set<String> rawKeys) {
        ObjectNode narrowed = schema.deepCopy();
        ObjectNode properties = Json.object();
        ArrayNode required = properties.arrayNode();
        JsonNode all = schema.get("properties");
        for (String rawKey : rawKeys) {
            if (all.has(rawKey)) {
                properties.set(rawKey, all.get(rawKey).deepCopy());
                required.add(rawKey);
            }
        }
        narrowed.set("properties", properties);
        narrowed.set("required", required);
        return narrowed;
    }

    // ------------------------------------------------------------------ preparation

    /** A judged score plus its reasoning; the LLM counterpart of a plain 0/1. */
    record Judged(double score, String reasoning) {}

    /** Everything derived from the inputs before any judging happens. */
    private record Prepared(
            Map<String, Object> outputs,
            Map<String, Object> reference,
            ObjectNode schema,
            Map<String, Object> scores,
            Set<String> rubricKeys,
            String formattedRubric,
            boolean useListReducer) {}

    @SuppressWarnings("unchecked")
    private Prepared prepare(Object rawOutputs, Object rawReference) {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.put("title", "structured_match_score");
        schema.put("description", "Scores measuring the accuracy of structured outputs");
        ObjectNode properties = Json.object();
        ArrayNode required = properties.arrayNode();

        Map<String, Object> scores = new LinkedHashMap<>();
        Set<String> rubricKeys = new LinkedHashSet<>();
        StringBuilder formattedRubric = new StringBuilder();

        Map<String, Object> outputs;
        Map<String, Object> reference;
        boolean useListReducer = rawOutputs instanceof List;

        if (useListReducer) {
            if (!(rawReference instanceof List)) {
                throw new IllegalArgumentException(
                        "If outputs is a list, reference_outputs must also be a list");
            }
            List<Map<String, Object>> outputList = asMapList((List<Object>) rawOutputs);
            List<Map<String, Object>> referenceList = asMapList((List<Object>) rawReference);
            Flattened flattened = flatten(outputList, referenceList);
            outputs = flattened.outputs;
            reference = flattened.reference;
        } else {
            outputs = asMap(rawOutputs);
            reference = asMap(rawReference);
        }

        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            String rawKey = entry.getKey();
            String key = baseKeyOf(rawKey, useListReducer);
            if (excludeKeys.contains(key)) {
                continue;
            }
            if (!reference.containsKey(rawKey)) {
                scores.put(rawKey, 0);
                continue;
            }
            if (!rubric.containsKey(key)) {
                scores.put(rawKey, java.util.Objects.equals(reference.get(rawKey), entry.getValue()) ? 1 : 0);
                continue;
            }
            String criteria = rubric.get(key);
            formattedRubric.append("Key: ").append(key).append(", Criteria: ").append(criteria).append('\n');
            rubricKeys.add(rawKey);
            properties.set(rawKey, rubricPropertySchema(key, criteria));
            required.add(rawKey);
        }

        // Reference keys the output never produced are failures, not omissions.
        for (String rawKey : reference.keySet()) {
            String key = baseKeyOf(rawKey, useListReducer);
            if (!excludeKeys.contains(key) && !outputs.containsKey(rawKey)) {
                scores.put(rawKey, 0);
            }
        }

        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);

        return new Prepared(
                outputs, reference, schema, scores, rubricKeys, formattedRubric.toString(), useListReducer);
    }

    private ObjectNode rubricPropertySchema(String key, String criteria) {
        String scoreDescription = "Does the output for key " + key + ", follow the criteria? " + criteria;
        if (!useReasoning) {
            ObjectNode property = Json.object();
            property.put("type", "boolean");
            property.put("description", scoreDescription);
            return property;
        }
        ObjectNode property = Json.object();
        property.put("type", "object");
        ObjectNode nested = Json.object();
        ObjectNode reasoning = Json.object();
        reasoning.put("type", "string");
        reasoning.put("description", "Reasoning for the score you assigned to key " + key);
        nested.set("reasoning", reasoning);
        ObjectNode score = Json.object();
        score.put("type", "boolean");
        score.put("description", scoreDescription);
        nested.set("score", score);
        property.set("properties", nested);
        ArrayNode nestedRequired = property.putArray("required");
        nestedRequired.add("score");
        nestedRequired.add("reasoning");
        property.put("additionalProperties", false);
        return property;
    }

    private record Flattened(Map<String, Object> outputs, Map<String, Object> reference) {}

    /**
     * Flattens two lists into {@code key_index} maps, pairing elements according to the list match
     * mode.
     *
     * <p>Pairing is greedy on the count of equal keys, ignoring excluded and rubric-judged keys —
     * those cannot be compared cheaply, so they must not influence which elements get paired.
     */
    private Flattened flatten(List<Map<String, Object>> outputs, List<Map<String, Object>> reference) {
        Map<String, Object> outputsToUse = new LinkedHashMap<>();
        Map<String, Object> referenceToUse = new LinkedHashMap<>();

        if (listMatchMode == ListMatchMode.ORDERED) {
            for (int i = 0; i < outputs.size(); i++) {
                putIndexed(outputsToUse, outputs.get(i), i);
            }
            for (int i = 0; i < reference.size(); i++) {
                putIndexed(referenceToUse, reference.get(i), i);
            }
            return new Flattened(outputsToUse, referenceToUse);
        }

        if (listMatchMode == ListMatchMode.SUPERSET) {
            List<Integer> available = new ArrayList<>();
            for (int i = 0; i < outputs.size(); i++) {
                available.add(i);
            }
            for (int i = 0; i < reference.size(); i++) {
                Map<String, Object> referenceItem = reference.get(i);
                Integer best = bestMatch(available, outputs, referenceItem);
                if (best != null) {
                    putIndexed(outputsToUse, outputs.get(best), i);
                    putIndexed(referenceToUse, referenceItem, i);
                    available.remove(best);
                } else {
                    // No output left to pair with: the reference item scores 0.
                    // Upstream leaves this branch unreachable through an uninitialized variable;
                    // reaching it here is a deliberate fix, documented in PARITY.md.
                    putIndexed(referenceToUse, referenceItem, i);
                }
            }
            return new Flattened(outputsToUse, referenceToUse);
        }

        // SAME_ELEMENTS and SUBSET: pair each output with its best reference.
        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < reference.size(); i++) {
            available.add(i);
        }
        for (int i = 0; i < outputs.size(); i++) {
            Map<String, Object> outputItem = outputs.get(i);
            Integer best = bestMatch(available, reference, outputItem);
            putIndexed(outputsToUse, outputItem, i);
            if (best != null) {
                putIndexed(referenceToUse, reference.get(best), i);
                available.remove(best);
            }
        }
        if (listMatchMode == ListMatchMode.SAME_ELEMENTS) {
            // Unpaired reference elements become entries with no output, scoring 0.
            for (int position = 0; position < available.size(); position++) {
                putIndexed(referenceToUse, reference.get(available.get(position)), outputs.size() + position);
            }
        }
        return new Flattened(outputsToUse, referenceToUse);
    }

    /** The index in {@code candidates} sharing the most equal comparable keys with {@code item}. */
    private Integer bestMatch(
            List<Integer> available, List<Map<String, Object>> candidates, Map<String, Object> item) {
        Integer best = null;
        int bestScore = -1;
        for (Integer index : available) {
            Map<String, Object> candidate = candidates.get(index);
            int matchScore = 0;
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                String key = entry.getKey();
                if (candidate.containsKey(key) && !excludeKeys.contains(key) && !rubric.containsKey(key)) {
                    matchScore += java.util.Objects.equals(entry.getValue(), candidate.get(key)) ? 1 : 0;
                }
            }
            if (matchScore > bestScore) {
                bestScore = matchScore;
                best = index;
            }
        }
        return best;
    }

    private static void putIndexed(Map<String, Object> target, Map<String, Object> item, int index) {
        item.forEach((key, value) -> target.put(key + "_" + index, value));
    }

    // ------------------------------------------------------------------ aggregation

    /**
     * Combines per-key scores.
     *
     * <p>For lists this is two levels: {@code aggregator} collapses the keys within one list element,
     * then {@code listAggregator} collapses across elements. With no aggregator the per-key structure
     * survives and only the across-element step runs.
     */
    static Map<String, Object> aggregate(
            String scoreKey,
            Map<String, Object> scores,
            boolean useListReducer,
            Aggregator aggregator,
            ListAggregator listAggregator) {

        Map<String, Object> results = new LinkedHashMap<>();

        if (useListReducer) {
            Map<String, Map<String, Object>> byIndex = new LinkedHashMap<>();
            scores.forEach((rawKey, value) -> {
                int split = rawKey.lastIndexOf('_');
                String index = rawKey.substring(split + 1);
                String baseKey = rawKey.substring(0, split);
                byIndex.computeIfAbsent(index, key -> new LinkedHashMap<>()).put(baseKey, value);
            });

            if (aggregator != null) {
                Map<String, Double> indexScores = new LinkedHashMap<>();
                byIndex.forEach((index, group) -> {
                    if (group.isEmpty()) {
                        return;
                    }
                    indexScores.put(index, collapse(group.values(), aggregator));
                });
                double score = listAggregator == ListAggregator.AVERAGE
                        ? mean(indexScores.values())
                        : (allOne(indexScores.values()) ? 1.0 : 0.0);
                results.put(scoreKey + ":" + name(aggregator), score);
                return results;
            }

            // No aggregator: collapse across list elements, key by key.
            Map<String, List<Object>> acrossIndices = new LinkedHashMap<>();
            byIndex.values().forEach(group -> group.forEach((baseKey, value) ->
                    acrossIndices.computeIfAbsent(baseKey, key -> new ArrayList<>()).add(value)));
            acrossIndices.forEach((baseKey, values) -> {
                double score = listAggregator == ListAggregator.AVERAGE
                        ? mean(values.stream().map(JsonMatchEvaluator::numeric).toList())
                        : (values.stream().allMatch(value -> numeric(value) == 1.0) ? 1.0 : 0.0);
                results.put(scoreKey + ":" + baseKey, score);
            });
            return results;
        }

        if (aggregator != null) {
            double score = collapse(scores.values(), aggregator);
            results.put(scoreKey + ":" + name(aggregator), score);
            return results;
        }

        scores.forEach((key, value) -> results.put(scoreKey + ":" + key, value));
        return results;
    }

    private static String name(Aggregator aggregator) {
        return aggregator == Aggregator.AVERAGE ? "average" : "all";
    }

    private static double collapse(java.util.Collection<Object> values, Aggregator aggregator) {
        List<Double> numbers = values.stream().map(JsonMatchEvaluator::numeric).toList();
        return aggregator == Aggregator.AVERAGE ? mean(numbers) : (allOne(numbers) ? 1.0 : 0.0);
    }

    private static double mean(java.util.Collection<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (Double value : values) {
            total += value;
        }
        return total / values.size();
    }

    private static boolean allOne(java.util.Collection<Double> values) {
        for (Double value : values) {
            if (value != 1.0) {
                return false;
            }
        }
        return true;
    }

    static double numeric(Object value) {
        if (value instanceof Judged judged) {
            return judged.score;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1.0 : 0.0;
        }
        throw new IllegalStateException("Cannot read a numeric score from: " + value);
    }

    private static String baseKeyOf(String rawKey, boolean useListReducer) {
        if (!useListReducer) {
            return rawKey;
        }
        int split = rawKey.lastIndexOf('_');
        return split <= 0 ? rawKey : rawKey.substring(0, split);
    }

    private static Set<String> baseKeysOf(Set<String> rawKeys, boolean useListReducer) {
        Set<String> baseKeys = new LinkedHashSet<>();
        rawKeys.forEach(rawKey -> baseKeys.add(baseKeyOf(rawKey, useListReducer)));
        return baseKeys;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            return copy;
        }
        return Json.MAPPER.convertValue(value, Map.class);
    }

    private static List<Map<String, Object>> asMapList(List<Object> values) {
        List<Map<String, Object>> maps = new ArrayList<>(values.size());
        for (Object value : values) {
            maps.add(asMap(value));
        }
        return maps;
    }

    /** Builder for {@link JsonMatchEvaluator}. */
    public static final class Builder {
        private Aggregator aggregator;
        private ListAggregator listAggregator = ListAggregator.ALL;
        private final Map<String, String> rubric = new LinkedHashMap<>();
        private final Set<String> excludeKeys = new LinkedHashSet<>();
        private ListMatchMode listMatchMode = ListMatchMode.SAME_ELEMENTS;
        private boolean useReasoning = true;
        private JudgeModel model;
        private EvalTracer tracer;

        /** Collapse all keys into one score. Leave unset for one result per key. */
        public Builder aggregator(Aggregator aggregator) {
            this.aggregator = aggregator;
            return this;
        }

        public Builder listAggregator(ListAggregator listAggregator) {
            this.listAggregator = java.util.Objects.requireNonNull(listAggregator, "listAggregator");
            return this;
        }

        /** Judge this key with an LLM against the given criteria instead of comparing for equality. */
        public Builder rubric(String key, String criteria) {
            this.rubric.put(key, criteria);
            return this;
        }

        public Builder rubric(Map<String, String> rubric) {
            this.rubric.putAll(rubric);
            return this;
        }

        /** Keys to ignore entirely — generated ids, timestamps, and the like. */
        public Builder excludeKeys(String... keys) {
            this.excludeKeys.addAll(List.of(keys));
            return this;
        }

        public Builder listMatchMode(ListMatchMode listMatchMode) {
            this.listMatchMode = java.util.Objects.requireNonNull(listMatchMode, "listMatchMode");
            return this;
        }

        public Builder useReasoning(boolean useReasoning) {
            this.useReasoning = useReasoning;
            return this;
        }

        /** Required when a rubric is set; rejected otherwise. */
        public Builder model(JudgeModel model) {
            this.model = model;
            return this;
        }

        public Builder tracer(EvalTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public JsonMatchEvaluator build() {
            return new JsonMatchEvaluator(this);
        }
    }
}
