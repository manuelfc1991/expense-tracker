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
./gradlew :app:testReleaseUnitTest             # 502 tests, all should pass
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

### A balance means one of three things

An account carries a figure, and what that figure *is* decides which total it joins.
There are three answers and they are not interchangeable:

| Kind | The money is | Where it shows | In "safe to spend"? |
|---|---|---|---|
| **Bank account** | available | *What is left* | yes |
| **Put aside** (`isSavings`) | held — an FD, an RD, a PPF | *Put aside* | **no** — owned, not available |
| **Credit card** (`isCard`) | owed | *Owed on cards* | **no** — already spent |

Get the middle one wrong in either direction and the app lies: count a ₹20,000 deposit
in "what is left" and it invites somebody to spend money that is locked up; leave it off
the screen and it denies they own it.

**The exclusion must live in `affordability()`, never only on the panel.** Both callers
hand it the unpartitioned list, so a kind honoured only by the screen is a kind the
safe-to-spend figure ignores. This is not hypothetical — it is exactly how a card balance
was counted as spendable, sign inverted, for a whole release. `PutAsideTest` and
`CardConversionTest` are the same test written twice for that reason.

The kinds are stored as two independent rules (`card`, `savings`) but presented as one
three-way choice, so changing kind must **clear the rule being left**, not merely write
the one being chosen. The panel partitions on `isCard` first, so an account that is
somehow both stays filed as a card and the household's answer is silently discarded.

## Sync

Two transports: `SheetTransport` (Google Apps Script, `sheet-sync/Code.gs`) and
`NearbyTransport` (Bluetooth). Lamport-clock CRDT over a `sync_events` log.

**Anything that is not a transaction travels as a `SharedRuleEntity`** — a synced
key-value store, `(type, ruleKey) → value`, last-write-wins on `updatedAt`. Types:
`account`, `sender`, `merchant`, `balance`, `minbal`, `budget`, `member`, `card`, `owner`,
`savings`. An **emptied value is the tombstone** for all of them.

`savings` is the simplest of them: its value is never read, so the rule's *presence* is
the whole statement. It is written as `"1"` only because blank is how this store says
"removed".

`balance` is the exception worth knowing: its value is `amount|bank|uid`, and an empty
*amount* means "this account exists and nobody has said what is in it" — a real state, not
a tombstone. Only a **wholly** empty value removes the account. `removeAccount` writes the
tombstone to all five account-keyed types at once (`balance`, `minbal`, `card`, `owner`,
`savings`), because `accountBalances()` builds its key set from all of them and clearing
one leaves the account on screen. **Adding an account-keyed type means adding it there
too** — that list is load-bearing, not housekeeping.

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

This household's **accounts**: **Kerala Gramin** (`KGBANK`, main, salary ~₹58,200/month,
a/c ···3062, ₹500 minimum) and **Federal** (`FEDBNK`, ···4657, ₹3,000 minimum). A third,
**SBI**, is the partner's and has no sender on Manuel's phone at all — it exists only as a
hand-typed balance, which is why it is the account that exposed every bug about typed
figures going stale.

Its **cards**, both registered as cards in the app so their balances read as money *owed*
rather than money to spend:

| | | | |
|---|---|---|---|
| **Utkarsh SuperCard** | `UTKSPR` / `UTKSPC` | ···2020 | ₹1,800 limit, bill due 20th |
| **ICICI** | `ICICIT` / `ICICIO` | ···3008 | ₹11,000 limit, bill due 30th |

Deliberately no balances here — those move daily and a stale figure in this file is worse
than none. Read them off the Accounts tab.

The two cards are opposite cases, and the difference is what they send. Counted from the
inbox on 8 August 2026:

- **SuperCard — 185 messages, and every kind of them.** Individual purchases
  ("your SuperCard 2020 debited for INR 152.00 ... for UPI"), bill payments ("We have
  received payment of INR 834.00 for your SuperCard ending 2020"), and monthly statements
  carrying a due date ("Your Jul-2026 statement ... TAD: INR 834.00, MAD: INR 41.70. Pay by
  03 Aug"). Both halves of the money reach the app, so this is the card the double-count
  note on `Category.TRANSFERS` is about — and the risk is **live**: three purchases landed
  on 7 August, after the 1 August floor. Registering it is what defuses that. A bill naming
  a registered card's last four becomes `SELF_TRANSFER` rather than `CARD_PAYMENT`, since
  its purchases were already counted one by one. That happens **at import**, so it protects
  bills read after registration, never ones already stored.
- **ICICI — zero messages. Not few: none at all.** No purchases, no bills, no statements,
  despite `ICICIT`/`ICICIO` being registered senders. Its balance is entirely hand-typed,
  and nothing about it will ever update itself. Two consequences: the double-count
  mechanism can never fire for it, and its bill will only ever be recorded if the household
  types it or pays it from an account that *does* message. Worth watching — if such a
  payment is ever parsed as a card bill naming ···3008, it would be excluded as a
  self-transfer while its purchases were never counted, hiding the money outright. That has
  not happened; there is no ICICI traffic to make it happen.

This is why card-bill exclusion is decided **per card** and never per category.

The rule itself is `TransactionRepository.categoryForKind` — a pure function of the
parsed message and the set of registered cards, pinned by `SettlesTrackedCardTest`. It
lived inline in `importParsed` until 7.5, which meant the one rule the whole double-count
defence rests on was reachable only through Room, a parser and a DAO, and no test had ever
touched it. Three things about it are worth keeping in mind:

- It is decided **at import, once**. Registering a card protects bills read *after*
  registration; ones already stored keep counting until somebody recategorises them.
- Paying a bill never reduces the card's *owed* figure. Nothing subtracts a payment from
  an outstanding balance, and `repairCardBillEchoes` deletes the issuer's acknowledgement
  when a same-amount debit sits within 26h — which is the very message that would have
  carried the new outstanding. The figure moves when the issuer next quotes one, so for
  ICICI, which sends nothing, never.
- Ordering matters. If the issuer's message is imported before the bank's debit, the debit
  is swallowed as a duplicate of a row already filed `CARD_PAYMENT`, and the registered-card
  rule never runs on it — so the bill counts as spending despite the card being registered.

`BankRules` marks ten senders `isCard`, and `adoptKnownCard` files one as a card the first
time it is seen — so a card the parser recognises never lands in "What is left" counting a
debt as spendable. It writes only when *nothing* is recorded for that key, blank included:
a blank rule is the tombstone, and adopting on blank would reinstate a card the household
had deliberately turned back into an account.

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

## The other documents

| | |
|---|---|
| `README.md` | what the app is, and its known gaps |
| `docs/DONE.md` | every release and the commits in it |
| `AT-HOME.md` | what is left that needs the other phone |
| `docs/REVIEW.md` | what the money model gets wrong, and where the tests are not |
| `docs/HANDOVER.md` | picking this up on another machine |
| `design/` | the mockups the screens are built from |
