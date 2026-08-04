# evals4j

A Java port of LangChain's [OpenEvals](https://github.com/langchain-ai/openevals) (MIT, reviewed at
commit `43fd6af`, v0.2.0) for **Spring AI** and **LangChain4j**. Published to Maven Central under
`com.dvarahq.oss`; `0.3.0` is live.

## Layout

Maven multi-module, Java 17 baseline (both Spring AI 2.0.0 and LangChain4j 1.18.1 ship
major-version-61 bytecode), built on JDK 21. Root package `com.dvarahq.oss.evals4j`.

| module | notes |
|---|---|
| `evals4j-core` | every evaluator, prompt catalog, SPI. **No AI-framework dependency** — keep it that way |
| `evals4j-springai` / `evals4j-langchain4j` | thin adapters implementing `JudgeModel` |
| `evals4j-spring-boot-starter` | autoconfiguration; Spring AI wins when both are present |
| `evals4j-sandbox` | Docker + local `SandboxRunner` |
| `evals4j-junit5` | assertions, `EvalReport` |
| `evals4j-examples` | runnable examples. Built by CI, **never published** |
| `evals4j-bom` | dependency management |

The whole framework boundary is four small interfaces in `core/spi`: `JudgeModel` (the only one that
matters — schema in, JSON out), `EmbeddingProvider`, `EvalTracer`, `SandboxRunner`. Everything else
funnels through `ScorerRunner`, which is what keeps result shapes consistent across evaluators.

## Build

```bash
./mvnw verify                          # unit tests, fully offline, no API key
OPENAI_API_KEY=... ./mvnw -Pit verify  # plus end-to-end tests against a real model
```

Unit tests must stay offline — `FakeJudgeModel` (in core's test-jar, also published) scripts judge
responses. Sandbox tests skip themselves when no Docker daemon is reachable.

## Parity discipline

This is a port, and the value is in matching upstream, not in improving on it.

- **The 33 prompts are byte-identical to upstream** and live as classpath resources, not Java text
  blocks, so no escaping rule can alter them. `PromptParityTest` checks all 33 against SHA-256
  checksums in `evals4j-core/src/test/resources/parity/prompt-checksums.txt`. Do not reformat them.
- **Trajectory and JSON-match behaviour is pinned to upstream's own test fixtures** (45 cases
  transcribed from the Python suite). If a change makes those fail, the change is wrong unless
  upstream changed too.
- Deliberate deviations — including two upstream bugs fixed rather than reproduced — are documented
  in [PARITY.md](PARITY.md). Add to it rather than diverging silently.
- The one upstream capability not reproduced is LangSmith export; there is no Java SDK. `EvalTracer`
  is the seam if that ever changes.

## Releasing

Tag and push.

```bash
git tag v0.2.0 && git push origin v0.2.0
```

Auto-publish is on, so a tag goes live on Central without a portal visit. **A published version is
permanent** — Central cannot replace or withdraw one. The safety net is the dry run, which builds and
signs everything without uploading:

```bash
gh workflow run release.yml -f version=0.2.0 -f dryRun=true
```

Two traps that cost real time during 0.1.0, both fixed but worth not re-introducing:

- **Never set `skipPublishing` on a module.** It is not per-module — the plugin performs the whole
  upload during the last reactor module's deploy phase, so setting it on `evals4j-examples` (which is
  last) silently skipped the entire deployment while every step still reported success. Keeping the
  examples out of the bundle is the root POM's `excludeArtifacts`.
- **The Central namespace is `com.dvarahq`, not `com.dvarahq.oss`.** The namespace is the reverse of
  the domain you own and nothing more; every groupId beneath it is then usable. Registering the longer
  form never verifies and gives no error saying why.

## Conventions

- **No `Co-Authored-By` trailers for tooling** in commits or PR bodies. GitHub users only.
- Push as the **grabdoc** account (`gh api user --jq .login` should return `grabdoc`). `gh` has
  switched to another account on its own, which is only a plain org member and 403s on this repo.
- Comments explain *why*, not what. Several non-obvious decisions are already documented in place —
  read the surrounding comment before "simplifying" something that looks odd.
