# Build Prompt — "Ours" Android Expense Tracker

> Paste everything below into Claude Code (or any coding agent) as the initial task.
> Build it in stages — do not attempt all phases in one shot.

---

## The Ask

Build a native Android app called **Ours** that automatically tracks my daily
expenses by reading bank/UPI SMS on my phone, shows a beautiful monthly summary, and
**syncs with my wife's phone** so we see one combined household budget — she pays from
her phone, I pay from mine, and both of us see every transaction.

Currency is **Indian Rupees (₹)**. Locale is India. I am the primary user; my wife is
the second member of the same household.

---

## 1. Tech Stack (use exactly this)

| Layer | Choice |
|---|---|
| Language | Kotlin, 100% |
| UI | Jetpack Compose + Material 3 (Material You dynamic color) |
| Min / Target SDK | minSdk 26, targetSdk 35 |
| Architecture | MVVM + Clean-ish: `ui / domain / data` modules |
| DI | Hilt |
| Local DB | Room (single source of truth, offline-first) |
| Async | Coroutines + Flow |
| Background work | WorkManager |
| Charts | Vico (or compose-charts) — no WebView charts |
| Sync | **No backend server.** Google Drive (shared folder) + Nearby Connections over Bluetooth. Auth via Google Sign-In only |
| Crypto | Tink (AES-256-GCM) for the sync payload |
| Navigation | Navigation-Compose, type-safe routes |
| Build | Gradle Kotlin DSL + version catalog (`libs.versions.toml`) |

---

## 2. Core Feature — SMS Expense Parsing

### Permissions
- `RECEIVE_SMS` and `READ_SMS`, requested at runtime with a clear rationale screen
  explaining "SMS never leaves your phone unencrypted; only the parsed amount and
  merchant are synced."
- Handle denial gracefully — the app must remain fully usable in **manual-entry mode**.

### Ingestion — two paths
1. **Backfill:** on first launch, read the last 6 months of SMS from the inbox
   (`Telephony.Sms.Inbox`) and parse them in a WorkManager job with a progress UI.
2. **Live:** a `BroadcastReceiver` on `SMS_RECEIVED` parses new messages instantly and
   posts a notification: *"₹450 at SWIGGY — tap to categorize."*

### Parser design (this is the heart of the app — build it properly)
Create `data/sms/SmsParser.kt` with a **rule-based engine driven by a list of
`BankRule` objects**, NOT one giant regex. Each rule has: sender-ID pattern, amount
regex, merchant regex, type (debit/credit), and account-tail regex.

Ship rules covering these Indian senders (match on the 6-char TRAI header, e.g.
`AD-HDFCBK`, `VM-ICICIB`, `JD-SBIINB`, `AX-AXISBK`, `VK-KOTAKB`, `BZ-PNBSMS`,
`AD-CANBNK`, `VM-BOIIND`, `JM-YESBNK`, `AD-IDFCFB`, `VM-INDUSB`, `AD-FEDBNK`,
`AX-PAYTMB`, `VM-GPAY`, `JD-PHONPE`, `AD-AMZNPY`, `VM-CRED`, `AD-SLICEIT`):

- HDFC, ICICI, SBI, Axis, Kotak, PNB, Canara, BoB, Yes, IDFC First, IndusInd,
  Federal, Union Bank
- UPI apps: GPay, PhonePe, Paytm, Amazon Pay, CRED, BHIM
- Credit cards: HDFC/ICICI/SBI/Axis card alerts, slice, OneCard
- Wallets: Paytm Wallet, Amazon Pay balance

Handle all of these amount formats:
```
Rs.1,234.56   Rs 1234    INR 1,234.56   ₹1234.56   Rs.1.2K
```

Detect **transaction type** from keywords:
- Debit: `debited`, `spent`, `paid`, `withdrawn`, `purchase`, `txn of`, `sent to`
- Credit: `credited`, `received`, `deposited`, `refund`, `cashback`, `salary`

**Must ignore (critical — this is where naive apps fail):**
- OTPs (`OTP`, `one time password`, `do not share`, 4–8 digit codes)
- Promotional / offer SMS (`offer`, `discount`, `sale`, `apply now`, `EMI at 0%`)
- Balance-enquiry SMS (`Avl Bal`, `available balance` with no txn verb)
- Failed/declined/reversed transactions (`failed`, `declined`, `reversed`, `not
  processed`)
- Bill-due reminders (`due on`, `minimum amount due`) — these are *upcoming*, store
  them separately as reminders, not expenses
- Personal (non-DLT) numbers — only accept 6-char alphanumeric TRAI sender IDs

**Deduplication:** UPI txns often generate two SMS (bank + UPI app). Dedupe on
`(amount, ±3-minute window, last-4-of-account or UPI ref)` and keep the richer one.

Extract when present: amount, merchant/VPA, date-time, account last-4, UPI ref no,
available balance.

Store the raw SMS body on the row for debugging + a **"Report wrong parse"** button
that opens a review screen where I fix it — and the fix trains a local override map
(merchant string → category) so it's right next time.

### Auto-categorization
Merchant-keyword map, editable in Settings:
`Food & Dining, Groceries, Transport & Fuel, Shopping, Bills & Utilities, Rent,
Health, Education, Entertainment, Travel, Investments, EMI & Loans, Transfers, Other`

Seed with Indian merchants: Swiggy/Zomato/Zepto/Blinkit/Instamart → Food & Groceries;
Uber/Ola/Rapido/IRCTC/IndianOil/HPCL → Transport; Amazon/Flipkart/Myntra/Ajio →
Shopping; Jio/Airtel/BSNL/TataPower/Adani → Bills; Apollo/PharmEasy/1mg → Health;
Netflix/Hotstar/Spotify/BookMyShow → Entertainment.

Uncategorized txns land in an **"Needs review"** inbox chip on the home screen.

---

## 3. Household Sync (my phone ⇄ wife's phone) — serverless

**There is no backend to run.** Sync happens two ways over the same data format:
Google Drive when we're apart, Bluetooth when we're together.

### 3.1 The data model that makes this work — append-only event logs

This is the core design decision. **Each device writes only to its own log file and
never touches anyone else's.** Merge conflicts are therefore impossible by
construction — merging is just a union of events.

```kotlin
data class SyncEvent(
    val eventId: String,     // UUID
    val txnId: String,       // the transaction this event is about
    val op: Op,              // UPSERT | DELETE
    val lamport: Long,       // Lamport clock — NOT wall-clock
    val deviceId: String,    // stable UUID, generated on first run
    val ownerUid: String,
    val payload: Transaction?  // null for DELETE (tombstone)
)
```

- **Use a Lamport clock, not timestamps.** Two phones have skewed clocks; a Lamport
  counter (`local = max(local, maxSeen) + 1` on every merge) gives a deterministic
  order without trusting either clock. Keep wall-clock too, but only for display.
- **Merge rule:** group all events by `txnId`, winner is highest `lamport`, ties
  broken by `deviceId` string comparison. Last-writer-wins per transaction.
  Deterministic — both phones always converge to the identical state.
- **Compaction:** when a device's log passes 2,000 events, write a snapshot
  (`device-{id}-snap.json`, current state of the transactions it owns) and truncate
  the log. Readers load snapshot + remaining log.
- **Encrypt every log line** with AES-256-GCM (Tink) using a household key derived
  via HKDF from the invite secret. Store the key in Android Keystore. Drive holds
  only ciphertext.

Build a transport-agnostic interface so both paths share all merge code:

```kotlin
interface SyncTransport {
    suspend fun push(myLog: EncryptedLog)
    suspend fun pull(): List<EncryptedLog>   // everyone else's logs
}
// DriveTransport, NearbyTransport
```

### 3.2 Path A — Google Drive (the always-on path)

**Important constraint to design around:** Drive's `appDataFolder` is scoped per
Google account, so my wife's app cannot see mine. Do **not** use `appDataFolder`.
Instead:

1. I create the household → app creates a Drive folder `Ours Sync` in **my**
   Drive and shares it with my wife's Google account via the Drive API
   (`permissions.create`, role `writer`).
2. The invite QR / 6-char code carries the **folder ID + the household key**.
3. Both apps read and write `device-{deviceId}.jsonl` inside that folder.

**OAuth scope — verify this early in Phase 5 before building on it:**
- *Preferred:* `drive.file` scope + Google Picker on my wife's device, where she
  selects the shared folder once during onboarding. Narrowest possible scope.
- *Verify:* that picker-granted folder access still covers files **created later by
  the other device** inside that folder. If it does not, fall back to the full
  `drive` scope, and keep the OAuth consent screen in **Testing** publishing status
  with our two accounts as test users — restricted scopes need no Google verification
  in Testing mode, which is fine for a 2-person sideloaded app.
- Report which one actually worked before continuing.

**Schedule:** `WorkManager` periodic sync every 15 min with a network constraint,
plus an immediate one-shot on app foreground and after each new SMS is parsed.
Expedited work for the foreground case.

### 3.3 Path B — Bluetooth / Nearby Connections (the instant path)

Use **Nearby Connections API** (`play-services-nearby`), not raw RFCOMM sockets — it
negotiates BLE for discovery then Bluetooth Classic or Wi-Fi Direct for transfer, and
handles pairing itself.

- Strategy `P2P_CLUSTER`; **service ID = the household ID**, so only our two phones
  ever discover each other.
- Both devices advertise and discover while the app is in the foreground. On
  connection, exchange logs, merge, show a brief "Synced with Priya ✓" snackbar.
- Optional Settings toggle **"Keep syncing when nearby"** → starts a foreground
  service with a low-priority persistent notification. **Default OFF** — be honest in
  the UI that this costs battery. Never enable it silently.
- Permissions: `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` on
  API 31+, `ACCESS_FINE_LOCATION` below 31, `NEARBY_WIFI_DEVICES` on API 33+.
  Add `android:usesPermissionFlags="neverForLocation"` to the scan permission so we
  don't have to justify location access.
- Bluetooth is a **bonus, never a requirement** — if it fails or is off, Drive covers
  it silently. Never block or error the user on Bluetooth.

### 3.4 Sync UX
```
SMS / manual entry
      ↓
   Room (single source of truth)  ──►  UI reads only from Room via Flow
      ↓  append SyncEvent to my local log
   ┌──────────────┴──────────────┐
Drive worker (~15 min)     Nearby (instant, when close)
   └──────────────┬──────────────┘
        merge others' logs → upsert Room → UI updates
```

- A subtle sync-status pill in the top bar: `Synced 2m ago` / `Syncing…` /
  `Offline — 3 pending` / `Synced via Bluetooth`. Tap to force a sync.
- **Each phone parses only its own SMS. Raw SMS bodies are never synced** — only the
  parsed amount, merchant, category and timestamp, and those are encrypted in transit
  and at rest in Drive.
- Per-transaction **`splitType`**: `PERSONAL` (mine only) or `SHARED` (counts toward
  the household total). Default SHARED, toggleable per transaction.
- Home screen segmented toggle **`Both · Me · Wife`** filters every chart and list
  live.
- Settings shows household members, the invite QR, "Sync now", last-sync time per
  transport, and "Leave household" (which revokes the Drive share).

---

## 4. Screens

1. **Onboarding** — 3 animated pages → Google Sign-In → SMS permission rationale →
   create-or-join household (show QR / scan QR) → backfill progress with a nice
   indeterminate animation.

2. **Home / Dashboard**
   - Big hero card: **"₹42,380 spent this month"** with an animated count-up, an arc
     progress ring against the monthly budget, and colour that shifts green → amber →
     red as budget is consumed.
   - Sub-line: `↓ 12% vs last month` with trend arrow.
   - `Both · Me · Wife` segmented toggle.
   - Horizontally scrolling category chips with mini-sparklines.
   - Donut chart of category split, animated on entry, tap a slice to drill down.
   - "Today" + "This week" quick stats.
   - Recent transactions list (bank logo/merchant avatar, amount right-aligned, a
     small avatar chip showing **who paid**).
   - FAB → add manual expense (bottom sheet, amount keypad first).

3. **Transactions** — infinite list grouped by day with sticky date headers; search;
   filter by category / member / amount range / date range; swipe-left to delete,
   swipe-right to recategorize; long-press for multi-select bulk categorize.

4. **Monthly Summary** (the showpiece)
   - Month picker (horizontal pager, swipe between months).
   - Total spent, total credited, net savings.
   - Bar chart: daily spend across the month, tap a bar for that day's detail.
   - Category breakdown with amount, %, and vs-last-month delta per category.
   - **Who spent what** — split between me and my wife, with a stacked bar.
   - Top 5 merchants.
   - Biggest single expense of the month.
   - "You spent ₹X more on Food than last month" style insight cards.
   - **Export** the month as PDF and CSV, share via the system share sheet.

5. **Budgets** — overall monthly budget + optional per-category budgets, with
   progress bars and a notification at 80% and 100%.

6. **Settings** — household members + invite QR + sync status/controls, category &
   merchant-rule editor,
   parser rule tester (paste an SMS, see what it parses to), currency/locale, theme
   (Light / Dark / System / Dynamic), biometric app lock, data export, delete account.

---

## 5. Design — must be genuinely eye-catching

This is not a spreadsheet with buttons. Aim for the polish of Cred / Jupiter / Fi Money.

- **Material 3 expressive**, dynamic color from wallpaper, with a strong fallback
  palette: deep indigo/violet primary, warm coral for overspend, mint green for
  savings, on a near-black (`#0F1115`) dark surface / soft off-white light surface.
- **Gradient mesh hero card** with subtle glassmorphism and an elevated shadow.
- **Motion everywhere, but fast:** count-up number animations, chart draw-in on
  first composition, shared-element transitions from list row → detail, spring-based
  FAB expansion, staggered list-item entry.
- **Typography:** one expressive display font for amounts (tabular figures, so
  columns align), clean sans for body. Amounts formatted Indian-style:
  **₹1,23,456** (lakh/crore grouping, not 123,456) via `NumberFormat` with
  `Locale("en", "IN")`.
- Merchant avatars: coloured circle with the first letter, colour derived
  deterministically from the merchant name hash.
- Empty states with illustrations, not blank screens.
- Skeleton shimmer loaders, never a bare spinner.
- Haptic feedback on key actions; edge-to-edge with proper insets; full RTL and
  TalkBack support; respect `Reduce motion`.
- Ship an **app icon and adaptive icon**, plus a **home-screen widget** showing this
  month's spend.

---

## 6. Privacy & Security (non-negotiable)

- Room DB encrypted with SQLCipher; key in Android Keystore.
- Raw SMS bodies **never** leave the device.
- **No third-party server sees our data at all.** Sync rides on my own Google Drive,
  and everything written there is AES-256-GCM ciphertext — Google stores opaque blobs.
- Optional biometric/PIN lock on app open.
- No analytics SDKs, no ads, no third-party trackers.
- A clear in-app privacy explanation screen.
- **Play Store note:** `READ_SMS`/`RECEIVE_SMS` are restricted permissions — a public
  Play listing requires a declaration and usually gets rejected for finance-tracker
  use. Plan for **sideload / internal-testing track / personal distribution**. Also
  implement an alternate ingestion path: **notification listener
  (`NotificationListenerService`)** parsing bank notifications, which has no such
  restriction — make SMS vs. notification a user-selectable source.

---

## 7. Quality Bar

- Unit tests for `SmsParser` with **at least 40 real-world SMS samples** across banks,
  including the negative cases (OTP, promo, failed, balance-only). This is the most
  important test suite in the app.
- Tests for SMS dedup and monthly-aggregation math.
- **Sync convergence tests** — simulate two devices with interleaved edits, deletes,
  and out-of-order log delivery; assert both devices reach byte-identical state
  regardless of merge order. Include the case where both phones edit the same
  transaction while offline, and where one phone's log arrives twice.
- Compose UI tests for the main flows.
- No hardcoded strings — everything in `strings.xml`.
- KtLint/Detekt clean.

---

## 8. Build Order (do these in sequence, ask me to verify after each)

1. **Phase 1** — Project skeleton, Hilt, Room schema, Compose theme + design system,
   Home screen with fake data. *Get the design right first.*
2. **Phase 2** — SMS permission flow, `SmsParser` + full unit test suite, backfill
   worker, live receiver. Local-only, single user.
3. **Phase 3** — Transactions screen, categorization, manual entry, budgets.
4. **Phase 4** — Monthly summary + charts + PDF/CSV export.
5. **Phase 5a** — The sync core, transport-free: event log, Lamport clock, merge
   function, compaction, encryption, and the convergence test suite. Prove two
   simulated devices converge **before** touching any network API.
6. **Phase 5b** — Google Sign-In, household create/join via QR, `DriveTransport`.
   Resolve the OAuth scope question first and report which scope worked.
7. **Phase 5c** — `NearbyTransport` over Bluetooth, sync-status pill,
   `Both · Me · Wife` filtering.
8. **Phase 6** — Polish: animations, widget, app lock, DB encryption, empty states,
   notification-listener fallback.

Start with Phase 1. Show me the design before building logic on top of it.
