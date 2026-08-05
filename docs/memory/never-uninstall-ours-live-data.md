---
name: never-uninstall-ours-live-data
description: "The Ours release app on Manuel's phone holds irreplaceable live data — always install with -r, never uninstall."
metadata:
  node_type: memory
  type: feedback
---

`com.manuel.ours` (the signed release build) on Manuel's phone `ZD2224KLJY` holds
real household data. Always update it with `adb install -r`, which preserves the
app's data directory. Never `adb uninstall`, never `pm clear`, and never suggest
"uninstall and reinstall" as a fix.

**Why:** the data is not fully reproducible. SMS backfill can rebuild transactions
from the phone's messages, but manual entries and hand-made category corrections
exist only in that database. The Google Sheet is not a backup either — it held 10
test events when checked on 3 Aug 2026, not the transaction history. Losing the
app data loses work the user did by hand.

**How to apply:** use `adb install -r <apk>` for every update, debug or release. If
something genuinely requires a clean install, stop and ask first, and take a copy
of `databases/`, `shared_prefs/` and `files/` beforehand — noting honestly that
`ours_secure.xml` is Android-Keystore-backed, so an off-device restore may not
work. The old `com.manuel.ours.debug` package was removed on 3 Aug 2026; its data
was backed up to `ExpenseTrackerApp-backup-debug-20260803/debug-data.tar`.

A second, equally destructive route to the same loss: signing a release with a
different key. `adb install -r` then fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
and the only way past it is an uninstall. `ours-release.jks` is gitignored, so a
fresh clone does not have it — see `docs/HANDOVER.md`.

Related: [[household-banks-with-kerala-gramin]]
