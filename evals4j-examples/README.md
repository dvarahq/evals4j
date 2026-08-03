# Examples

Runnable programs, built by CI so they cannot drift from the API.

Nothing here is published to Maven Central — the module exists to be read and run.

| example | what it shows | needs a key? |
|---|---|---|
| [`AgentTrajectoryExample`](src/main/java/io/github/grabdoc/evals4j/examples/AgentTrajectoryExample.java) | tool-call matching, match modes, per-tool argument overrides | **no** |
| [`QuickstartExample`](src/main/java/io/github/grabdoc/evals4j/examples/QuickstartExample.java) | LLM-as-judge: conciseness, correctness, continuous scores | yes |
| [`RagExample`](src/main/java/io/github/grabdoc/evals4j/examples/RagExample.java) | the four RAG evaluators, and why they are separate | yes |
| [`StructuredExtractionExample`](src/main/java/io/github/grabdoc/evals4j/examples/StructuredExtractionExample.java) | per-key scoring of extracted objects, exact fields plus a rubric field | yes |
| [`SimulationExample`](src/main/java/io/github/grabdoc/evals4j/examples/SimulationExample.java) | driving an agent through a whole conversation, then scoring it | yes |
| [`EvalSuiteExampleTest`](src/test/java/io/github/grabdoc/evals4j/examples/EvalSuiteExampleTest.java) | an eval suite as a JUnit test class | partly |

`QuickstartExample`, `StructuredExtractionExample` and `SimulationExample` use Spring AI;
`RagExample` uses LangChain4j. The evaluator code is identical either way — only the two lines in
[`Models`](src/main/java/io/github/grabdoc/evals4j/examples/Models.java) that build the judge differ.

## Running them

Start with the one that needs nothing:

```bash
./mvnw -q -pl evals4j-examples -am install -DskipTests
./mvnw -q -pl evals4j-examples exec:java \
    -Dexec.mainClass=io.github.grabdoc.evals4j.examples.AgentTrajectoryExample
```

```
Exact argument matching — fails on the extra flag and the timestamp:
  STRICT     false
  UNORDERED  false
  SUBSET     false
  SUPERSET   false

Superset argument matching — extras allowed, but the timestamp still differs:
  SUPERSET   false

Per-tool overrides — pin flight_no, ignore incidental arguments:
  SUPERSET   true

Agent skipped the pricing tool and guessed instead:
  SUPERSET   false  <- the regression this test exists to catch
```

The rest call a real model:

```bash
export OPENAI_API_KEY=sk-...
export EVALS4J_MODEL=gpt-5.4          # optional

./mvnw -q -pl evals4j-examples exec:java \
    -Dexec.mainClass=io.github.grabdoc.evals4j.examples.QuickstartExample
```

The eval suite runs as an ordinary test. Its deterministic half always runs; the model-backed half
skips without a key:

```bash
./mvnw -q -pl evals4j-examples test
```

## Where to start

- **Never used an eval library before** — `QuickstartExample`.
- **Testing an agent** — `AgentTrajectoryExample`, then `SimulationExample`.
- **Testing a RAG pipeline** — `RagExample`. The four evaluators tell you whether the retriever or
  the generator is at fault, which one score cannot.
- **Testing extraction or tool arguments** — `StructuredExtractionExample`.
- **Wiring evals into an existing test suite** — `EvalSuiteExampleTest`.
