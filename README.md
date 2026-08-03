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
in plain text including the original bank messages. The sheet itself stays private;
only the script URL is shared.

Setup is walked through inside the app — **Settings ▸ Sheet sync ▸ How do I set this
up?** — which also carries the script itself, copyable and shareable, so the second
phone never depends on having this repository to hand. The script has one source,
`sheet-sync/Code.gs`; Gradle copies it into the app at build time rather than keeping a
second copy that could drift into speaking a different wire format.

Neither path needs a Cloud Console project, OAuth client, API key or SHA-1
fingerprint. Bluetooth is encrypted end to end and involves no third party at all.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 187 tests
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
listener as an alternative source, categorization with learned user corrections,
editable auto-assign rules, bulk sorting by merchant, manual entry, transactions with
search/filter/grouping and swipe-to-delete with undo, monthly summary, budgets, a
tracking start date that retires older months without deleting them, CSV + PDF export,
home-screen widget, theming, QR invite generation and scanning, and the sync core with
its convergence tests.

**Verified on one phone, not yet on two.** Everything above was exercised against a
real inbox. The sync *transports* have not been: `MergeConvergenceTest` proves the merge
converges under adversarial ordering, but no pair of phones has actually exchanged a log
in the field. Treat "sync works" as tested-in-theory until you have run it.

**On-device security**

The Room database is encrypted with SQLCipher, keyed by 32 random bytes held in
`EncryptedSharedPreferences` whose master key lives in the Android Keystore. Verified,
not assumed — the file header reads as noise and `sqlite3` refuses to open it. Optional
app lock uses `BiometricPrompt` with `DEVICE_CREDENTIAL` as a fallback, because a lock
you can get permanently stuck behind is worse than no lock.

**Known gaps, honestly:**

1. **The notification source is second-class.** Budget alerts and bill reminders are
   raised from `SmsReceiver` only. Switch the source to Notifications and both stop
   firing, silently — the expenses still import, but you are no longer warned about a
   budget or an upcoming bill and nothing says so.
2. **The widget never refreshes on a data change.** Nothing outside the provider asks
   it to update, so it shows whatever it last drew until the system happens to.
3. **Bulk multi-select** is not built.
4. **No two phones have exchanged a log.** `MergeConvergenceTest` proves the merge
   converges under adversarial ordering, and the Sheet transport is verified end to end
   against a live deployment — but device-to-device convergence has never been observed.
5. **Nearby is unproven against a second device.** Its runtime permissions were declared
   but never requested until recently, so every entry point silently reported "no peers".
   The request now happens and the preconditions check out on one phone; a real exchange
   has not been seen.
6. **Three or more people is supported but only tested with two.** The filter, the
   split bar, the merge and both transports all take an arbitrary number of members,
   and `AggregationTest` covers a household of three — but only two real devices have
   ever exchanged a log.
7. **A retired month is retired for the household, not just for you.** The tracking start
   date bounds what syncs as well as what is drawn, so months before it never reach the
   other phone. That is deliberate — but it means a partner joining later receives only
   what is in scope, and the two settings pull against each other.
4. **Shared-folder sync has no UI.** The transport and its tests exist; nothing can
   choose a folder, so the path is dormant. See above.
5. **Nearby sync is unproven against a second device.** Its runtime permissions were
   declared in the manifest but never requested until recently, so every entry point
   silently reported "no peers" — the toggle looked on and did nothing. The request now
   happens when you enable it, and the preconditions check out on one phone, but a real
   two-phone exchange has not been observed.

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
SmsParserTest          47   bank shapes, OTP/promo/failed rejection, edge cases
RegionalBankTest        8   regional/small-finance sender coverage, bare-credit labels
SmsParserRealWorldTest 19   real messages, kept as a regression corpus
AggregationTest        14   month math, Both/Me/Partner filtering, insights
MergeConvergenceTest   13   sync convergence under adversarial ordering
CategoryPredictorTest  12   one-tap category guesses
CryptoAndCodecTest     11   key derivation, tamper detection, corrupt-line recovery
MoneyTest              11   lakh/crore formatting, paise arithmetic, bare amounts
MoneyFlowTest          10   spending vs saving vs moving
InvestmentLedgerTest    9   FD/RD/SIP handling
SafFolderNamingTest     7   one file per device, never a shared one
DedupeTimeTest          7   bank + UPI-app double messages
BillReminderTest        6   money owed, not money spent
SheetRowFormatTest      8   Apps Script row shape, raw-message redaction
RescanIdempotencyTest   4   a backfill never duplicates
CorpusReportTest        1   parser coverage over the whole corpus
                       ―――
                      187
```
