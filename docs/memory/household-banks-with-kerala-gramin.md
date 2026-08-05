---
name: household-banks-with-kerala-gramin
description: "The household's main bank is Kerala Gramin Bank (KGBANK header); salary is ~Rs 58,200 monthly, and sender coverage is the parser's real failure mode"
metadata:
  node_type: memory
  type: project
---

The primary account is with **Kerala Gramin Bank** — TRAI header `KGBANK`, with the
bank naming itself both "Kerala Gramin Bank" and "Kerala Grameena Bank". Secondary:
Federal Bank (`FEDBNK`) and ICICI (`ICICIT`/`ICICIO`). Salary lands monthly at roughly
₹58,200. **No SBI sender exists on this phone** — SBI messages the user asked about are
on the partner's phone, not this one.

**Why:** on 2026-08-03 the user reported "salary credit not showing". The cause was not
parsing at all — `KGBANK` was absent from `BankRules`, and sender matching is the *first*
rule in `SmsParser.parse()`, so all 466 of that bank's messages were discarded before an
amount was ever looked for. Adding the header and rescanning took the app from 179 to
429 transactions in one pass.

**How to apply:** when a user says "it isn't reading my messages", check sender coverage
*before* suspecting the extraction regexes — dump the phone's distinct sender headers
(`adb shell content query --uri content://sms/inbox --projection address`, addresses
only, no bodies) and run them through `BankRules.forSender`. A missing header is never a
few stray messages; a household banks with one bank, so it is that household's entire
history. `RegionalBankTest` now guards this.

KGB's credit SMS names no payer at all ("Your A/c XXXX3062 credited Rs.58200 Bal after
txn ... Msg Id ... Time ..."), so there is nothing in the message to extract. At the
user's request bare credits are now labelled with the receiving bank instead of "Unknown
payee"; the label is guarded by `BankRules.isBankName` so no merchant rule is ever
learned from it, and `relabelBareCredits()` repairs rows imported before the change.

Account details, as recorded in the app on 5 Aug 2026: Kerala Gramin ···3062, ₹500
minimum balance; Federal ···4657, ₹3,000 minimum; ICICI ···3008, zero-balance account.

Related: [[partner-phone-android-16]]
