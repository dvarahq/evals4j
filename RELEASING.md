# Releasing

Artifacts are published to Maven Central by the [`release`](.github/workflows/release.yml) workflow.

## What gets published

Eight artifacts under `com.dvarahq.oss`: the parent POM, `evals4j-bom`, and the six library
modules. `evals4j-examples` is deliberately excluded — see the `excludeArtifacts` note in the root
POM.

`evals4j-core` also publishes a `tests` classifier, which is intentional: it carries
`FakeJudgeModel`, so consumers can unit-test their own evaluators offline.

## One-time setup

### 1. Claim the namespace

Register `com.dvarahq.oss` at [central.sonatype.com](https://central.sonatype.com/).

Because this is a domain-based namespace, Central verifies it through DNS rather than through a
GitHub repository: the portal issues a verification code, and you add it as a TXT record on
`dvarahq.com`.

```
Type:  TXT
Host:  @            (i.e. dvarahq.com itself)
Value: <the code the portal shows you>
```

Then press **Verify Namespace**. Propagation is usually minutes but can take longer; the portal
re-checks on demand.

Verifying `com.dvarahq.oss` also covers everything beneath it, so no further claim is needed if the
group ever gains sub-namespaces.

> If you do not control `dvarahq.com`, this namespace cannot be published — Central will not issue
> it. Switching to a `io.github.<user>` namespace, which is verified by creating a repository the
> portal names, is the fallback.

### 2. Generate a signing key

Central requires every artifact to be signed. One script does the whole thing — generates the key,
publishes the public half, and stores both secrets:

```bash
./scripts/setup-signing-key.sh --email opensource@dvarahq.com
```

In a normal terminal it prompts twice for the passphrase — once for gpg, once for `gh` — and never
handles it itself. The private key is piped from gpg into `gh`, so it never lands on disk.

Where there is no terminal (a CI runner, or any tool that runs commands with stdin closed) gpg
cannot open `/dev/tty` to ask. Supply the passphrase instead:

```bash
# generate one; it is stored as the secret and printed once
./scripts/setup-signing-key.sh --email opensource@dvarahq.com --auto-passphrase

# or provide your own
export MY_PASS='...'
./scripts/setup-signing-key.sh --email opensource@dvarahq.com --passphrase-env MY_PASS
```

Without one of these the script stops immediately with instructions rather than hanging on a prompt
nobody can answer.

`--check` verifies prerequisites without creating anything; `--skip-secrets` prints the commands
instead of running them; `--key-id` reuses a key you already have.

<details>
<summary>Doing it by hand</summary>

```bash
gpg --quick-generate-key "Dvara <opensource@dvarahq.com>" rsa4096 sign 3y
gpg --list-secret-keys --keyid-format=long      # key id follows rsa4096/

# Publish the public key, or Central will reject the deployment.
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Pipe, so the private key never lands on disk.
gpg --armor --export-secret-keys <KEY_ID> | gh secret set GPG_PRIVATE_KEY --repo dvarahq/evals4j
gh secret set GPG_PASSPHRASE --repo dvarahq/evals4j
```

Use `--export-secret-keys`, not `--export` — the public key cannot sign. Keyserver propagation takes
a few minutes; if a publish fails on key lookup, wait and retry rather than regenerating.

</details>

The key expires after three years. Renew it before then, or releases start failing signature
validation.

### 3. Add the repository secrets

**Settings → Secrets and variables → Actions** on
[dvarahq/evals4j](https://github.com/dvarahq/evals4j/settings/secrets/actions):

| secret | what it is |
|---|---|
| `CENTRAL_USERNAME` | user-token username from the Central Portal (**not** your login) |
| `CENTRAL_PASSWORD` | the matching user-token password |
| `GPG_PRIVATE_KEY` | the full ASCII-armoured block, `-----BEGIN…` through `-----END…` |
| `GPG_PASSPHRASE` | the key's passphrase |

Generate the user token under **Account → Generate User Token** in the portal.

Or from the command line:

```bash
gh secret set CENTRAL_USERNAME --repo dvarahq/evals4j
gh secret set CENTRAL_PASSWORD --repo dvarahq/evals4j
```

Each prompts for the value without echoing it. The two GPG secrets are handled by
`scripts/setup-signing-key.sh` in step 2 — set them there rather than by hand, so the private key is
piped instead of exported to a file.

> Secrets do **not** survive a repository transfer, and they are not inherited from the org unless
> defined at org level. Both were empty after the move to `dvarahq`.

### 4. How the credentials reach Maven

Nothing reads a secret directly. The workflow turns each one into an environment variable, and a
different tool picks each up — worth knowing, because a rename on either side breaks the chain
silently.

| GitHub secret | environment variable | who reads it |
|---|---|---|
| `CENTRAL_USERNAME` | `MAVEN_USERNAME` | `settings.xml` → `central-publishing-maven-plugin` |
| `CENTRAL_PASSWORD` | `MAVEN_PASSWORD` | `settings.xml` → `central-publishing-maven-plugin` |
| `GPG_PASSPHRASE` | `MAVEN_GPG_PASSPHRASE` | `maven-gpg-plugin` reads this env var by default |
| `GPG_PRIVATE_KEY` | — | imported into the keyring by `actions/setup-java` |

The Central credentials are the indirect pair. `actions/setup-java` is configured with:

```yaml
server-id: central
server-username: MAVEN_USERNAME
server-password: MAVEN_PASSWORD
```

which makes it write `~/.m2/settings.xml` containing a `central` server whose values are
`${env.MAVEN_USERNAME}` / `${env.MAVEN_PASSWORD}` placeholders. Maven resolves those at run time from
the environment. The plugin looks the credentials up by the server id `central`, which is set in the
root POM as `<publishingServerId>`. **The Maven-side name is `central` in three places and they must
agree**: `server-id` here, `publishingServerId` in the POM, and the `<server><id>` in `settings.xml`.

The GPG passphrase is the direct one: `maven-gpg-plugin`'s `passphraseEnvName` defaults to
`MAVEN_GPG_PASSPHRASE`, so exporting that variable is enough — no `settings.xml` entry needed.

### 5. Releasing from a laptop

The same environment variables work locally, but **Maven will not read `MAVEN_USERNAME` on its own**
— that only works in CI because `setup-java` generated a `settings.xml` referencing it. Locally you
supply the equivalent yourself:

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${env.CENTRAL_USERNAME}</username>
      <password>${env.CENTRAL_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Then:

```bash
export CENTRAL_USERNAME='...'          # leading space keeps it out of shell history
export CENTRAL_PASSWORD='...'
export MAVEN_GPG_PASSPHRASE='...'

./mvnw -Prelease deploy -DskipTests
```

Referencing `${env.…}` rather than pasting the values means `settings.xml` holds no secrets and can
be kept in dotfiles.

If you would rather not import the signing key into your local keyring, `maven-gpg-plugin` can take
it from the environment too — `MAVEN_GPG_KEY` (its default `keyEnvName`) accepts the armoured
private key:

```bash
export MAVEN_GPG_KEY="$(gpg --armor --export-secret-keys <KEY_ID>)"
export MAVEN_GPG_PASSPHRASE='...'
```

Straight from gpg — no key file to forget about afterwards. `.gitignore` covers the usual key
extensions anyway, as a backstop.

Prefer the tagged workflow for real releases. Local publishing is for reproducing a CI failure, and
it is easy to publish from a dirty tree by accident — Central cannot un-publish a version.

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

**"Unable to get publisher server properties for server id: central"** — Maven found no `central`
server, so the credentials never reached the plugin. Work back along the chain in §4: is the secret
set, does the workflow map it to `MAVEN_USERNAME` / `MAVEN_PASSWORD`, and does `settings.xml` have a
`<server>` with id `central`? Running locally without the `settings.xml` from §5 produces exactly
this error, because there is nothing to resolve `${env.…}` into.

**Credentials look set but Central returns 401** — the Central Portal wants the **user token**, not
your portal login. Regenerate it under **Account → Generate User Token**; the username is an opaque
string, not an email.

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
