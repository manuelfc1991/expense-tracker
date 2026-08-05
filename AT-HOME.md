# At home: the list

Everything left that needs the other phone, or knowledge only you have.

Last reviewed **5 August 2026**, against **5.7 (48)**. Earlier versions of this file
described a 2.3 release and a categorising job that is long finished; that work is done
and has been removed rather than left to mislead.

---

## 1. Her phone: update to 5.7

**Settings ▸ Updates ▸ Check for updates** → Download → Install.

Everything below depends on this. Her phone has never run a build that can sync a
budget, an account balance, or the fact that she exists — all three were write-only
until 5.5, and membership until 5.4.

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

---

## What to report back

- Step 2: how many names Household lists
- Step 3: whether the two phones agree, and on what they don't
- Step 5: whether Bluetooth exchanged anything at all
