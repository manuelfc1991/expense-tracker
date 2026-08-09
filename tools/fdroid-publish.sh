#!/usr/bin/env bash
#
# Publishes the current release APK into this repository's own F-Droid repo.
#
# Why an F-Droid repo at all, when `release/version.json` already updates the phones:
# the in-app updater is ours to maintain, downloads over plain HTTP(S) with no signature
# check beyond Android's own, and can only ever serve this one household. An F-Droid repo
# is the same idea done by something that is not us — signed index, version history,
# a client that already exists on both phones.
#
# The thing that makes it safe here, and the reason F-Droid's *own* repository was ruled
# out: these APKs are signed with `ours-release.jks`, exactly like the ones the phones
# already run. F-Droid's repository would rebuild from source and sign with F-Droid's key,
# which cannot install over the live data. Ours ships the same binary, so the F-Droid
# client is just another way to receive the update the phones would have taken anyway.
#
#   ./tools/fdroid-publish.sh
#
# Run it from the repository root, after `./gradlew :app:publishRelease`.

set -euo pipefail

# The certificate `ours-release.jks` produces. Pinned rather than merely checked for
# presence, because "signed" is not the property that matters — "signed with the key the
# phones already trust" is. Anything else is an APK whose only route onto a phone is the
# uninstall that destroys the database.
readonly EXPECTED_CERT="c618446939df65fa5ad19a854914a9e7ee397c0a376bf1801c883728f8178ae2"

readonly APK="app/build/outputs/apk/release/app-release.apk"
readonly FDROID_DIR="fdroid"

die() { printf '\n  %s\n\n' "$*" >&2; exit 1; }

[[ -f settings.gradle.kts ]] || die "Run this from the repository root."
[[ -f "$APK" ]] || die "No release APK. Run: ./gradlew :app:publishRelease"

command -v fdroid >/dev/null || die \
  "fdroid is not on PATH. Install it (see README, 'Publish to the F-Droid repo') and retry."

command -v apksigner >/dev/null || die \
  "apksigner is not on PATH. Add \$ANDROID_HOME/build-tools/34.0.0 to it."

# --- the APK is the one the phones can actually take -------------------------------

certs=$(apksigner verify --print-certs "$APK" 2>/dev/null) || die \
  "$APK is not signed at all. keystore.properties or ours-release.jks is missing — the
  build says nothing when they are, it just emits an unsigned APK."

actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")
[[ "$actual" == "$EXPECTED_CERT" ]] || die \
  "Wrong signing key.
     expected $EXPECTED_CERT
     got      ${actual:-<none>}
  Publishing this would offer both phones an update they cannot install."

# --- version, taken from the APK rather than from build.gradle.kts -----------------
#
# The two disagree the moment somebody bumps versionCode without rebuilding, and it is
# the APK that gets published.

code=$(aapt2 dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
name=$(aapt2 dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
[[ -n "$code" ]] || die "Could not read versionCode from $APK"

target="$FDROID_DIR/repo/com.manuel.ours_$code.apk"
if [[ -f "$target" ]]; then
  published_sha=$(sha256sum "$target" | cut -d' ' -f1)
  building_sha=$(sha256sum "$APK" | cut -d' ' -f1)
  if [[ "$published_sha" != "$building_sha" ]]; then
    die "Version $name ($code) is already published, and this APK is a different build.
  Bump versionCode in app/build.gradle.kts — a repo cannot hold two APKs claiming one
  version, and the phones compare versionCode and nothing else."
  fi
  echo "  $target is already published and identical; refreshing the index only."
else
  cp "$APK" "$target"
  echo "  Added $target"
fi

# Has to run inside fdroid/, which is where config.yml and the keystore are found.
# `--pretty` keeps index-v2.json diffable, which matters because it is committed.
#
# Deliberately no `--create-key`: if the keystore is missing, the right outcome is a
# failure. Letting fdroid generate a fresh one would produce a repo that publishes
# perfectly and that every phone already subscribed would reject, because the
# fingerprint they pinned belongs to the key that just got replaced.
log=$(mktemp)
trap 'rm -f "$log"' EXIT
( cd "$FDROID_DIR" && fdroid update --pretty ) 2>&1 | tee "$log" \
  || die "fdroid update failed. If it cannot find a keystore, see docs/HANDOVER.md —
  fdroid/keystore.p12 is gitignored and does not arrive with a clone."

# The fingerprint is not in the index — it is the index-signing certificate's own digest,
# so it is read back out of what fdroid just logged. This is the string a phone checks the
# repo against, and the whole point of handing it over out of band: an attacker who can
# serve a different index cannot also change the fingerprint already on the phone.
fingerprint=$(sed -n 's/^.*INFO: \([0-9A-F][0-9A-F]\( [0-9A-F][0-9A-F]\)\{31\}\)$/\1/p' \
  "$log" | head -1 | tr -d ' ')

cat <<EOF

  Published Ours $name ($code) to the F-Droid repo.

    git add fdroid .nojekyll && git commit && git push

  Once pushed, the phones see it at:

    https://manuelfc1991.github.io/expense-tracker/fdroid/repo

EOF
[[ -n "$fingerprint" ]] && echo "  Repo fingerprint: $fingerprint"
echo
