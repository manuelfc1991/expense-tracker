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
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 238 tests
```

Kotlin · Jetpack Compose · Material 3 · Room · Hilt · WorkManager · minSdk 26

---

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
editable auto-assign rules, bulk sorting by merchant, manual entry, transactions with
search/filter/grouping, swipe-to-delete with undo and multi-select for acting on many
rows at once, renaming a payee the bank never named, recurring-charge detection,
monthly summary, budgets, a tracking start date that retires older months without
deleting them, CSV + PDF export, a home-screen widget that redraws when the numbers
move, theming, QR invite generation and scanning, and the sync core with its
convergence tests.

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

**Known gaps, honestly:**

1. **Bluetooth sync is unproven against a second device.** Its runtime permissions were
   declared in the manifest but never requested until recently, so every entry point
   silently reported "no peers" — the toggle looked on and did nothing. The request now
   happens when you enable it, and the preconditions check out on both phones, but no
   two devices have ever completed a Nearby handshake. Emulators get as far as
   `requestConnection` and then fail on the virtual radio.
2. **Three or more people is supported and tested, but not on three real phones.** The
   filter, the split bar, the merge and both transports take an arbitrary number of
   members; `AggregationTest` covers a household of three, and a four-member household
   has been exercised across one phone and three emulators. Two real handsets is the
   most that has ever run at once.
3. **A retired month is retired for the household, not just for you.** The tracking
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
CryptoAndCodecTest         15   key derivation, tamper detection, corrupt-line recovery
RecurringDetectorTest      15   what repeats, and what only looks like it
MergeConvergenceTest       14   sync convergence under adversarial ordering
CategoryPredictorTest      12   one-tap category guesses
MeridiemTwinTest           11   the duplicates that fixing AM/PM created
MoneyTest                  11   lakh/crore formatting, paise arithmetic, bare amounts
MoneyFlowTest              10   spending vs saving vs moving
InvestmentLedgerTest        9   FD/RD/SIP handling
RegionalBankTest            8   regional/small-finance sender coverage, bare-credit labels
SheetRowFormatTest          8   Apps Script row shape, raw-message redaction
CardBillEchoTest            7   one bill, two banks, two messages
DedupeTimeTest              7   bank + UPI-app double messages
SelfTransferTest            7   a round trip is not a purchase and a windfall
AccountNumberMerchantTest   6   an account number is not a payee
BillReminderTest            6   money owed, not money spent
MixedReferenceDedupeTest    4   one payment, two texts, only one with a reference
RescanIdempotencyTest       4   a backfill never duplicates
CorpusReportTest            1   parser coverage over the whole corpus
                          ―――
                          238
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
