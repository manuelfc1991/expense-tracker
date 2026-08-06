# Review: the money model, and where the tests are not

Written **6 August 2026**, against **5.20 (61)**, from the code and from the household's
own live figures on that date. `docs/DONE.md` is what has shipped and `AT-HOME.md` is what
needs the other phone; this is the third thing — what the app gets wrong, and what is
worth building next.

Findings are ordered by what they cost the household, not by effort.

---

## 1. The budget has no idea what day it is

**Severity: high. The app's central number is unreliable in the direction that matters.**

On the morning this was written, Home read:

> **₹28,763.20 spent · budget ₹38.5K · 74% used · ₹9,710 left** — meter green

That is 74% of a month's budget consumed on the **6th of a 31-day month**. Straight-line
pace would be about 19%. The screen said nothing, because nothing in the budget path takes
the date as an input:

```kotlin
val fraction = spent.toFloat() / budget          // HomeScreen.kt:442
val over = spent > budget                        // HomeScreen.kt:443

private val THRESHOLDS = listOf(100, 80)         // BudgetAlerter.kt:103
```

`BudgetAlerter` will fire "80% of your budget used" identically on the 3rd and the 28th.
On the 28th that is a shrug; on the 3rd it is the most useful thing the app could say all
month. Telling a household whether it is on course is the entire job of a budget, and it
is the one question this cannot answer.

### Why the obvious fix is wrong

Dividing the budget by days elapsed would cry wolf every month in this household. The
daily bars for August show **₹16,955.79 on day 3 — 59% of the month's spending in a single
day** — which is rent and a card bill landing together. Any linear pace model reports a
household that pays rent on the 1st as catastrophically overspent for the first fortnight,
every single month, and an alert that is wrong every month is an alert people switch off.

### The shape that works

Pace the **discretionary** money, not the total:

```
₹9,710 left · ₹2,400 still committed · 25 days · ₹292 a day
```

Every input already exists:

- `MonthlyAggregator.committedRemaining()` returns recurring charges expected before month
  end and not yet paid — it already excludes anything whose date has passed
- `RecurringDetector` already identifies what repeats
- `Affordability` already reasons about commitments, and already takes them off the
  *capacity* side rather than the budget side, for a documented and correct reason

So the daily figure is `(budgetLeft − stillCommitted) ÷ daysRemaining`, and rent on the 1st
never triggers it, because rent was committed and is now paid.

That number — what can be spent today without missing the cap — is what a household
actually uses. It is also the honest one to alert on.

---

## 2. A refund is income, and the purchase it undoes still counts as spending

**Severity: high. Overstates spending, in an app whose headline is spending.**

```kotlin
if (type == TxnType.CREDIT) {
    return when {
        RETURNING_INVESTMENT.any { it in m } -> Category.INVESTMENTS
        else -> Category.INCOME                      // Categorizer.kt:25
    }
}
```

Every credit that is not a maturing investment becomes Income. The Rules screen states the
rule to the user in as many words: *"Money coming in is never matched — credits are always
Income."* And spending counts debits only:

```kotlin
fun totalSpent(transactions: List<Transaction>): Long =
    transactions.filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }
```

Return a ₹2,000 item and the ledger holds a ₹2,000 debit and a ₹2,000 credit. Net worth is
right. **Spending is overstated by ₹2,000 and the budget has been charged for a purchase
that was undone.** Near the cap, the app tells the household to stop when it need not.

This is a different thing from the reversal handling that already exists and works:
`SmsParser.kt:170` rejects messages containing "reversed", "reversal", "refunded to your",
"cancelled" — that is the bank reporting a payment that never completed, and refusing to
record it is correct. A merchant refund arriving three days after the purchase is a
genuine, separate credit, and nothing catches it.

### What not to do

Automatic matching on amount. Two ₹2,000 movements in a month are far more often two real
payments than a purchase and its refund — this is the same trap `DedupeTimeTest` and
`MixedReferenceDedupeTest` exist to guard, and the household has already been bitten by a
matcher that was too eager: two ₹10,000 movements one minute apart, an FD maturing and rent
paid to a person, both real.

### What to do

A **"this is a refund"** action on a credit, which asks which purchase it cancels. Manual,
explicit, and auditable — the refund then nets out of `totalSpent` instead of inflating
income. The same pattern the app already uses for every other judgement it refuses to make
on the household's behalf.

---

## 3. Two timezones in one app

**Severity: latent. Nothing wrong today; wrong the first time either of you travels.**

All money maths runs in one fixed zone:

```kotlin
val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")     // MonthlyAggregator.kt:29
```

Every date the interface formats runs in another:

```kotlin
Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
```

— in `Statement.kt`, `SummaryScreen.kt`, `TransactionDetailScreen.kt`, `TrashScreen.kt`,
`SettingsScreen.kt`, and, most consequentially, in the **tracking-start date picker**:

```kotlin
onPick(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
```

The chosen day is converted in the phone's local zone and then compared against
transactions bucketed into months in IST. Both phones sit in IST today, so the two agree
and nothing is visibly wrong. Take a phone to a different zone and the cutoff moves by
hours, and Activity's day headings stop agreeing with the month totals they sit under.

One constant, used in both places. `ExportManager` and `BackupManager` already hard-code
Asia/Kolkata and are consistent; the interface is the odd one out.

---

## 4. Where the tests are not

**Severity: high, and it is the reason the findings above were found by eye.**

| Package | Lines | Test classes |
|---|---|---|
| **ui** | **13,103** | **0** |
| data/sms | 1,707 | 12 |
| **data/repo** | **1,703** | **0 dedicated** |
| domain | 1,556 | 15 |
| data/sync | 1,229 | 3 |
| work | 375 | 0 |
| widget | 110 | 0 |

Roughly two thirds of the codebase has no tests, and it is the two thirds that produced
every defect found on 6 August:

- a confirmation dialog printing ₹450 for a row showing ₹450.75
- a wrong-file message pasting the parser's internals *and a quotation of the chosen
  file* into the interface
- a missing full stop that ran two sentences together
- an offer to sync a restore that had written nothing
- an amount column running off the edge of the display
- an empty bin drawing the ledger's receipt
- money-direction arrows used as an expand/collapse control

`301 tests` were green through every one of them. The suite is genuinely good at what it
covers — parsing, deduplication, merge convergence, money arithmetic — and blind to
everything a person looks at.

`data/repo` is worth naming separately. It is the **only writer to the transactions
table**, and its coverage is incidental: `RescanIdempotencyTest` exercises it through a
real Room database, but nothing tests it directly. `applyRestore`, which mints a sync event
for every restored row and has to mint a DELETE rather than an UPSERT for tombstones, has
no test of its own.

The floor worth having is the ViewModels — not Compose UI tests, just the state machines.
Robolectric, Turbine and `kotlinx-coroutines-test` are already dependencies.

---

## What is not wrong

Worth recording, because it is why the above are worth fixing rather than rebuilding
around. The money model is more careful than most commercial apps manage:

- **Spending, debited and saved are three different numbers**, and the app never conflates
  them. A month where the household saved hard does not read as a month where it overspent.
- **Unknown is never zero.** An account with no known balance is counted and reported, not
  summed as ₹0 — `AffordabilityTest` guards it.
- **Card bills are excluded from spending**, because they settle purchases already counted
  one by one. Double-counting them would inflate every month with a card in it.
- **A maturing FD is not income.** Without that, net savings lies spectacularly once a
  quarter.
- **Budget is permission, balance is capacity**, and `Affordability` says which of the two
  is binding rather than blending them into one misleading figure.
- **Every chart funnels through `spendable()`**, so the donut, the daily bars, the member
  split and the headline cannot disagree.

---

## Order of work

1. **Commitment-aware pacing.** No new data, every input present, and the live figures show
   the app currently failing to flag something worth flagging. About a day.
2. **A ViewModel test floor.** Cheap, and it is what stops this list regrowing.
3. **The refund action.** Needs a little interface design; the model change is small.
4. **One timezone constant.** An hour, and it closes a trap rather than a bug.
