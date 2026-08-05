---
name: ours-v2-design-approved
description: "The v2 \"printed statement\" design is approved and built across every screen; design/ours-mockup-v2.html is the spec and Statement.kt is it in Kotlin"
metadata:
  node_type: memory
  type: project
---

On 2026-08-03 the user approved the **v2 design** for Ours with "nice design do it", and
it was built the same day across **every** screen. The spec is
`design/ours-mockup-v2.html`; the spec-in-Kotlin is `ui/components/Statement.kt`.

**Why:** the governing idea is that a bank SMS *is* a printed line — fixed pitch, amount
flush right, hairline between entries — so the UI is built from that material rather
than dressed up as a wallet app. Amounts share one right-hand tabular column and carry
**no ₹ per row**, because the aligned column is itself the unit.

**How to apply:** reach for the element sheet (`Ruler`, `StatementEntry`,
`TransactionEntry`, `LabelOverValue`, `OursChip`, `StatePill`, `TapeHeader`,
`AccentButton`, `GhostButton`, `PrimaryAction`, `Meter`, `QuietEmpty`, `CategoryGrid`)
rather than inventing spacing or type sizes — there is no second design system left in
the tree, the v1 cards and charts were deleted. Any total, subtotal or headline goes
through `Money.whole`; only the transaction-detail screen shows paise.

Two README claims turned out to be false when checked against the code and were
corrected: swipe-to-delete and QR camera scanning are both fully built. Swipe-to-delete
was later **removed** at the user's request — it fired accidentally while scrolling.

Later mockups, all built: `design/ours-mockup-v4-filter.html` (category filter),
`design/ours-mockup-v5-settings.html` (settings, two variants — the alternative layout is
reachable only with developer mode on). Theme tone (Crisp/Soft) and six accent colours
live in `ui/theme/Color.kt`.

Related: [[partner-phone-android-16]]
