# Changelog

Notable changes per release. Versions are evals4j's own; the OpenEvals version it ports is recorded
in [PARITY.md](PARITY.md).

## 0.2.0 — unreleased

Upstream OpenEvals has had no functional change since its 0.2.0 tag, so this release is about
evals4j itself: one real bug, two capabilities the docs promised but never shipped, and an
asynchronous path that did nothing.

### Fixed

- **Sandbox timeouts never fired.** Both `DockerSandboxRunner` and `LocalProcessSandboxRunner` read
  the child's stdout to EOF *before* calling `waitFor(timeout)`. Because that read only returns when
  the child closes the pipe, a hung process blocked forever and the timeout was never consulted.
  Draining stdout before stderr could also deadlock once the child filled the stderr pipe buffer.
  Both streams are now drained concurrently with the wait.
- **Timed-out containers were orphaned.** Destroying the `docker run` client does not stop the
  container it started; with `--rm` and no name there was no handle left to stop it, so it kept its
  memory and CPU reservation. Each run is now named and killed by name.
- **Multi-key results came back in a random order.** `ScorerOutput.Keyed` finished with
  `Map.copyOf`, whose iteration order is salted per JVM run, so the same code produced
  `json_match:<key>` results in a different order each time it ran — and `evaluate()`, which returns
  the first result, returned an arbitrary key's score. Insertion order is now preserved.

### Added

- `MicrometerEvalTracer`, auto-configured by the Spring Boot starter when the application has a
  `MeterRegistry`. Publishes `evals4j.evaluation.score` and `evals4j.evaluation.duration`, tagged by
  evaluator and feedback key. `EvalTracer`'s javadoc had claimed this shipped since 0.1.0.
- `SpringAiMessages.fromSpringAi` and `LangChain4jMessages.fromLangChain4j` for whole conversations,
  so a real agent run can be handed to the trajectory evaluators without hand-rolling the mapping.
  Tool calls keep their ids, names and arguments.
- `Evaluator.evaluateAsync(request, Executor)` and `evaluateAllAsync(request, Executor)`, for a pool
  sized for IO rather than the common pool.
- `MultiturnSimulation.runAsync`, which also scores its trajectory evaluators concurrently.
- `ScorerRunner.runAsync`, so tracer callbacks fire around asynchronous evaluations too.
- `TrajectoryLlmAsJudge` and `CodeLlmAsJudge` builders gained `system`, `outputSchema`,
  `fewShotExamples(List)` and `choices(List)`, plus `evaluateRaw` and the `feedbackKey()` /
  `runName()` accessors `LlmAsJudge` already had.
- `ExactMatch.withTracer` and `LevenshteinDistance.withTracer` — the two most-used evaluators were
  the two that could not be observed.
- `JavacEvaluator.replaceCompilerOptions`, since `compilerOptions` only ever appended to the
  warning-silencing defaults.
- `Automatic-Module-Name` on every published jar.

### Changed

- **Asynchronous evaluation is now genuinely asynchronous.** `evaluateAsync` used to wrap the
  blocking call in `supplyAsync` on the common pool, and `JudgeModel.invokeStructuredAsync` had no
  callers at all. The model-backed evaluators now compose over it, and `evaluateAsync` routes through
  `evaluateAllAsync` so an override takes effect for both. `JsonMatchEvaluator` judges rubric keys
  concurrently on this path rather than one after another.
- `EvaluatorResult.sourceRunId` is populated when a tracer returns a `CharSequence` from `onStart`.
  It was previously always null.
- `ExecutionEvaluator` now rejects a `fileName` set without a `command`, or the reverse. The defaults
  are `outputs.py` and `[python, outputs.py]`, so changing one alone silently ran the wrong file.
- The starter's optional `micrometer-observation` dependency is now `micrometer-core`, which is where
  `MeterRegistry` lives.
- CI builds on macOS as well as Linux, exercises `-Prelease` so javadoc breakage surfaces on the pull
  request rather than during a release, and runs the integration tests on a schedule.

### Documentation

- `evals4j-junit5` no longer describes itself as providing a JUnit "extension". It provides
  assertions and `EvalReport`, which you register and write out yourself; an extension is planned for
  0.3.0.
- PARITY.md notes that its "0.2.0" is upstream's version, not evals4j's.

## 0.1.0 — 2026

First release. A Java port of OpenEvals 0.2.0 (commit `43fd6af`) for Spring AI and LangChain4j: all
33 prompts byte-identical to upstream, the LLM-as-judge engine, the string, JSON-match and trajectory
evaluators, code evaluators with Docker and local sandboxes, multi-turn simulation, JUnit 5 support
and a Spring Boot starter.
