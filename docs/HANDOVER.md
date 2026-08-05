# Moving to another machine

A clone gets you the code, the design specs, the published APK and the working notes in
`CLAUDE.md`. Three things it deliberately does not get you, and one of them will destroy
live data if you get it wrong.

---

## 1. The signing key — copy this by hand, not through git

**Files:** `ours-release.jks` and `keystore.properties`, both in the repository root,
both gitignored.

**Why it matters more than anything else here.** Android identifies an app by its
signature. Build a release on the new machine with a *different* key and it is a
different app as far as the phones are concerned:

```
adb install -r ...  →  INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

The only way past that error is `adb uninstall`, which erases the household's data —
manual entries and hand-made category corrections that exist in no backup. The sheet is
a sync log, not a history.

There is also a quiet version of this failure: **without `keystore.properties` the build
still succeeds and produces an unsigned APK.** Nothing says so. You find out when the
install fails, or worse, when you publish an unsigned build the phones then refuse.

**Move them over a channel you trust** — a USB stick, `scp` between the two machines, or
a password manager's file attachment. Not email, not chat, and not by committing them
"just for the transfer": a signing key in git history stays in git history.

```bash
scp ours-release.jks keystore.properties newmachine:~/Manuel/ExpenseTrackerApp/
```

**Verify before you trust it.** After the first release build on the new machine:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
adb -s <device> shell dumpsys package com.manuel.ours | grep -A2 signatures
```

The SHA-256 must match what is already on the phone. If you ever do lose the key, say so
rather than working around it — the honest options are narrow and all of them involve the
household reinstalling, so the data has to be exported first.

## 2. `local.properties`

One line pointing at the Android SDK. Recreate it — the path will be different on the new
machine anyway:

```properties
sdk.dir=/home/<you>/Android/Sdk
```

## 3. The Apps Script URL

Not in the repository, and it should stay that way — it is the only credential protecting
the household's data, and anyone holding it can read and change every expense.

It already lives on both phones, so **nothing needs to be moved for sync to keep
working.** You only need it again if you reinstall from scratch or add a device:
**Settings ▸ Sync ▸ Sheet sync** on either phone, or the Sheet itself via
**Extensions ▸ Apps Script ▸ Deploy ▸ Manage deployments**.

---

## Restoring the working notes

`CLAUDE.md` at the repository root is read automatically — nothing to do.

`docs/memory/` is a copy of Claude's own memory files, which live outside the repository
and so do not travel. To put them back on the new machine:

```bash
DEST=~/.claude/projects/$(pwd | tr '/' '-')/memory
mkdir -p "$DEST" && cp docs/memory/*.md "$DEST"/
```

The directory name is the project's absolute path with `/` replaced by `-`, so run that
from inside the repository on the new machine and it lands in the right place. If the
repository sits at a different path there, the name changes accordingly — which the
command above handles by computing it rather than hard-coding it.

Keep `docs/memory/` and the live memory directory in step by hand; nothing syncs them.

---

## First run on the new machine

```bash
git clone git@github.com:manuelfc1991/expense-tracker.git
cd expense-tracker
# copy ours-release.jks + keystore.properties in, write local.properties
./gradlew :app:testReleaseUnitTest        # ~499 tests, all should pass
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

If the tests pass and the certificate matches the phone, everything else in `CLAUDE.md`
applies unchanged.
