package com.dvarahq.oss.evals4j.examples;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.json.JsonMatchEvaluator;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scoring structured extraction, where some fields must match exactly and one is free text.
 *
 * <p>Comparing extracted objects with {@code equals} fails on the summary field, and judging the
 * whole object with an LLM is slow, expensive and vague about which field went wrong. This does
 * both: exact comparison for the values, a rubric for the prose, and a score per key so a failure
 * tells you which field broke.
 *
 * <pre>
 * OPENAI_API_KEY=... ./mvnw -q -pl evals4j-examples exec:java \
 *     -Dexec.mainClass=com.dvarahq.oss.evals4j.examples.StructuredExtractionExample
 * </pre>
 */
public final class StructuredExtractionExample {

    public static void main(String[] args) {
        // What the extractor produced from a support email.
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("ticket_id", "T-8891");           // generated; should not be compared
        extracted.put("customer_name", "Ada Lovelace");
        extracted.put("priority", "high");
        extracted.put("order_number", "ORD-4471");
        extracted.put("summary", "Customer's package never arrived and they want a refund.");

        // The hand-labelled expectation.
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("ticket_id", "T-0001");
        expected.put("customer_name", "Ada Lovelace");
        expected.put("priority", "urgent");            // mismatch: the extractor under-rated it
        expected.put("order_number", "ORD-4471");
        expected.put("summary", "The order was never delivered; the customer is requesting a refund.");

        JsonMatchEvaluator evaluator = JsonMatchEvaluator.builder()
                .excludeKeys("ticket_id")              // generated per run; never comparable
                .rubric("summary", "Does the summary convey the same problem and the same "
                        + "customer request as the reference? Wording may differ.")
                .model(Models.springAiJudge())
                .build();

        System.out.println("Per-key scores:");
        for (EvaluatorResult result : evaluator.evaluateAll(EvalRequest.of(null, extracted, expected))) {
            Models.print(result);
        }

        // One number for a dashboard, once you know which keys matter.
        JsonMatchEvaluator aggregated = JsonMatchEvaluator.builder()
                .excludeKeys("ticket_id")
                .rubric("summary", "Does the summary convey the same problem and the same "
                        + "customer request as the reference? Wording may differ.")
                .model(Models.springAiJudge())
                .aggregator(JsonMatchEvaluator.Aggregator.AVERAGE)
                .build();

        System.out.println("\nAggregated to one score:");
        Models.print(aggregated.evaluate(EvalRequest.of(null, extracted, expected)));

        // Lists are supported too — elements are paired by how well their keys line up, not by
        // position, so an extractor that returns line items in a different order still passes.
        List<Map<String, Object>> actualItems = List.of(
                Map.of("sku", "B-22", "quantity", 1),
                Map.of("sku", "A-10", "quantity", 2));
        List<Map<String, Object>> expectedItems = List.of(
                Map.of("sku", "A-10", "quantity", 2),
                Map.of("sku", "B-22", "quantity", 3));

        JsonMatchEvaluator lineItems = JsonMatchEvaluator.builder()
                .listAggregator(JsonMatchEvaluator.ListAggregator.AVERAGE)
                .build();

        System.out.println("\nLine items, matched irrespective of order:");
        for (EvaluatorResult result : lineItems.evaluateAll(
                EvalRequest.of(null, actualItems, expectedItems))) {
            Models.print(result);   // sku scores 1.0; quantity 0.5, since one of the two differs
        }
    }
}
