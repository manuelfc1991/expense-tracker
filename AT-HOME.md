# At home: the list

Everything left that needs the other phone, or knowledge only you have.

Last reviewed **6 August 2026**, against **5.16 (57)**. Earlier versions of this file
described a 2.3 release and a categorising job that is long finished; that work is done
and has been removed rather than left to mislead.

---

## 1. Her phone: update to 5.16

**Settings ▸ Updates ▸ Check for updates** → Download → Install.

Everything below depends on this. Her phone has never run a build that can sync a
budget, an account balance, or the fact that she exists — all three were write-only
until 5.5, and membership until 5.4. 5.16 also adds the backup in step 7.

## 2. Her phone: is it in the household at all?

This has never been established, and until 5.4 there was **no way to establish it**. A
partner who had joined and simply not spent anything looked exactly like a partner who
was never there, because the app inferred people from their transactions.

From 5.4 the answer is visible. On **either** phone, after both have synced:

**Settings ▸ Household** should list two names.

- **Two names** — she is in, and always was.
- **One name** — she never joined. Fix it: on your phone **Settings ▸ Household ▸ Add
  someone else**, then on hers scan the QR or type the six-letter code.

## 3. Both phones: sync, then compare

**Settings ▸ Sync now** on each, hers first.

Then put the two screens side by side. They should now agree on all of:

| | |
|---|---|
| Home ▸ spent this month | same figure |
| Home ▸ budget | ₹40K, same % used, same left |
| Summary ▸ Where it went | same categories, same amounts |

This was rehearsed on an emulator joined to the real sheet on 5 August and all of it
matched to the rupee. Her phone is the only untested part.

> **Expected, not a bug:** she will see **no account balances and no "safe to spend"**.
> Balances are the household owner's, and she owns none. If you would rather she saw the
> household's real position, that is a one-line change to the visibility rule — ask.

## 4. Her phone: does it still receive bank SMS?

Only if this was ever fixed — the network was once set to 5G-only, which silently
dropped every bank message.

**Settings ▸ Network & internet ▸ SIMs ▸ [her SIM]** → **Preferred network type** →
**5G/4G/3G/2G (auto)**, and **VoLTE** on. Then toggle airplane mode.

**Check:** text her from your phone. If it arrives, so will the banks'.

Then **Settings ▸ Rescan messages** in Ours. A rescan never duplicates.

> Anything her network dropped while 5G-only was on is gone, not queued.

## 5. The Bluetooth test

Still the one claim in this project never run on real hardware — an emulator cannot
stand in for it.

1. **Settings ▸ Sync ▸ Keep syncing when nearby → on**, both phones
2. Grant the Bluetooth permissions when asked, both
3. Phones side by side, both apps open, wait a minute

**Check:** **Sync now** should report *Bluetooth* as the transport rather than *Sheet*.

If nothing happens that is a real result and worth reporting — it means the Nearby
handshake fails on real devices, which has never been established either way.

> Note: shared rules — balances, budget, membership, merchant corrections — travel over
> the **sheet only**. Bluetooth carries transactions. So even with Bluetooth working,
> the phones still need one sheet round to agree on a budget.

## 6. Housekeeping: remove the test member

An emulator was joined to the household on 5 August to test the sync, and left a member
called **TEST-EMULATOR** behind. It is cosmetic, but it is not real.

Blank the value of its row in the sheet's `rules` tab (`member` /
`c8513f5e-93a5-4212-bbe3-4ddc27411aa0`) and bump that row's `updatedAt`, then sync both
phones. 5.6 and later read an emptied member as "no longer in the household".

**Do the sheet edit and it stays done.** Ours has been uninstalled from the `ours-api36`
AVD, which is what made this safe: the tombstone only survives while nothing republishes
it. `RulesRepository.publishSelf()` writes this phone's member row whenever the stored
value differs from its own name — so an emulator that still held the household would have
pulled the blank, noticed the disagreement, and put TEST-EMULATOR straight back on the
next sync. The row was the symptom; a joined test device is the cause.

The AVD itself is intact. Rejoin it by QR when you next need a second device — and expect
a *new* member row, under a new uid, to clean up the same way afterwards.

## 7. The emulator: does a restore actually work?

New in 5.14. Most of it has now been run on your phone — the backup itself, the file
picker, the confirmation dialog, both refusals, and a restore carried through to its
report. What has **never** happened on any device is a restore that actually inserts a
row, because the only copy of six months of spending is not where a write path should run
for the first time.

**Do this on the `ours-api36` AVD, not on your phone.** That is the whole point of the
step: to make the first insert happen somewhere a wrong answer costs nothing.

> Three defects turned up in the on-phone session that the tests had missed: a rejection
> that pasted the parser's message and a quotation of the chosen file into the interface,
> a missing full stop that ran two sentences together, and an offer to sync a restore that
> had changed nothing. Fixed in 5.16 and pinned by tests. Expect the insert path to have
> its own.

On your phone: **Settings ▸ This app ▸ Backup & restore ▸ Back up everything**, and send
the file somewhere the emulator can reach it — email it to yourself, or `adb pull` it out
of the share target and `adb push` it to the AVD's Download folder.

On the emulator, install 5.16, onboard it as its **own** household (do *not* join yours —
see step 6 for what a joined test device costs), then **Restore from a file** and pick it.

**Check, in order:**

1. The confirmation names the date, the build and the counts before anything is written.
2. It reports what it restored, and the entries appear in Activity with their categories.
3. **Run it a second time.** It must say everything was already there and change nothing.
   That is the property the whole design rests on, and it is the one worth seeing fail.
4. Point it at a file that is not a backup — any JPEG. It should say *"That file could
   not be read — this is not an Ours backup"* and nothing more. (Verified on the phone in
   5.16; listed so a regression is caught.)

If step 3 duplicates the history, stop and report it: that is the one outcome that would
make the feature worse than not having it.

---

## What to report back

- Step 2: **already answered** — 5.14 on your phone shows *Household · Manuel, Beula*,
  so she is in and always was. Nothing to do.
- Step 3: whether the two phones agree, and on what they don't
- Step 5: whether Bluetooth exchanged anything at all
- Step 7: whether a second restore is genuinely a no-op
