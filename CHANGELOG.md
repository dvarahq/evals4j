# Changelog

Notable changes per release. Versions are evals4j's own; the OpenEvals version it ports is recorded
in [PARITY.md](PARITY.md).

## 0.5.0 — unreleased

In progress. `main` builds as `0.5.0-SNAPSHOT`; the version on Maven Central is
[0.4.0](#040--2026-08-04).

### Documentation

- Corrected the upstream re-check in [PARITY.md](PARITY.md). It read "upstream `main` is 65 commits
  past `43fd6af`", which conflated two comparisons: 65 is measured from the `openevals==0.2.0` tag
  (`cf22d62`), while `43fd6af` — the commit this port was reviewed at — is itself 63 commits past
  that tag, leaving `main` only 2 commits ahead of it. PARITY now names both bases and points the
  re-check at the reviewed commit, since that is the count that says anything about port work.

  The 0.4.0 entry below repeats the same mislabelling and is left standing as the historical record.
  Neither number changes the conclusion either version drew: every commit in both comparisons is a
  dependency or lockfile bump.

## 0.4.0 — 2026-08-04

Upstream OpenEvals is still unchanged since its 0.2.0 tag — 65 commits ahead of `43fd6af`, all of
them dependency and lockfile bumps — so this release is evals4j's own work again: it makes the report
show a trend, which its own docs had claimed since 0.2.0, and removes the per-evaluator tracer wiring
that 0.3.0's extension could not avoid.

### Added

- `EvalScope`, a tracer that evaluators fall back to when they were not given one. `ScorerRunner`
  consults it only for an evaluator with no tracer configured, so an explicit one — including
  `NO_OP` — always wins.

  It is a dynamic scope rather than a global default: opened by a caller, confined to that thread,
  closed again, and nesting restores what was there before. All four asynchronous hand-offs capture
  it and re-open it on the far side, since a thread-confined value does not follow work onto a pool.
- `EvalReport.writeJson` / `readJson` and a `writeMarkdown` overload taking a baseline, so a run can
  be compared against the one before it.
- `EvalReportExtension` writes that JSON beside the Markdown and reads it back as the next run's
  baseline, adding a change column to the table. `evals4j.report.maxDrop` optionally fails the run
  when a key falls further than that; it is evaluated once the run ends, because a key scored by two
  classes only has a complete mean once both have run.

  That end-of-run hook needs **JUnit 5.13 or newer**, which is when Jupiter began closing
  `AutoCloseable` values left in its store. On an older Jupiter, or with
  `junit.jupiter.extensions.store.close.autocloseable.enabled=false`, the report is still written but
  the gate never fires. Everything else in the extension works on any Jupiter 5.

### Changed

- **`@EvalSuite` no longer needs `.tracer(report)` on every evaluator.** It opens an `EvalScope`
  around each test carrying the run's report. Suites that pass a tracer explicitly are unaffected.
- Evaluators keep an unset tracer as `null` instead of collapsing it to `NO_OP` at construction,
  which is what lets the fallback happen. This includes
  `Evaluator.from(runName, feedbackKey, scorer)`, the entry point for custom evaluators, so a custom
  evaluator picks up an open scope exactly as a built-in one does. No effect on an evaluator that was
  given a tracer.

## 0.3.0 — 2026-08-04

The JUnit 5 extension 0.2.0 promised, and nothing else — `evals4j-junit5` is the only module that
changed. Upstream OpenEvals is still unchanged since its 0.2.0 tag.

### Added

- `EvalReportExtension`, the JUnit 5 extension 0.2.0 promised. Register it with `@ExtendWith`, take
  an `EvalReport` as a test parameter, and the report is created, shared across every class in the
  run and written out — no `@AfterAll` calling `writeMarkdown` by hand. The file defaults to
  `target/evals4j-report.md` and moves with the `evals4j.report` system property.

  Wiring a tracer into an evaluator stays explicit (`.tracer(report)`): an evaluator takes its tracer
  at construction, and a global default one would be shared mutable state across every module.
- `@EvalSuite`, a composed annotation equivalent to `@ExtendWith(EvalReportExtension.class)`. It
  carries no attributes on purpose — the obvious one would be a report path, but the report is one
  per run, so two classes naming different files would have no defensible winner.
- Automatic registration through `META-INF/services`, for a build that sets
  `junit.jupiter.extensions.autodetection.enabled=true` and wants no annotation at all. JUnit leaves
  autodetection off by default, so the jar on its own still changes nothing.

## 0.2.0 — 2026-08-03

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
