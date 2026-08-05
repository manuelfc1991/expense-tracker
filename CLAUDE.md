# Ours — working notes for Claude

Android expense tracker (Kotlin/Compose) that parses Indian bank/UPI SMS and syncs
between the phones in one household. Sideloaded to two real phones with real money in
them. `README.md` is the full description; this file is the operating manual.

---

## The one rule that matters

**Never uninstall the app. Always `adb install -r`.**

`com.manuel.ours` on Manuel's phone holds irreplaceable live data. SMS backfill can
rebuild transactions from the inbox, but **manual entries and hand-made category
corrections exist only in that database**, and the Google Sheet is not a backup — it
carries sync events, not the history.

- ✅ `adb install -r app/build/outputs/apk/release/app-release.apk`
- ❌ `adb uninstall`, `pm clear`, "try uninstalling and reinstalling"

If something genuinely needs a clean install, **stop and ask first**, and take a copy of
`databases/`, `shared_prefs/` and `files/` beforehand — noting honestly that
`ours_secure.xml` is Android-Keystore-backed, so an off-device restore may not work.

## Signing — read before the first release build

Release builds **must** use `ours-release.jks`. Both it and `keystore.properties` are
gitignored and do **not** arrive with a clone. A release signed with any other key
cannot install over what is on the phones: `adb install -r` fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way past that is an uninstall, which
destroys the data above.

If those two files are missing, the build still succeeds and silently produces an
**unsigned** APK. Check before assuming a build is installable.

## The phones

| | |
|---|---|
| Manuel's | `ZD2224KLJY` — Motorola Edge 20 Fusion, household owner |
| Partner's | Android 16 (API 36). App targets SDK 34, which is fine — but check anything permission- or transport-shaped against API 36 before claiming it works on both |

Do not bump `targetSdk` without asking. `READ_SMS` and the notification-listener paths
are the fragile ones.

## Build and release

Run Gradle **from the repository root**. Run it from a subdirectory and it silently does
nothing — which once led to a stale APK being installed and a fix reported as shipped
when it was not.

```bash
./gradlew :app:assembleRelease                 # build
./gradlew :app:testReleaseUnitTest             # ~499 tests, all should pass
./gradlew :app:publishRelease -PreleaseNotes="one line, shown in the update prompt"
```

`publishRelease` copies the signed APK and a manifest into `release/`, both committed,
so **`git push` is the whole release process**. The phones read
`release/version.json` and compare `versionCode`, so bump **both** `versionCode` and
`versionName` in `app/build.gradle.kts` or no update is ever offered.

Verifying what actually landed in an APK beats trusting the build log:

```bash
aapt2 dump resources app/build/outputs/apk/release/app-release.apk | grep -A6 'color/surface'
```

## Design

`design/ours-mockup-v2.html` is the spec; `ui/components/Statement.kt` is that spec in
Kotlin. The governing idea: a bank SMS **is** a printed statement line — fixed pitch,
amount flush right, hairline between entries.

Reach for the existing elements (`Ruler`, `StatementEntry`, `TransactionEntry`,
`LabelOverValue`, `OursChip`, `StatePill`, `TapeHeader`, `AccentButton`, `GhostButton`,
`PrimaryAction`, `Meter`, `QuietEmpty`, `CategoryGrid`) rather than inventing spacing or
type sizes. There is no second design system in the tree.

- Every total, subtotal and headline goes through `Money.whole`. Only the
  transaction-detail screen shows paise.
- Amounts share one right-hand tabular column and carry **no ₹ per row** — the aligned
  column is the unit.
- Later mockups: `design/ours-mockup-v4-filter.html`, `ours-mockup-v5-settings.html`.

## Money model — three ideas that are easy to conflate

| | What it means | Where |
|---|---|---|
| **Spending** | consumed, gone | `totalSpent` — excludes Savings and Ours |
| **Left our accounts** | every debit, savings and self-transfers included | `totalDebited` |
| **Budget** vs **balance** | permission vs capacity — you can spend the smaller | `domain/Affordability.kt` |

The budget is **one cap over one household**: always measured against unfiltered,
household-wide spend, never one member's share. Home, Budgets, the widget and
`BudgetAlerter` must all agree on this.

Unknown is never zero. An account with no recorded balance is counted and reported, not
summed as ₹0 — see `AffordabilityTest`.

## Sync

Two transports: `SheetTransport` (Google Apps Script, `sheet-sync/Code.gs`) and
`NearbyTransport` (Bluetooth). Lamport-clock CRDT over a `sync_events` log.

**Anything that is not a transaction travels as a `SharedRuleEntity`** — a synced
key-value store, `(type, ruleKey) → value`, last-write-wins on `updatedAt`. Types:
`account`, `sender`, `merchant`, `balance`, `minbal`, `budget`, `member`. An **emptied
value is the tombstone** for all of them.

Two things to know:

- Shared rules travel over the **sheet only**, not Bluetooth. `RulesRepository.sync`
  takes a `SheetTransport`.
- A feature that writes a shared rule on *change* does nothing for data that already
  exists. Backfill it (see `publishExistingBudgets`) or it silently never syncs — this
  exact bug shipped twice.

`sheet-sync/Code.gs` is copied into `res/raw` at build time; edit the former.

## When the parser "isn't reading my messages"

Check **sender coverage first**, before suspecting extraction regexes. Sender matching
is the first rule in `SmsParser.parse()`, so an unrecognised TRAI header discards the
message before an amount is ever looked for.

A missing header is never a few stray messages — a household banks with one bank, so it
is that household's entire history. Adding `KGBANK` once took the app from 179 to 429
transactions. `RegionalBankTest` guards this.

This household: **Kerala Gramin** (`KGBANK`, main, salary ~₹58,200/month, a/c ···3062,
₹500 minimum), **Federal** (`FEDBNK`, ···4657, ₹3,000 minimum), **ICICI**
(`ICICIT`/`ICICIO`, ···3008, zero-balance). No SBI sender on Manuel's phone.

Dump headers without reading anyone's messages:

```bash
adb shell content query --uri content://sms/inbox --projection address
```

## Testing on the phone

Screenshots are the ground truth — take one and **look at it**, including zooming in.
Do not report a UI fix as verified from a successful build.

Blind `adb shell input tap` is dangerous in Settings: it has twice changed real
preferences by accident (tracking-start date, developer mode). Screenshot, locate the
control, then tap. Navigating tabs is safe; toggling is not.

An emulator can stand in for the partner's phone, and is the only way to catch
sync bugs that are invisible from one device:

```bash
emulator -avd <name> -no-window -gpu swiftshader_indirect -no-snapshot
```

## What does not arrive with a clone

`ours-release.jks`, `keystore.properties`, `local.properties`. See
`docs/HANDOVER.md`.
