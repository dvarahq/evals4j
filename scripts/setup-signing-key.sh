#!/usr/bin/env bash
#
# Creates the GPG signing key Maven Central requires, publishes the public half,
# and loads both halves into the repository's GitHub Actions secrets.
#
#   ./scripts/setup-signing-key.sh --email opensource@dvarahq.com
#
# The passphrase is never seen by this script: gpg prompts for it through its own
# pinentry, and `gh` prompts for it again when storing the secret. The private key
# is piped straight from gpg into gh, so it is never written to disk.
#
# Run with --check to verify prerequisites without creating anything.

set -euo pipefail

REPO="dvarahq/evals4j"
GH_ACCOUNT="grabdoc"
KEY_TYPE="rsa4096"
KEY_EXPIRY="3y"
KEYSERVERS=(keyserver.ubuntu.com keys.openpgp.org)

NAME="Dvara"
EMAIL=""
KEY_ID=""
CHECK_ONLY=false
SKIP_SECRETS=false
SKIP_PUBLISH=false

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
info()  { printf '%s==>%s %s\n' "$BOLD" "$OFF" "$1"; }
ok()    { printf '  %s✓%s %s\n' "$GREEN" "$OFF" "$1"; }
warn()  { printf '  %s!%s %s\n' "$YELLOW" "$OFF" "$1"; }
die()   { printf '  %s✗%s %s\n' "$RED" "$OFF" "$1" >&2; exit 1; }

usage() {
    cat <<EOF
Creates the GPG signing key Maven Central requires, publishes the public half,
and loads both halves into the repository's GitHub Actions secrets.

  ./scripts/setup-signing-key.sh --email opensource@dvarahq.com

The passphrase is never seen by this script: gpg prompts for it through its own
pinentry, and gh prompts for it again when storing the secret. The private key is
piped straight from gpg into gh, so it is never written to disk.

Options:
  --email <addr>     Address on the key. Required unless --key-id is given.
  --name <name>      Name on the key. Default: "$NAME"
  --key-id <id>      Use an existing key instead of generating one.
  --repo <o/r>       Target repository. Default: $REPO
  --skip-publish     Do not send the public key to the keyservers.
  --skip-secrets     Do not touch GitHub secrets. Prints the commands instead.
  --check            Verify prerequisites and exit.
  -h, --help         This message.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --email)        EMAIL="${2:-}"; shift 2 ;;
        --name)         NAME="${2:-}"; shift 2 ;;
        --key-id)       KEY_ID="${2:-}"; shift 2 ;;
        --repo)         REPO="${2:-}"; shift 2 ;;
        --skip-publish) SKIP_PUBLISH=true; shift ;;
        --skip-secrets) SKIP_SECRETS=true; shift ;;
        --check)        CHECK_ONLY=true; shift ;;
        -h|--help)      usage; exit 0 ;;
        *)              die "Unknown option: $1 (try --help)" ;;
    esac
done

# ---------------------------------------------------------------- prerequisites

info "Checking prerequisites"

command -v gpg >/dev/null || die "gpg not found. Install it: brew install gnupg"
ok "gpg $(gpg --version | head -1 | awk '{print $3}')"

if [[ "$SKIP_SECRETS" == false ]]; then
    command -v gh >/dev/null || die "gh not found. Install it: brew install gh"

    active="$(gh api user --jq .login 2>/dev/null || true)"
    [[ -n "$active" ]] || die "gh is not authenticated. Run: gh auth login"

    if [[ "$active" != "$GH_ACCOUNT" ]]; then
        # gh has switched accounts on its own before, and the wrong one 403s on
        # the org repo — fail here rather than halfway through.
        die "gh is authenticated as '$active', expected '$GH_ACCOUNT'.
      Run: gh auth switch --hostname github.com --user $GH_ACCOUNT"
    fi
    ok "gh authenticated as $active"

    gh repo view "$REPO" >/dev/null 2>&1 || die "Cannot see $REPO. Check the name and your access."
    ok "repository $REPO reachable"
fi

if [[ "$CHECK_ONLY" == true ]]; then
    info "Prerequisites satisfied. Re-run without --check to create the key."
    exit 0
fi

# ---------------------------------------------------------------- the key

if [[ -z "$KEY_ID" ]]; then
    [[ -n "$EMAIL" ]] || die "--email is required (or pass --key-id to reuse a key)"

    # Reuse rather than silently creating a second key for the same address.
    existing="$(gpg --list-secret-keys --keyid-format=long "$EMAIL" 2>/dev/null \
        | awk '/^sec/ {split($2, a, "/"); print a[2]; exit}' || true)"

    if [[ -n "$existing" ]]; then
        warn "A secret key already exists for $EMAIL: $existing"
        read -rp "  Reuse it? [Y/n] " reply
        [[ "${reply:-Y}" =~ ^[Yy]?$ ]] || die "Aborted. Pass --key-id to choose a different key."
        KEY_ID="$existing"
    else
        info "Generating a $KEY_TYPE signing key for $NAME <$EMAIL>"
        echo "  gpg will prompt for a passphrase. Remember it — it becomes the"
        echo "  GPG_PASSPHRASE secret, and the key is useless without it."
        echo
        gpg --quick-generate-key "$NAME <$EMAIL>" "$KEY_TYPE" sign "$KEY_EXPIRY"

        KEY_ID="$(gpg --list-secret-keys --keyid-format=long "$EMAIL" \
            | awk '/^sec/ {split($2, a, "/"); print a[2]; exit}')"
        [[ -n "$KEY_ID" ]] || die "Key generation appeared to succeed but no key id was found."
        ok "created key $KEY_ID"
    fi
else
    gpg --list-secret-keys "$KEY_ID" >/dev/null 2>&1 || die "No secret key found for id $KEY_ID"
    ok "using existing key $KEY_ID"
fi

FINGERPRINT="$(gpg --fingerprint --with-colons "$KEY_ID" | awk -F: '/^fpr/ {print $10; exit}')"

# ---------------------------------------------------------------- publish

if [[ "$SKIP_PUBLISH" == false ]]; then
    info "Publishing the public key"
    echo "  Central rejects a deployment whose key it cannot find."
    published=false
    for server in "${KEYSERVERS[@]}"; do
        if gpg --keyserver "$server" --send-keys "$KEY_ID" >/dev/null 2>&1; then
            ok "sent to $server"
            published=true
        else
            warn "could not reach $server"
        fi
    done
    $published || warn "No keyserver accepted the key. Retry later; propagation is not instant."
fi

# ---------------------------------------------------------------- secrets

if [[ "$SKIP_SECRETS" == true ]]; then
    info "Skipping GitHub secrets. To set them yourself:"
    cat <<EOF

  gpg --armor --export-secret-keys $KEY_ID | gh secret set GPG_PRIVATE_KEY --repo $REPO
  gh secret set GPG_PASSPHRASE --repo $REPO
EOF
else
    info "Storing GPG_PRIVATE_KEY in $REPO"
    # Piped, so the private key never touches the filesystem.
    gpg --armor --export-secret-keys "$KEY_ID" | gh secret set GPG_PRIVATE_KEY --repo "$REPO"
    ok "GPG_PRIVATE_KEY set"

    info "Storing GPG_PASSPHRASE in $REPO"
    echo "  Enter the same passphrase you gave gpg. It will not be echoed."
    gh secret set GPG_PASSPHRASE --repo "$REPO"
    ok "GPG_PASSPHRASE set"
fi

# ---------------------------------------------------------------- summary

echo
info "Done"
echo "  key id       $KEY_ID"
echo "  fingerprint  $FINGERPRINT"
echo "  expires      $KEY_EXPIRY from today — renew before then or releases start failing"
echo
echo "Still required before a real publish (see RELEASING.md):"
echo "  - CENTRAL_USERNAME / CENTRAL_PASSWORD secrets, from the Central Portal user token"
echo "  - DNS TXT verification of dvarahq.com for the com.dvarahq.oss namespace"
echo
echo "Rehearse signing without touching Central:"
echo "  gh workflow run release.yml --repo $REPO -f version=0.1.0 -f dryRun=true"
