# Releasing

Artifacts are published to Maven Central by the [`release`](.github/workflows/release.yml) workflow.

## What gets published

Eight artifacts under `io.github.grabdoc`: the parent POM, `evals4j-bom`, and the six library
modules. `evals4j-examples` is deliberately excluded — see the `excludeArtifacts` note in the root
POM.

`evals4j-core` also publishes a `tests` classifier, which is intentional: it carries
`FakeJudgeModel`, so consumers can unit-test their own evaluators offline.

## One-time setup

### 1. Claim the namespace

Register `io.github.grabdoc` at [central.sonatype.com](https://central.sonatype.com/), and verify it
by creating the public repository the portal names.

### 2. Generate a signing key

Central requires every artifact to be signed.

```bash
gpg --gen-key                                   # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long      # note the key id

# Publish the public key, or Central will reject the deployment.
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export the private key for the secret below.
gpg --armor --export-secret-keys <KEY_ID>
```

### 3. Add the repository secrets

**Settings → Secrets and variables → Actions**:

| secret | what it is |
|---|---|
| `CENTRAL_USERNAME` | user-token username from the Central Portal (**not** your login) |
| `CENTRAL_PASSWORD` | the matching user-token password |
| `GPG_PRIVATE_KEY` | the full ASCII-armoured block, `-----BEGIN…` through `-----END…` |
| `GPG_PASSPHRASE` | the key's passphrase |

Generate the user token under **Account → Generate User Token** in the portal.

## Cutting a release

### Rehearse first

**Actions → release → Run workflow**, set the version, leave **dry run** ticked.

This builds, tests, generates javadoc and sources, and signs everything — which is where releases
actually break — without contacting Central or using its credentials.

### Then publish

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow builds from the tag, publishes, and opens a GitHub release with generated notes.

The version is taken from the tag and applied at build time. The POM stays on `-SNAPSHOT` in git, so
there is no release commit to forget and no version to keep in sync by hand.

### Confirm

By default the deployment is **staged, not published** — Central has validated it, but it is not
live until you press **Publish** at
[central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).

That deliberate pause exists because publishing to Central is permanent: a version can never be
replaced or withdrawn. To skip it, run the workflow manually with **auto publish** ticked.

Artifacts appear on `search.maven.org` within a few hours.

## After a release

Bump the SNAPSHOT if the next version is not a patch:

```bash
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "build: start 0.2.0-SNAPSHOT"
```

## If it fails

**"Unable to get publisher server properties for server id: central"** — the `CENTRAL_USERNAME` /
`CENTRAL_PASSWORD` secrets are missing or misnamed.

**Signing hangs, then the job times out** — gpg is waiting for a passphrase prompt. The release
profile passes `--pinentry-mode loopback` to prevent this; check that `GPG_PASSPHRASE` is set and
that the profile's `gpgArguments` are intact.

**Central rejects the deployment** — the build fails rather than going green, because `waitUntil` is
`VALIDATED`. The portal shows the reason; the usual causes are an unpublished public key, a missing
javadoc or sources jar, or POM metadata (name, description, url, licence, developers, scm) that
Central requires. All of those are present in the root POM.

**The version is rejected before anything runs** — the workflow only accepts `1.2.3`, optionally
with `-alpha1`, `-beta1` or `-rc1`. A typo'd tag fails immediately rather than publishing something
permanent under the wrong name.
