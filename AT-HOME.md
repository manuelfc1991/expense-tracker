# At home: the list

Everything left needs both phones, or knowledge only you have. Roughly 30 minutes.

Work top to bottom — later steps depend on earlier ones.

---

## 1. Her phone: get SMS working again

**Why first:** while 5G-only is on, her bank messages never arrive. The app records
nothing and shows no error, so it looks like she simply spent nothing.

1. **Settings ▸ Network & internet ▸ SIMs ▸ [her SIM]**
2. **Preferred network type** → **5G/4G/3G/2G (auto)** — not "5G only"
3. **VoLTE / 4G Calling** → **on**
4. Toggle **Airplane mode** on and off to force re-registration

**Check it worked:** send her a text from your phone. If it arrives, so will the banks'.

> Anything her network dropped while 5G-only was on is gone — not queued. Only new
> messages will come through.

---

## 2. Her phone: update to 2.3

**Settings ▸ Updates ▸ Check for updates** → Download → Install.

If that fails, `Ours-2.3.apk` is in your Downloads — send it to her.

**Why it matters:** older builds don't know the `SELF_TRANSFER` category and will read
it as "Other", so transfers between you two would still count as spending on her side.

---

## 3. Her phone: rescan

**Settings ▸ Rescan messages.**

Re-reads her inbox in case bank texts arrived but weren't processed. Safe to run any
time — a rescan never duplicates.

---

## 4. Both phones: sync

**Settings ▸ Sync now**, on each.

Hers hasn't pushed since the sheet was reset. Yours has deletions and repairs queued.

**Check it worked:** the status line should say *"Sent N"* rather than *"Already up to
date"*. If it says **"Couldn't sync — …"**, read me the message.

---

## 5. Your phone: fix the five August rows

The app can't know what these were. Kerala Gramin words a payment to a mother, a
landlord, an FD and your own account identically — you're the only source.

**Activity ▸ 3 August:**

| Row | Change category to |
|---|---|
| **₹20,000** (20:31) | Savings & Investments |
| **₹10,000** (20:14) — to your wife | Between our accounts |
| **₹1,000** (20:04) — to your own low-balance account | Between our accounts |
| ₹7,000 (20:01) — to your father | leave as is |
| ₹1,000 (20:33) — to your mother | leave as is |

Also worth renaming, so next month reads plainly: tap the payee name, type *Father* /
*Mother* / *My FD* / *Wife*.

**Check it worked:** Home should drop from **₹59,272 to about ₹28,272**.

---

## 6. Your phone: name the accounts that carry history

This is the step that stops you doing step 5 every month.

168 of your rows now record which account they paid. Naming one relabels all of its
history at once, and every future payment to it arrives already named.

Worth naming first, by how much they carry:

```
...4657   ₹1,17,791  across  9 payments
...7853   ₹21,145    across  3
...0005   ₹19,651    across 15
...6613   ₹14,000    across  2
...0025   ₹4,734     across 18
```

**How:** open any payment to that account → tap the payee name → type the name → leave
**"Remember account ####"** switched on → Save.

The name syncs to her phone through the sheet, so neither of you names it twice.

> The five rows in step 5 are the exception: their destination account was lost when
> duplicate messages were merged, so there is nothing to name. Hand-categorising them
> once is the only way.

---

## 7. Her phone: match your months (optional)

You track from **1 August**; she still tracks everything, so she sees February onward
and you see August. Nothing is broken, but the two screens won't agree.

To match: **Settings ▸ Tracking ▸ Change date ▸ 1 August 2026** on her phone.

Nothing is deleted either way — moving the date back brings it all straight back.

---

## 8. The Bluetooth test

The one claim in this project never run on real hardware.

1. **Settings ▸ Bluetooth sync → on**, on both phones
2. Grant the permissions when asked, on both
3. Put the phones side by side, both apps open
4. Wait a minute

**Check it worked:** **Settings ▸ Sync now** should report *Bluetooth* as the transport
rather than *Sheet*, or the sync status should mention it.

If nothing happens, that is a real result and worth telling me — it means the Nearby
handshake fails on real devices, which is exactly what has never been established.

---

## What to report back

- Step 4: the exact sync status line on each phone
- Step 5: the new Home figure
- Step 8: whether Bluetooth exchanged anything

I can verify the rest from the sheet.
