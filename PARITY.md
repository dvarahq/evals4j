# Parity with OpenEvals

evals4j is a Java port of [OpenEvals](https://github.com/langchain-ai/openevals), reviewed at
commit `43fd6af` (v0.2.0). This file records what maps to what, and every place the port behaves
differently.

## How parity is enforced

Behaviour is pinned to upstream's own test fixtures rather than to a reading of its source:

- `TrajectoryMatchParityTest` — 24 cases transcribed from
  `python/tests/trajectory/test_trajectory.py`, including the full mode × tool-args matrix.
- `JsonMatchParityTest` — 21 cases from `python/tests/test_json.py`, covering both levels of list
  aggregation.
- `PromptParityTest` — SHA-256 of all 33 prompts, computed from the upstream Python sources at the
  commit above. Any edit to a prompt resource fails the build.

## API mapping

| OpenEvals | evals4j |
|---|---|
| `create_llm_as_judge` | `LlmAsJudge.builder()` |
| `exact_match` | `ExactMatch.create()` |
| `levenshtein_distance` | `LevenshteinDistance.create()` |
| `create_embedding_similarity_evaluator` | `EmbeddingSimilarity.create(EmbeddingProvider)` |
| `create_json_match_evaluator` | `JsonMatchEvaluator.builder()` |
| `create_trajectory_match_evaluator` | `TrajectoryMatchEvaluator.builder()` |
| `create_trajectory_llm_as_judge` | `TrajectoryLlmAsJudge.builder()` |
| `create_code_llm_as_judge` | `CodeLlmAsJudge.builder()` |
| `create_pyright_evaluator`, `create_mypy_evaluator` | `JavacEvaluator`, `CliCheckEvaluator` |
| `create_e2b_execution_evaluator` | `ExecutionEvaluator` + a `SandboxRunner` |
| `run_multiturn_simulation` | `MultiturnSimulation.builder().run()` |
| `create_llm_simulated_user` | `SimulatedUser.llm(...)` |
| `EvaluatorResult` | `EvaluatorResult` (`comment` still carries the judge's reasoning) |
| `openevals.prompts.*` | `Prompts.*` — same 33 names |

Feedback keys are unchanged: `exact_match`, `levenshtein_distance`, `embedding_similarity`,
`json_match:<key>` / `:average` / `:all`, `trajectory_{strict,unordered,subset,superset}_match`,
`code_correctness`. Run names follow upstream too, including `llm_as_judge` versus
`llm_as_<feedbackKey>_judge`.

## Intentional differences

### Two upstream bugs are fixed, not reproduced

**Uninitialized index in `superset` list mode.** In `json/match.py`, the `superset` branch of
`_prepare_parameters` never initializes `best_match_idx` before its inner loop, unlike the
`same_elements`/`subset` branch which sets it to `None`. As a result the `if best_match_idx is not
None` guard reads a stale value from the previous iteration, its "there were extra reference items"
fallback is unreachable, and an empty `outputs` list raises `NameError`. evals4j initializes the
index per iteration, which makes the fallback reachable: a reference element with no output to pair
with is recorded and scores 0.

*Effect:* differs from upstream only when the output list is shorter than the reference list under
`SUPERSET`, where upstream crashes or mispairs and evals4j scores the unmatched reference 0.

**Missing zero-fill in the async JSON-match path.** Upstream's synchronous non-LLM branch fills
missing list indices with 0, and its async twin omits that block, so the same input scores
differently depending on which API you call. evals4j has one implementation and always fills.

### Structural differences

| upstream | evals4j | why |
|---|---|---|
| pyright / mypy evaluators | `JavacEvaluator` (JDK Compiler API, in-process) | The JVM analogue of "type-check the generated code", with nothing to install. `CliCheckEvaluator` keeps the shell-out shape for any other language. |
| E2B cloud sandbox | `SandboxRunner` SPI, Docker and local implementations | E2B has no Java SDK. Docker is the portable equivalent. |
| LangSmith run-tree metadata | `EvalTracer` SPI, with slf4j and Micrometer implementations | No LangSmith Java SDK exists. **This is the one upstream capability not reproduced** — see below. |
| pytest / vitest plugin | `evals4j-junit5` | Assertions, `EvalReport`, and `@EvalSuite` — a JUnit `Extension` that supplies the report and writes it out, with `META-INF/services` registration for a module that wants no annotation. Attaching the report to an evaluator stays explicit, where the pytest plugin patches itself in. **`@EvalSuite` is unreleased**: it is on `main` for 0.3.0, and 0.2.0 has the assertions and `EvalReport` only. |
| separate `*_async` twin per evaluator | `evaluate` + `evaluateAsync` on one type | Java does not need duplicated call sites. |
| `code_extraction` via two bound tools | one structured-output schema with a `has_code` flag | Expresses the same choice through the single `JudgeModel` SPI, so it works with every adapter rather than only those exposing tool binding. |
| judge configured with a model-id string | a `JudgeModel` from an adapter | There is no `init_chat_model` in Java; the framework supplies the model. |
| scorer returns a tuple, dict, or bare value | `ScorerOutput.Single` / `ScorerOutput.Keyed` | Same four shapes, made explicit instead of inspected at runtime. |

### Prompt templating

Upstream prompts are Python `str.format` templates. `PromptTemplate` fills `{name}` placeholders
with one deliberate difference: a brace run that is not a well-formed `{identifier}` is left alone,
where Python raises. Interpolated values are frequently JSON, and a nested `{"a": 1}` should not
break formatting. A placeholder that *is* well-formed but has no supplied value still throws,
matching Python's `KeyError`.

### Not implemented

**LangSmith export.** OpenEvals stamps `__ls_framework`, `__ls_evaluator` and `__ls_language` onto
the LangSmith run tree and logs feedback through the pytest plugin. There is no LangSmith Java SDK,
and an untested hand-rolled REST client would be worse than none. The `EvalTracer` SPI is the seam
where such an implementation would go; `Slf4jEvalTracer` and `MicrometerEvalTracer` ship today, and
the Spring Boot starter registers one automatically — Micrometer when the application has a
`MeterRegistry`, slf4j otherwise. A tracer that returns a run id from `onStart` has it recorded as
each result's `sourceRunId`, which is the hook an export implementation would use.

**Streaming judges.** Neither upstream nor evals4j streams judge output; scores are read from a
complete structured response.

## Version

Ported from OpenEvals `0.2.0` (commit `43fd6af`). **That version number is upstream's, not
evals4j's** — the two happen to share it, which is easy to misread in release notes.

`43fd6af` was still the tip of upstream `main` when evals4j 0.2.0 was prepared: the commits after the
`openevals==0.2.0` tag are dependency bumps and lockfile syncs, touching only `js/package.json`,
`js/yarn.lock`, `python/pyproject.toml` and `python/uv.lock`. There is no port work outstanding. To
re-check, look at whether
[the comparison](https://github.com/langchain-ai/openevals/compare/openevals==0.2.0...main) still
lists only those four files; the prompt checksum test is what will notice if the catalog diverges.
