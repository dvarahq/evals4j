# evals4j

[![build](https://github.com/dvarahq/evals4j/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/dvarahq/evals4j/actions/workflows/build.yml)
[![maven central](https://img.shields.io/maven-central/v/com.dvarahq.oss/evals4j-core?label=maven%20central)](https://central.sonatype.com/artifact/com.dvarahq.oss/evals4j-core)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![java](https://img.shields.io/badge/java-17%2B-orange.svg)](#install)

Open-source evaluators for LLM applications on the JVM, for **Spring AI** and **LangChain4j**.

A Java port of LangChain's [OpenEvals](https://github.com/langchain-ai/openevals) — the same
LLM-as-judge engine, the same 33-prompt catalog, the same trajectory and structured-output matchers.
evals4j is an independent project and is not affiliated with or endorsed by LangChain, Inc.

Evals are to LLM applications what tests are to ordinary software: a way to find out whether a
prompt change made things better or worse before your users do.

```java
LlmAsJudge conciseness = LlmAsJudge.builder()
        .prompt(Prompts.CONCISENESS_PROMPT)
        .feedbackKey("conciseness")
        .model(judge)
        .build();

EvaluatorResult result = conciseness.evaluate(
        "How is the weather in San Francisco?",
        "Thanks for asking! The current weather in San Francisco is sunny and 90 degrees.");

result.score();    // false
result.comment();  // "The response includes an unnecessary pleasantry ... Thus, the score should be: false."
```

---

## Install

> **Not yet released.** Nothing has been published under `com.dvarahq.oss` yet, so the Maven Central
> badge above reads *not found* and the coordinates below will not resolve. Until the first release,
> build from source:
>
> ```bash
> git clone https://github.com/dvarahq/evals4j.git && cd evals4j && ./mvnw install
> ```

Requires **Java 17+**. Add the BOM, then whichever modules you need.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.dvarahq.oss</groupId>
      <artifactId>evals4j-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

| module | what it gives you |
|---|---|
| `evals4j-core` | every evaluator, the prompt catalog, the SPI. No AI-framework dependency. |
| `evals4j-springai` | drives evaluators with a Spring AI `ChatModel` |
| `evals4j-langchain4j` | drives evaluators with a LangChain4j `ChatModel` |
| `evals4j-spring-boot-starter` | auto-configures whichever of the two is on your classpath |
| `evals4j-sandbox` | Docker and local sandbox runners, for evaluating generated code |
| `evals4j-junit5` | assertions and a report for eval suites |

Runnable examples live in [`evals4j-examples`](evals4j-examples) — start with
[`AgentTrajectoryExample`](evals4j-examples/src/main/java/com/dvarahq/oss/evals4j/examples/AgentTrajectoryExample.java),
which needs no API key.

### Spring Boot

Add the starter and you are done — an existing `ChatModel` bean is enough.

```xml
<dependency>
  <groupId>com.dvarahq.oss</groupId>
  <artifactId>evals4j-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>com.dvarahq.oss</groupId>
  <artifactId>evals4j-springai</artifactId>
</dependency>
```

```java
@Autowired JudgeModel judge;   // auto-configured from your ChatModel
```

### Without Spring Boot

```java
// Spring AI
JudgeModel judge = SpringAiJudgeModel.of(chatModel);

// LangChain4j
JudgeModel judge = LangChain4jJudgeModel.of(chatModel);
```

---

## LLM-as-judge

The centrepiece. A model grades an output against a prompt, and returns a structured score plus its
reasoning.

```java
LlmAsJudge judge = LlmAsJudge.builder()
        .prompt(Prompts.CORRECTNESS_PROMPT)
        .feedbackKey("correctness")
        .model(judgeModel)
        .build();

EvaluatorResult result = judge.evaluate(
        "Who was the first president of the United States?",
        "John Adams",
        "George Washington");   // reference
```

**Score shapes.** Boolean by default; `.continuous(true)` for 0.0–1.0; `.choices(0.0, 0.5, 1.0)` to
restrict it to specific values. Choices win over continuous.

**Reasoning** is on by default. It costs tokens, but it is what makes a low score actionable — and
the schema forces the model to reason before it scores, which measurably improves consistency. Turn
it off with `.useReasoning(false)`.

**Extra prompt variables** are supplied per call:

```java
judge.evaluate(EvalRequest.builder()
        .outputs(answer)
        .variable("context", retrievedDocuments)   // fills {context}
        .build());
```

**Few-shot examples** calibrate the judge:

```java
.fewShotExample(FewShotExample.of("2+2?", "4", true, "correct and direct"))
.fewShotExample(FewShotExample.of("2+2?", "Well, mathematically...", false, "padded"))
```

**Custom output schema** for classification rather than scoring — read it with `evaluateRaw`:

```java
JsonNode raw = LlmAsJudge.builder()
        .prompt("Classify: {outputs}")
        .model(judgeModel)
        .outputSchema(mySchema)
        .build()
        .evaluateRaw(EvalRequest.ofOutputs(ticket));
```

---

## Prompt catalog

33 prompts, copied verbatim from OpenEvals and verified byte-for-byte by a checksum test.

| category | prompts |
|---|---|
| **quality** | `CORRECTNESS`, `CONCISENESS`, `HALLUCINATION`, `ANSWER_RELEVANCE`, `CODE_CORRECTNESS` (+ `_WITH_REFERENCE_OUTPUTS`), `PLAN_ADHERENCE`, `LAZINESS` |
| **rag** | `RAG_GROUNDEDNESS`, `RAG_HELPFULNESS`, `RAG_RETRIEVAL_RELEVANCE` |
| **safety** | `TOXICITY`, `FAIRNESS` |
| **security** | `PII_LEAKAGE`, `PROMPT_INJECTION`, `CODE_INJECTION` |
| **trajectory** | `TRAJECTORY_ACCURACY` (+ `_WITH_REFERENCE`), `TOOL_SELECTION` |
| **conversation** | `PERCEIVED_ERROR`, `WINS`, `TASK_COMPLETION`, `KNOWLEDGE_RETENTION`, `USER_SATISFACTION`, `AGENT_TONE`, `LANGUAGE_DETECTION`, `SUPPORT_INTENT` |
| **image** | `EXPLICIT_CONTENT`, `SENSITIVE_IMAGERY` |
| **voice** | `AUDIO_QUALITY`, `TRANSCRIPTION_ACCURACY`, `USER_INTERRUPTS`, `VOCAL_AFFECT` |

Each constant's Javadoc lists the variables it needs. Notably: the RAG prompts want `{context}`,
`PLAN_ADHERENCE` wants `{plan}`, the injection prompts grade `{inputs}` only, and the conversation
and trajectory prompts take a whole message list as `outputs`.

### Which RAG evaluator?

| evaluator | compares | needs a reference? |
|---|---|---|
| correctness | answer vs. expected answer | yes |
| helpfulness | answer vs. question | no |
| groundedness | answer vs. retrieved context | no |
| retrieval relevance | retrieved context vs. question | no |

### Multimodal

Prompts with an `{attachments}` placeholder take images or audio, spliced in as content blocks:

```java
judge.evaluate(EvalRequest.builder()
        .inputs("describe this")
        .outputs(caption)
        .attachment(Attachment.ofUrl("https://example.com/photo.png"))
        .build());
```

---

## Evaluators that need no model

```java
ExactMatch.create()               // canonical JSON, map key order ignored
LevenshteinDistance.create()      // normalized similarity in [0,1]
EmbeddingSimilarity.create(embeddings)   // cosine (default) or dot product
```

### Structured output

Compare extracted objects key by key. Keys with a rubric are judged by a model; the rest are
compared for equality — which is exactly what you want when one field is a free-text summary and the
rest are exact values.

```java
Evaluator evaluator = JsonMatchEvaluator.builder()
        .excludeKeys("id", "extracted_at")
        .rubric("summary", "Does the summary capture the main points of the reference?")
        .model(judgeModel)
        .build();

List<EvaluatorResult> results = evaluator.evaluateAll(
        EvalRequest.of(null, extracted, expected));
// json_match:summary, json_match:customer_name, ...
```

Add `.aggregator(AVERAGE)` or `.aggregator(ALL)` to collapse to one score. Lists are supported with
four pairing strategies (`.listMatchMode(...)`) and a second aggregation across elements
(`.listAggregator(...)`).

### Agent trajectories

Compare the tool calls an agent made against a reference run.

```java
Evaluator evaluator = TrajectoryMatchEvaluator.builder()
        .matchMode(TrajectoryMatchMode.SUPERSET)          // agent did at least the reference calls
        .toolArgsMatchMode(ToolArgsMatchMode.EXACT)
        .overrideOnKeys("book_flight", "flight_no")       // ignore the timestamp on this one tool
        .build();
```

| mode | passes when |
|---|---|
| `STRICT` | same messages, same roles, same tool calls (message *content* is never compared) |
| `UNORDERED` | same set of tool calls, any order or grouping |
| `SUBSET` | every call the agent made appears in the reference — it may do less |
| `SUPERSET` | every reference call was made — it may do more |

Per-tool overrides take a match mode, a list of dotted argument paths (`"time.start"`), or your own
predicate.

When there is no single correct path, judge it instead:

```java
TrajectoryLlmAsJudge.builder().model(judgeModel).build()
        .evaluate(EvalRequest.ofOutputs(messages));
```

---

## Generated code

```java
// Does it compile? Uses the JDK Compiler API in-process — nothing to install.
JavacEvaluator.builder()
        .extractionStrategy(CodeExtractionStrategy.MARKDOWN_CODE_BLOCKS)
        .build();

// Does it run? Needs a sandbox.
ExecutionEvaluator.builder()
        .sandbox(DockerSandboxRunner.builder().image("python:3.12-slim").build())
        .fileName("outputs.py")
        .command("python", "outputs.py")
        .build();

// Does it do what was asked?
CodeLlmAsJudge.builder().model(judgeModel).build();

// Any other checker: mypy, tsc, ruff, shellcheck.
CliCheckEvaluator.builder().command("mypy", "--ignore-missing-imports").fileName("outputs.py").build();
```

Extraction strategies: `NONE`, `MARKDOWN_CODE_BLOCKS` (skips shell and JSON blocks), `LLM`, or your
own function. When nothing can be extracted the result is a failure carrying
`code_extraction_failed` metadata, not an exception.

> **Running model-generated code is running untrusted input.** `DockerSandboxRunner` defaults to no
> network, capped memory and CPU, a read-only root filesystem, and all Linux capabilities dropped.
> `LocalProcessSandboxRunner` is *not* a security boundary — it is for CI and for code you wrote.

---

## Multi-turn simulation

Some failures only appear over several turns. Simulate a user, let your app respond, and score the
whole transcript.

```java
SimulationResult result = MultiturnSimulation.builder()
        .app((message, threadId) -> myAgent.chat(threadId, message.text()))
        .user(SimulatedUser.llm("You are a customer whose order never arrived.", judgeModel))
        .maxTurns(5)
        .stopWhen((trajectory, turn) -> mentionsRefund(trajectory))
        .trajectoryEvaluator(LlmAsJudge.builder()
                .prompt(Prompts.TASK_COMPLETION_PROMPT)
                .feedbackKey("task_completion")
                .model(judgeModel)
                .build())
        .run();

result.trajectory();        // the full conversation
result.evaluatorResults();  // the scores
```

The app receives only the newest user message and a stable `threadId`, so the simulation exercises
your app's own memory rather than replaying a transcript into it. `SimulatedUser.scripted(...)`
takes a fixed list instead.

---

## In your test suite

```java
@Test
void answersStayConcise() {
    EvalAssert.assertPassed(conciseness.evaluate(question, myApp.answer(question)));
}

@Test
void answersStayCorrect() {
    EvalAssert.assertScoreAtLeast(0.8, correctness.evaluate(question, answer, reference));
}
```

Failure messages carry the judge's reasoning, because "expected true but was false" tells you
nothing about an LLM score.

`EvalReport` implements `EvalTracer`; register it on your evaluators and write a summary at the end
of the run — the trend across runs is what matters, not one pass or fail.

---

## Writing your own

```java
Evaluator startsWithGreeting = Evaluator.from(
        "greeting_check", "greeting",
        request -> ScorerOutput.of(
                request.outputs().toString().startsWith("Hello"),
                "checked the opening words"));
```

`ScorerRunner` turns the output into results and fires the tracer, so a custom evaluator behaves
like a built-in one.

---

## Structured output and providers

The judge asks the model for JSON conforming to a schema. Providers differ in how well they support
that, so both adapters take a `StructuredOutputMode`:

- **`AUTO`** (default) — use the provider's schema enforcement, fall back to prompting if it is
  rejected. Spring AI goes through `StructuredOutputChatOptions`; LangChain4j through
  `JsonRawSchema`, which passes the schema through untouched.
- **`NATIVE`** — require provider-side enforcement, fail if unsupported.
- **`PROMPTED`** — put the schema in the prompt and recover the JSON from the reply. Works with any
  model, including local ones.

Prompted-mode recovery scans for a brace-balanced region, ignoring braces inside string literals, so
a fenced or chatty reply still parses.

---

## Build

```bash
./mvnw verify                # unit tests, fully offline, no API key
OPENAI_API_KEY=... ./mvnw -Pit verify   # plus end-to-end tests against a real model
```

Sandbox tests requiring Docker skip themselves when no daemon is reachable. The examples are
compiled on every build, so an API change that breaks them breaks CI.

Publishing to Maven Central is handled by the [`release` workflow](.github/workflows/release.yml) —
see [RELEASING.md](RELEASING.md).

---

## Differences from OpenEvals

Documented in [PARITY.md](PARITY.md), including where a bug in upstream was fixed rather than
reproduced.

## License

MIT. Prompt texts and several algorithms are derived from OpenEvals (MIT, © 2025 LangChain, Inc.) —
see [NOTICE](NOTICE).
