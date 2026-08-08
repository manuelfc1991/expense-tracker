# Ours

Android expense tracker that reads Indian bank/UPI SMS, produces monthly summaries,
and syncs between the phones in a household — two, three, or more.

No account and no server of ours. Two sync paths, either or both:

| Path | Setup | Works when | Encrypted |
|---|---|---|---|
| **Google Sheet** | Paste an Apps Script URL on both phones | Anywhere | No — plaintext by design |
| **Bluetooth** (Nearby) | Grant Bluetooth once, on both phones | The two phones are close | Yes |

The Sheet path exists because a spreadsheet is **readable and repairable** — when
something looks wrong you can open it and see. That comes at a real cost, stated
plainly: the script URL is the only credential, and the sheet holds your data
in plain text — though the original bank messages are stripped before upload. The
sheet itself stays private; only the script URL is shared.

Setup is walked through inside the app — **Settings ▸ Sheet sync ▸ How do I set this
up?** — which also carries the script itself, copyable and shareable, so the second
phone never depends on having this repository to hand. The script has one source,
`sheet-sync/Code.gs`; Gradle copies it into the app at build time rather than keeping a
second copy that could drift into speaking a different wire format.

Neither path needs a Cloud Console project, OAuth client, API key or SHA-1
fingerprint. Bluetooth is encrypted end to end and involves no third party at all.

```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testReleaseUnitTest # 502 tests
```

Kotlin · Jetpack Compose · Material 3 · Room · Hilt · WorkManager · minSdk 26

Everything below the tutorials is *why* the app is built the way it is. If you only
want to get it running, the next section is the whole job.

**Tutorials** — [build from a clone](#1-build-it-from-a-fresh-clone) ·
[first phone](#2-set-it-up-on-the-first-phone) ·
[install over real data](#3-install-on-a-phone-that-already-has-real-data) ·
[cut a release](#4-cut-a-release-the-phones-will-accept) ·
[second phone](#5-put-a-second-phone-in-the-household) ·
[accounts, cards, deposits](#6-record-an-account-a-card-or-money-put-aside) ·
[read a month](#7-read-a-month-honestly) ·
[parser misses messages](#8-when-it-isnt-reading-my-messages) ·
[backup and restore](#9-back-up-and-prove-the-restore-works) ·
[verify on the phone](#10-verify-a-ui-change-on-the-phone) ·
[troubleshooting](#11-troubleshooting)

**Reference** — [how sync works](#how-sync-works) ·
[what else travels](#what-else-travels-between-phones) ·
[deleting](#deleting-needs-the-owner) · [updating itself](#updating-itself) ·
[what counts as spending](#what-counts-as-spending) ·
[recurring charges](#recurring-charges) · [the SMS parser](#the-sms-parser) ·
[what works and what needs you](#what-works-and-what-still-needs-you) ·
[design](#design) · [tests](#tests)

The other documents: `CLAUDE.md` is the operating manual, `docs/DONE.md` every release
and the commits in it, `AT-HOME.md` what is left that needs the second phone,
`docs/REVIEW.md` what the money model gets wrong, `docs/HANDOVER.md` picking this up on
another machine.

---

# Tutorials

Each one is a complete job, in order, with the command you actually run. They assume a
Linux or macOS shell, the Android SDK on your `PATH`, and that you run **Gradle from the
repository root** — run it from a subdirectory and it silently does nothing, which once
led to a stale APK being installed and a fix being reported as shipped when it was not.

## 1. Build it from a fresh clone

```bash
git clone git@github.com:manuelfc1991/expense-tracker.git ours
cd ours
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

That is enough for a debug build on any phone or emulator. **Three files do not arrive
with a clone**, because they are gitignored:

| File | What it is | Without it |
|---|---|---|
| `local.properties` | your Android SDK path | Gradle cannot find the SDK; some setups fall back to `ANDROID_HOME` |
| `ours-release.jks` | the release signing key | release builds are produced **unsigned**, silently |
| `keystore.properties` | that key's passwords | same |

The signing pair matters more than it looks — see tutorial 4 before your first release
build. `docs/HANDOVER.md` covers picking the project up on another machine.

## 2. Set it up on the first phone

Install, open, and the app walks you through it. What it is actually doing:

1. **Grant SMS access** — or switch **Settings ▸ Read from** to **Notifications**
   instead (the third option, **Manual**, reads nothing). Same information, no restricted
   permission, and the only option if you ever want this on the Play Store.
2. **Backfill** — it reads the existing inbox and builds the history. This is the step
   where a missing bank header costs you everything that bank ever sent; if the count
   looks far too low, go to tutorial 8.
3. **Set a tracking start date** if you do not want the whole inbox. Retiring a month
   hides it *and stops it syncing* — it is a household-wide decision, not a view filter.
4. **Record your accounts** — tutorial 6.

## 3. Install on a phone that already has real data

**Never uninstall. Always `adb install -r`.**

```bash
adb devices -l                                              # find the phone
adb install -r app/build/outputs/apk/release/app-release.apk
```

The database holds things nothing can rebuild. A rescan recovers what the banks sent;
**manual entries and hand-made category corrections exist only on that phone**, and the
Google Sheet is not a backup — it carries sync events, not the history.

```bash
adb uninstall com.manuel.ours   # ❌ destroys it
adb shell pm clear com.manuel.ours   # ❌ same
```

If a clean install is genuinely unavoidable, take a copy of `databases/`, `shared_prefs/`
and `files/` first — noting honestly that `ours_secure.xml` is Android-Keystore-backed, so
an off-device restore of it may not work.

Confirm an install actually replaced what was there, rather than reinstalling it:

```bash
adb shell dumpsys package com.manuel.ours | grep -E "versionCode|firstInstallTime"
```

An unchanged `firstInstallTime` is the proof that the data survived.

## 4. Cut a release the phones will accept

Release builds **must** use `ours-release.jks`. A release signed with any other key
cannot install over what is on the phones — `adb install -r` fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way past that is an uninstall, which
destroys the data above.

```bash
# 1. bump BOTH, in app/build.gradle.kts — versionCode is what phones compare
#       versionCode = 78
#       versionName = "7.6"

# 2. prove it
./gradlew :app:testReleaseUnitTest

# 3. build, copy the signed APK and manifest into release/
./gradlew :app:publishRelease -PreleaseNotes="one line, shown in the update prompt"

# 4. verify it is signed at all — an unsigned APK builds successfully
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# 5. this is the whole release process
git push
```

`release/` is committed, the phones read `release/version.json`, and they compare
`versionCode`. Forget to bump it and **no update is ever offered**, however new the APK.

To check what really landed in a build rather than trusting the log:

```bash
aapt2 dump resources app/build/outputs/apk/release/app-release.apk | grep -A6 'color/surface'
```

## 5. Put a second phone in the household

Sheet sync carries everything; Bluetooth carries transactions only. Do the sheet first.

**On the sheet, once per household:**

1. Open **Settings ▸ Sheet sync ▸ How do I set this up?** — the script is in there,
   copyable, so the second phone never needs this repository.
2. Paste it into a new Apps Script project bound to a new spreadsheet, deploy as a **web
   app**, access **anyone with the link**, and copy the `/exec` URL.
3. Paste that URL into both phones.

**Then join the second phone:** on the first, **Settings ▸ Household ▸ Add someone
else**; on the second, scan the QR or type the six-letter code. **Settings ▸ Sync now**
on each, the second phone first, and the two should agree on spent-this-month, the
budget, and Summary ▸ Where it went.

Two things that surprise people, both deliberate:

- **A partner sees no account balances and no "safe to spend"** unless they own
  accounts. Balances belong to the household owner.
- **Retired months never sync at all**, so a partner who joins later gets only what is
  in scope. Moving the date back does not retroactively push what it readmits — use
  **Re-upload everything** for that.

The script lives at `sheet-sync/Code.gs` and Gradle copies it into the app at build
time. **Edit that file, never the copy in `res/raw`.**

## 6. Record an account, a card, or money put aside

**Summary ▸ Accounts ▸ Add an account**, or tap any account already listed to change it.
The first question is *what kind*, and it is the one that matters, because it decides
which total the figure joins:

| Choose | For | It shows under | Counted as spendable? |
|---|---|---|---|
| **Bank account** | a current or savings account | *What is left* | yes |
| **Credit card** | anything whose balance is a debt | *Owed on cards* | no — already spent |
| **Put aside** | an FD, an RD, a PPF | *Put aside* | no — owned, not available |

Marking a card as a card does two jobs. It stops a debt being counted as money you can
spend — and it defuses the double count, because once the app records a card's purchases
one by one, a bill naming that card's last four is filed as moving your own money rather
than as fresh spending.

The kind is changeable afterwards, which matters because accounts the *parser* discovers
arrive as bank accounts by default.

> An account with **no** recorded balance is counted and reported as unknown, never
> summed as ₹0. Type `0` for an account that really is empty — that is a figure, and it
> sticks. Emptying the field instead hands the account back to whatever the bank last
> said.

## 7. Read a month honestly

Three quantities are easy to conflate, and the app keeps them apart on purpose:

- **Spending** — consumed, gone. Excludes savings and money moved between your own
  accounts.
- **Left our accounts** — every debit, including deposits and self-transfers.
- **Budget vs balance** — permission versus capacity. **You can spend the smaller of the
  two**, and the Accounts tab says which one is binding.

The budget is **one cap over one household**, always measured against unfiltered,
household-wide spending — never one member's share. Filtering to "Me" changes what you
are looking at, not what the budget is measured against.

## 8. When it "isn't reading my messages"

**Check sender coverage first, before suspecting the extraction regexes.** Sender
matching is the first rule in `SmsParser.parse()`, so an unrecognised TRAI header
discards the message before an amount is ever looked for — silently.

This is never a few stray messages. A household banks with one bank, so a missing header
is that household's entire history: adding `KGBANK` once took a real phone from 179 to
429 transactions.

```bash
# dump the headers without reading anyone's message text
adb shell content query --uri content://sms/inbox --projection address
```

Compare what comes back against `BankRules.forSender`. If a header is missing, add it
there — then **Settings ▸ Rescan messages**, which never duplicates.

## 9. Back up, and prove the restore works

**Settings ▸ This app ▸ Backup & restore ▸ Back up everything** writes the whole history
to one JSON file and hands it to the share sheet.

Test the restore **on an emulator, not on the phone that holds the real money** — the
point is to make the first insert happen somewhere a wrong answer costs nothing:

```bash
emulator -avd <name> -no-window -gpu swiftshader_indirect -no-snapshot
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 push ours-backup-2026-08-06-1104.json /sdcard/Download/
```

Onboard the emulator as its **own** household — do not join your real one, or you leave a
member behind that has to be tombstoned in the sheet afterwards. Then restore, and check
in this order:

1. The confirmation names the date, the build and the counts **before** anything is written.
2. It reports what it restored, and the rows appear in Activity with their categories.
3. **Run it a second time.** It must say everything was already there and change nothing.
   That is the property the whole design rests on, and the one worth seeing fail.
4. Point it at a JPEG. It should say *"That file could not be read — this is not an Ours
   backup"* and nothing more.

If step 3 duplicates the history, stop: that is the one outcome that makes the feature
worse than not having it.

## 10. Verify a UI change on the phone

A green build is not verification. Take a screenshot and **look at it**, zoomed in.

```bash
adb shell monkey -p com.manuel.ours -c android.intent.category.LAUNCHER 1
adb exec-out screencap -p > shot.png
```

This is not ceremony. A three-chip row shipped with its third chip off the right-hand
edge of a scrolling row that gives no sign it scrolls — tests green, build clean, and the
release's only new option invisible on the device.

**Blind `adb shell input tap` is dangerous in Settings.** It has twice changed real
preferences by accident, including the tracking start date. Screenshot, locate the
control, *then* tap. Navigating tabs is safe; toggling is not.

## 11. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | APK signed with a different key | build with `ours-release.jks`; do **not** uninstall to get past it |
| Release APK will not install anywhere | it was built unsigned | `keystore.properties` / `ours-release.jks` missing — tutorial 1 |
| Phones never offer an update | `versionCode` not bumped, or `release/` not pushed | tutorial 4 |
| Gradle "succeeds" but nothing changes | run from a subdirectory | run from the repository root |
| Far too few transactions | unrecognised sender header | tutorial 8 |
| A balance is stale | typed figures do not update themselves | retype it, or register the account with a bank that messages |
| Sheet sync says `Unknown action: pullRules` | the sheet runs an older script | redeploy `sheet-sync/Code.gs` |
| Re-upload "Queued 0" | everything is before the tracking start date | move the date, or accept it |
| Sync now reports *Sheet* when you expected Bluetooth | shared rules never travel over Bluetooth | expected — only transactions do |

---

# Reference

Why the app is built this way. Every section below is a decision that cost something to
get wrong.

## How sync works

Every device writes **only to its own append-only log**. No two devices ever write the
same file, so there is nothing to lock and no file-level conflict to resolve. All that
remains is picking a winner per transaction:

```
winner = max by (lamport, deviceId)
```

**Lamport clock, not wall-clock.** Two phones' clocks drift by minutes. A logical
counter (`local = max(local, maxSeen) + 1` on every merge) gives a total order both
phones agree on without trusting either clock. Wall-clock is carried for display only.

Because that rule is a pure function of the event set, both phones converge to
identical state regardless of arrival order — which is what `MergeConvergenceTest`
exercises: shuffled delivery, duplicate delivery, partial-then-catch-up, both phones
editing the same row offline, deletes racing edits.

Proven on real hardware, not only in tests: two phones in one household converged on a
460-event ledger spanning six months, written by one and pulled whole by the other
through a live Sheet deployment.

```
SMS / manual entry
      ↓
   Room (single source of truth) ──► UI reads only from Room
      ↓  append SyncEvent to my own log
   ├── Google Sheet (Apps Script), from anywhere
   └── Nearby Connections (Bluetooth), when the phones are close
      ↓
   merge peer's log → upsert Room → UI updates
```

Transports sit behind one `SyncTransport` interface and move opaque blobs, so the
merge logic never knew Drive existed and did not change when it was removed. Every log
line is separately AES-256-GCM encrypted with a key derived (HKDF-SHA256) from the
invite secret. Per-line rather than whole-file encryption means one corrupt or
truncated line costs you that event, not the file.

**Raw SMS bodies *are* synced, and that is a deliberate trade — not an oversight.**
`SyncPayload.rawSms` carries the original message so a mis-parse can be diagnosed on
either phone, and `CryptoAndCodecTest` asserts the field is present rather than absent.

What it costs depends entirely on the transport:

- **Bluetooth** — every log line is separately AES-256-GCM encrypted,
  so the message text never appears in the clear. `CryptoAndCodecTest` pins this by
  encoding a real debit alert and asserting the account tail and balance cannot be found
  in the ciphertext.
- **Google Sheet** — plaintext, so the message is **stripped before it is sent**.
  `SheetTransport.redactForSheet` nulls `rawSms` on the way out and the ledger has no
  `Original message` column. Two tests pin it: one asserts the text and the balance
  cannot be found anywhere in the redacted payload, the other asserts the caller's
  events are left untouched, since the encrypted transports push the same list and must
  keep carrying it.

A sheet you recreate or clear by hand needs **Settings ▸ Sheet sync ▸ Re-upload
everything**. "Pushed" only ever meant "pushed to whatever sheet was there at the time",
and nothing on the phone can tell that the spreadsheet behind an unchanged URL is a
different one — so it is a button rather than a guess.

Everything else still goes to the sheet in the clear — amounts, merchants, account
tails, who paid. That is inherent: a ledger you can open and repair is a ledger anyone
holding the `/exec` URL can read. Only the bank's raw text is held back, because it is
the one field that adds running balances and full account context on top.

---

## What else travels between phones

Two kinds of knowledge ride alongside the ledger, in a `rules` tab of the same sheet,
three meaningful columns and editable by hand:

```
type      | key       | value
sender    | KGBANK    | Kerala Gramin Bank
merchant  | keecheril | FOOD
```

Sender rules are the ones that have cost real money. An unrecognised TRAI header is
discarded *before* parsing begins, silently — one missing line threw away 466 messages
on the first real phone, and the fix was a single string that nonetheless needed a new
build on every device. Merchant rules ride along so a correction made on one phone is
not made again on the other forever.

The boundary is deliberate: **the parser's patterns stay code.** A regex that reads
"credited to a/c no." as a payee cannot be repaired from a spreadsheet and should not
be, because a bad pattern from a shared sheet would break parsing everywhere at once.
What travels is data a person could reasonably type.

Rules degrade to doing nothing against a sheet running an older script, which answers
`Unknown action: pullRules`. The ledger is the job; rules are a bonus and must never
take a sync down with them.

---

## Deleting needs the owner

A delete is the one change nobody can inspect afterwards. An edit leaves a value to
disagree with; a deletion leaves nothing at all. In a shared ledger that makes it the
one action worth a second pair of eyes.

A member's delete becomes a **request**, and the row stays visible *and counted* until
the household owner answers — a request is not a decision, and no total should move on
the strength of one. The owner sees who asked and what for, and either deletes it or
keeps it. Their own delete is immediate; asking yourself for permission is theatre.

This is the only authority in the app. The merge rule is otherwise deliberately
symmetric — last writer wins, no privileged device — so ownership is recorded once,
when the household is created, and does nothing else. A creator who reinstalls and
joins by code becomes a member, which is a real limitation rather than something the
app can detect.

---

## Updating itself

Not on Play, so it updates from this repository. `./gradlew publishRelease` writes the
signed APK and a small manifest into `release/`; `git push` is the rest of the release
process. Phones compare `versionCode` against their own and offer the download only
when something is genuinely newer.

Three limits on a feature that downloads and opens an executable:

- The downloaded APK's **signing certificate is checked against the running app's**
  before the installer is offered. Android would refuse a mismatch anyway, but only
  after a 25 MB download and with a message that explains nothing.
- The manifest URL is **hard-coded**. A configurable one is a setting that lets anyone
  who reaches the phone point it at their own build.
- **Nothing is silent.** Check when asked, download when asked, and the install is
  Android's own installer showing what is about to happen.

`apkUrl` is data in the manifest rather than code in the app, so where updates come
from can change without shipping a build to change it.

**Developer mode** hides in Settings ▸ About: seven taps on the version, then one on
the household code — two targets in order, because a single repeated tap is something a
thumb does by accident. Owner only. It unlocks editing an amount, which is the one
field worth locking: a category is the app's guess and a payee is often missing
entirely, but the figure came from the bank. Overwriting it is irreversible, so an
edited row is stamped with the date it was changed and says so under the amount. A row
that quietly disagrees with the bank is worse than one that explains itself.

---

## What counts as spending

Only debits, and not all of them. The distinction is the difference between a month
that reads honestly and one that flatters or alarms.

| | Counts | Why |
|---|---|---|
| Ordinary purchases | yes | money gone |
| **Transfers** | **yes** | an unnamed debit is overwhelmingly money sent to someone else |
| **Card bills** | **yes** | on this household not one of 460 rows was an individual card purchase, so the bill is the only record of that money |
| Savings and deposits | no | an FD is still yours afterwards |
| **Between our accounts** | no | it never left the household |

Transfers and card bills used to be excluded as "money moved, not spent". Both were
wrong here. A card bill is only a double count if the purchases inside it arrived
separately as messages, and they never do — excluding it hid ₹56,461. Transfers were
83 unnamed payees, an IMPS charge and an ATM fee: all of it gone.

**Own-account transfers are the exception, and they are identified by the pair.** A
debit carrying one account tail and a credit carrying another, for the same amount
within half an hour, is a round trip. The tail is the evidence: this phone only
receives alerts for accounts the household owns, so a stored tail is always one of
theirs. Both legs are marked neutral rather than deleted, because they are real
messages about real movements — what a household should not see is the month claiming
it spent the money.

**A balance answers one of three questions, not two.** Money in an account is
*available*; money on a card is *owed*; money in a fixed deposit is *held* — owned, and
not there to spend this month. Collapsing the third into either of the others makes the
app lie in a specific direction: count a ₹20,000 deposit as spendable and it invites
somebody to spend money that is locked up, leave it off the screen and it denies they own
it. So *What is left*, *Put aside* and *Owed on cards* are three totals, and only the
first is capacity.

The exclusion lives in `affordability()` rather than only in the panel that draws it,
which sounds like an implementation detail and is not: both callers hand that function
the unpartitioned list, so a distinction honoured only by the screen is a distinction the
safe-to-spend figure ignores. That is exactly how a card balance was once added to
spendable money with its sign inverted.

A maturing deposit is a case the parser cannot win. `Rs.10000 credited to your A/c
XXXX. BAL-...` says nothing about a deposit, and is indistinguishable from a salary.
Recategorising it to Savings & Investments removes it from the month's income, and the
app deliberately learns no rule from that — bare credits are labelled with the bank's
name, so learning would file every future credit from that bank as savings.

---

## Recurring charges

Nothing declares a subscription — no bank SMS says "this is one". So `RecurringDetector`
infers it from repetition: the same payee, for about the same amount, at about the same
interval, at least three times.

Three is the floor because two of anything is a coincidence. Cadences are weekly,
monthly, quarterly and yearly, each with its own tolerance — monthly needs the widest,
since calendar months are 28–31 days. A gap of roughly twice the period is read as one
missed sighting rather than a broken pattern, so a single unparsed message does not hide
a subscription that has run for two years.

**Amount consistency is the guard that matters.** Weekly groceries at one shop repeat as
regularly as Netflix does, but for wildly different amounts — and they are not a
commitment you could cancel. Requiring every amount within 25% of the median is what
separates a subscription from a habit; the tolerance is wide enough to survive a utility
bill's seasonal swing and the odd price rise. The figure shown is the median, so one
price rise does not drag it to something never actually charged.

The bias is deliberately conservative. A false positive is worse than a miss: claiming a
₹4,000 monthly commitment that does not exist makes every other number in the app
suspect, while missing one subscription costs the reader nothing they had before.

Summary shows them under **Committed**, ranked by monthly equivalent so cadences can be
read against one another — a ₹1,200 quarterly charge and a ₹400 monthly one are the same
commitment. Detection needs history, so a household that has just set a tracking start
date will see nothing until a few months have accrued.

---

## The SMS parser

`SmsParser` is rule-driven over ~40 sender families — the big private banks, the public
sector ones, the regional and small-finance banks (Kerala Gramin, Utkarsh, Karnataka,
South Indian, Indian, Central, IOB, UCO, Bandhan, AU, RBL, Bank of Maharashtra), the
wallets and the card issuers.

**Sender coverage is the failure mode that actually bites.** The sender check is the
first rule in `parse()`, so a bank whose header is missing is not mis-parsed — it is
never read. Shipping without one regional bank's header meant several hundred messages on the
first real phone were discarded silently; adding one line more than doubled the
transactions that phone held. When
something "isn't being tracked", dump the phone's sender headers and check them against
`BankRules.forSender` before suspecting a regex.

Reject rules run **before** any extraction, because an OTP and a debit alert both
contain "Rs." and a number:

| Rejected | Why it matters |
|---|---|
| OTPs | `OTP for txn of Rs.4,821 at AMAZON is 998877` would otherwise log ₹4,821 |
| Failed / declined / reversed | No money moved |
| Promotional | Pre-approved loan offers quote large amounts |
| Balance-only | `Avl Bal Rs.45,200` is not a transaction |
| Personal numbers | Only 6-char TRAI sender IDs are accepted |
| Bill reminders | Stored separately — money owed, not spent |

Two bugs the test suite caught, both worth knowing about:

- **`credited` parsed as a crore suffix.** The amount regex allowed an optional
  `K`/`L`/`Cr` multiplier, so `Rs.85,000.00 credited` matched the `cr` of *credited*
  and reported ₹85,000 as ₹85,00,00,00,000. Fixed with a `(?![a-z])` lookahead.
- **Merchants swallowing the rest of the sentence.** `at HOTSTAR using Amazon Pay
  balance` yielded that whole string as the merchant. Banks append clauses far more
  often than they punctuate, so the terminator list is explicit.

**Bare credits carry their bank's name.** Banks routinely credit an account without
saying who sent the money — a Gramin bank salary alert is literally `Your A/c
XXXXnnnn credited Rs.NN,NNN Bal after txn ...` with no payer anywhere in it. Labelling
that row "Unknown payee" told the reader nothing they did not already know, so it now
reads "Kerala Gramin Bank". The label is a fallback, not a merchant, so `recategorize`
refuses to learn a rule from it — otherwise sorting one salary would teach the app that
everything that bank ever credits is salary. Debits keep the placeholder: for money
going out the payee is the whole question, and answering it with your own bank's name
would be misleading rather than merely unhelpful.

UPI payments generate two SMS (bank + UPI app). `SmsDeduplicator` collapses them on
`(amount, ±3 min, account tail or UPI ref)` and keeps whichever record names a
merchant.

Amounts are `Long` paise everywhere. Never `Double` — a test sums 10,000 values to
prove no drift.

---

## What works, and what still needs you

**Working end to end:** SMS parsing and backfill, live SMS receiver, notification
listener as an alternative source, duplicate collapsing across the several messages a
bank sends for one payment, categorization with learned user corrections,
editable auto-assign rules, bulk sorting by merchant, manual entry, accounts recorded as
one of three kinds — bank, credit card, or money put aside — transactions with
search/filter/grouping, swipe-to-delete with undo and multi-select for acting on many
rows at once, renaming a payee the bank never named, recurring-charge detection,
monthly summary, budgets, a tracking start date that retires older months without
deleting them, CSV + PDF export, whole-history backup and restore, a home-screen widget
that redraws when the numbers move, theming, QR invite generation and scanning, and the
sync core with its convergence tests.

**Verified on two phones.** Everything above was exercised against a real inbox, and
the Sheet transport has now carried a household: one phone wrote 460 events spanning
six months and the other pulled the lot. Bluetooth is the transport still untested
against a second device — see the gaps below.

**On-device security**

The Room database is encrypted with SQLCipher, keyed by 32 random bytes held in
`EncryptedSharedPreferences` whose master key lives in the Android Keystore. Verified,
not assumed — the file header reads as noise and `sqlite3` refuses to open it. Optional
app lock uses `BiometricPrompt` with `DEVICE_CREDENTIAL` as a fallback, because a lock
you can get permanently stuck behind is worse than no lock.

**Backup**

Settings ▸ This app ▸ Backup & restore writes the whole history to one JSON file and
hands it to the share sheet: every transaction including tombstones, the shared rules,
the payee rules a person made, budgets, members and reminders. It is the only answer in
the app to a lost handset — a rescan rebuilds what the banks sent, and nothing rebuilds
what somebody typed or corrected.

Restoring **only ever adds.** Nothing already on the phone is deleted, a deletion made
here is never undone, and rows are matched on `dedupeKey` rather than on id, because the
realistic restore is onto a phone that has already re-read the inbox and holds the same
transactions under fresh ids. A matched row keeps its local id and its parser-derived
fields and takes the backup's human ones — the renamed payee, the corrected category, a
hand-edited amount. Running the same restore twice is a no-op and says so.

Two things it deliberately does not carry: the invite secret and the sheet URL, which are
capability credentials and would otherwise sit in a file destined for Drive; and
`sync_events`, which is rebuildable and would replay an ordering the household has moved
past. It is **not encrypted** and it does include the original bank messages, account
tails and balances among them — a passphrase that can be forgotten turns a safety net
into a second way to lose everything, so the file says what it is instead.

**Known gaps, honestly:**

1. **No restore has yet written a row on a handset.** Everything up to the write has
   been run on Manuel's phone: the backup (5.14 wrote `ours-backup-2026-08-06-1104.json`
   and the share sheet took it), the picker, the confirmation dialog naming the file's
   date and counts, a file written by a newer build being refused by version, a file
   that is not a backup being refused by name, and a restore carried through to its
   report. The one thing deliberately not tried there is a restore that *inserts*, since
   the only copy of six months of spending is not where a write path should run first.

   Three defects came out of that session and none were visible from the tests:
   a rejection that pasted the parser's message *and a quotation of the chosen file*
   into the interface, a missing full stop that ran two sentences together, and an
   invitation to sync a restore that had changed nothing. All three are fixed and pinned
   by tests now — which is the argument for the emulator run rather than against it.
   `AT-HOME.md` step 7 is how to do it.
2. **Bluetooth sync is unproven against a second device.** Its runtime permissions were
   declared in the manifest but never requested until recently, so every entry point
   silently reported "no peers" — the toggle looked on and did nothing. The request now
   happens when you enable it, and the preconditions check out on both phones, but no
   two devices have ever completed a Nearby handshake. Emulators get as far as
   `requestConnection` and then fail on the virtual radio.
3. **Three or more people is supported and tested, but not on three real phones.** The
   filter, the split bar, the merge and both transports take an arbitrary number of
   members; `AggregationTest` covers a household of three, and a four-member household
   has been exercised across one phone and three emulators. Two real handsets is the
   most that has ever run at once.
4. **A retired month is retired for the household, not just for you.** The tracking
   start date bounds what *syncs* as well as what is drawn, so months before it never
   reach the other phone. That is deliberate, and it used to be the single most
   confusing thing in the app, because nothing said so: "Re-upload everything" honoured
   the cutoff in silence and reported success having queued a fraction of the history.

   It now says what it withholds. The button reads "Re-upload everything **in scope**"
   once anything is retired, a warning above it counts what will not be sent and dates
   it, and the result names both halves — *"Queued 214 expenses. 246 from before
   1 Feb 2026 stayed behind — those months are retired."* A cutoff later than every
   stored row no longer reports "Queued 0", which read as a broken sheet rather than as
   a date the household chose. `ReuploadScopeTest` covers the wording.

   What remains, and is not a bug: a partner who joins later still receives only what is
   in scope, and moving the date back does not retroactively push the months it
   readmits — a re-upload does that.

---

## Design

`design/ours-mockup-v2.html` is the spec, and `ui/components/Statement.kt` is that spec
in Kotlin. Every screen is arrangement of those elements rather than fresh invention,
which is what keeps them looking like one product. There is no second design system in
the tree: the v1 cards, donut and bar charts were deleted rather than left to rot.

The governing idea: a bank message is a machine-printed line — fixed pitch, amount
flush right, hairline between entries — so the interface is built from that material
instead of dressed up to hide it. Concretely, every amount shares one right-hand column
with tabular figures, and there is **no ₹ on any row**, because once the column is
aligned the column itself is the unit.

That column is also why every total, subtotal and headline is whole rupees
(`Money.whole`). Paise on a month total are two digits of noise; paise on *some* rows of
a column and not others is worse, because the decimal points stop lining up and the
column stops being a column. The exact figure lives on the entry's own screen, which is
where you go to reconcile against a bank statement.

**Category colour** is the one part that is generated rather than drawn. Five hues were
signed off in the mockup; the other seven were placed to sit in the same measured band —
even hue spacing, never closer than 25°, lightness and chroma held inside the range the
approved five occupy, and hue preserved across themes so a category keeps its identity
when you switch. Light is not an inversion of dark: it is re-stepped against paper,
because the dark greens and ambers fail contrast on white.

Stated plainly rather than buried: with twelve categories inside one lightness band,
some pairs collapse under colour-blind simulation — Food and Education are close to
identical to a deuteranope. Widening the band does not fix it, because the worst pair is
Food and Bills, both approved and only 29° apart. Colour is therefore never the sole
carrier: every bar, chip and row prints its name and amount beside it.

**Play Store:** `READ_SMS` is a restricted permission that Google rejects for expense
trackers. Sideload to your two phones, or switch the source to **Notifications** in
Settings — same information, no restricted permission.

---

## Tests

```
SmsParserTest              47   bank shapes, OTP/promo/failed rejection, edge cases
SmsParserRealWorldTest     19   real messages, kept as a regression corpus
AggregationTest            17   month math, member filtering, insights
DueBillsTest               17   when to interrupt somebody about a bill, and when not to
SenderDiscoveryTest        17   reading a bank the compiled table has never heard of
CryptoAndCodecTest         15   key derivation, tamper detection, corrupt-line recovery
RecurringDetectorTest      15   what repeats, and what only looks like it
BackupMergeTest            14   what restoring a backup does to rows already here
MergeConvergenceTest       14   sync convergence under adversarial ordering
CategoryPredictorTest      12   one-tap category guesses
MoneyTest                  12   lakh/crore formatting, paise arithmetic, bare amounts
AffordabilityTest          11   budget vs balance, and unknown never counted as zero
BugReproTest               11   a QA pass, each claim proved or disproved before any fix
MeridiemTwinTest           11   the duplicates that fixing AM/PM created
MoneyFlowTest              10   spending vs saving vs moving
PacingTest                 10   budget pacing against the household's own figures
ReadEveryPaymentTest       10   payments from senders nobody vouched for
BackupCodecTest             9   the backup file format, and every way reading one fails
CardConversionTest          9   an account the ledger found, turned into a card
InvestmentLedgerTest        9   FD/RD/SIP handling
PossiblePaymentsTest        9   a payment-shaped message becomes a question
RefundTest                  9   a refund is neither spending nor income
PaletteContrastTest         8   the palette measured rather than trusted
PutAsideTest                8   money owned and not spendable, kept out of what is left
RegionalBankTest            8   regional/small-finance senders, bare-credit labels
SettlesTrackedCardTest      8   whether paying a card bill counts as spending
SheetRowFormatTest          8   Apps Script row shape, raw-message redaction
TrashTest                   8   the thirty days, and the promise the caption makes
TypedBalanceDriftTest       8   a typed balance has to move when money moves
CardBillEchoTest            7   one bill, two banks, two messages
DedupeTimeTest              7   bank + UPI-app double messages
DuplicateMessageTest        7   one debit described twice by one bank is one row
MoneyModelFixesTest         7   four money-model defects, proved before they were fixed
RefundLinkTest              7   linking a refund is undoable, and two refunds add up
SelfTransferTest            7   a round trip is not a purchase and a windfall
AccountNumberMerchantTest   6   an account number is not a payee
AccountOwnerTest            6   whose account it is, as said rather than implied
BillReminderTest            6   money owed, not money spent
CounterpartyAccountTest     6   the account paid, when the bank named one
PaidFromTest                6   a payment you typed still came out of somewhere
RefundPromptTest            6   which credits are worth asking about
ReuploadScopeTest           6   what a re-upload admits to leaving behind
RemoveAccountTest           5   removing an account added by mistake
SaveableStateTest           5   what rememberSaveable holds has to survive a Bundle
ZeroBalanceAccountTest      5   an account with no balance is not an account with none
CreditCardTest              4   a card balance is owed, and never joins what you have
MixedReferenceDedupeTest    4   one payment, two texts, only one with a reference
MovingMoneyTest             4   sorting a payment as moving money takes it out of spend
PaidFromEntryTest           4   attributing a payment after the fact
RecurringRobustnessTest     4   a commitment the detector drops, pacing spends twice
RescanIdempotencyTest       4   a backfill never duplicates
BudgetAlertReachableTest    3   "over your monthly budget" has to be reachable
DateOnlyTest                3   never print a clock time the bank did not give
DeletedAtMigrationTest      3   the schema change, against a real database
RescanRespectsDeletionTest  3   re-reading the inbox must not overturn a deletion
ColumnCarryTest             2   every column has to survive every crossing
CorpusReportTest            1   parser coverage over the whole corpus
RealInboxAuditTest          1   an instrument, not a guard: the real parser on a real inbox
                          ―――
                          502   across 58 suites
```

Regenerate the counts rather than trusting this block:

```bash
./gradlew :app:testReleaseUnitTest
grep -ho 'tests="[0-9]*"' app/build/test-results/testReleaseUnitTest/*.xml \
  | grep -o '[0-9]*' | paste -sd+ | bc
```

**Most of the newer suites are refusals.** Four of them decide whether two rows are
the same event, and all four can delete or neutralise real money if they say yes when
they should say no. So they are written from the direction of what must survive.

The case that shaped them came from the household, not from a test: two ₹10,000
movements on 2 August, one minute apart, from the same bank — a fixed deposit maturing
and rent paid to a person. Same amount, same day, same bank, both real. Any rule
matching on amount and time alone destroys one of them. What saves it is the account
tail: one account, so two events, not a round trip.

`DedupeTimeTest` and `MixedReferenceDedupeTest` guard deduplication from opposite
directions, because it has failed in both. Once it merged two real payments and lost
one; once it recorded a single payment twice because the bank's message and the UPI
app's were filed under different lookup keys. Losing a transaction is far worse than
keeping a duplicate: a duplicate is visible and one tap removes it, a missing row is
invisible forever.

`RecurringDetectorTest` spends more cases on what must *not* be detected than on what
must. Weekly groceries at one shop repeat as reliably as a subscription; the amounts
are what tell them apart. Claiming a commitment that does not exist makes every other
number in the app suspect.
