# Done

What has actually shipped, reconstructed from `git log`. The open list is `AT-HOME.md`;
this is the other half of it.

Generated on **6 August 2026** from `78f4497`, covering **106 commits** from the first one
on 3 August 2026 to **5.20 (61)**. The commit that updates this file is necessarily not in
it — regenerate after a release and the previous one appears.

Each heading is the version the app carried *after* that commit — so the commits under a
heading are the work that release delivered. The quoted line is the note the phones showed
in the update prompt, taken from `release/version.json` at that commit; releases before 2.8
were published without one.

Five version numbers never existed as builds — **1.6, 1.7, 2.2, 2.6 and 5.5** — because a
single commit bumped past them. Nothing is missing; there was simply never an APK.

<details>
<summary><strong>Regenerating this file</strong></summary>

```bash
for c in $(git rev-list --reverse HEAD); do
  v=$(git show $c:app/build.gradle.kts | grep -m1 versionName | sed 's/.*"\(.*\)".*/\1/')
  n=$(git show $c:app/build.gradle.kts | grep -m1 versionCode | sed 's/[^0-9]*//g')
  printf '%s|%s|%s|%s|%s\n' "$v" "$n" \
    "$(git log -1 --format=%ad --date=short $c)" \
    "$(git rev-parse --short $c)" "$(git log -1 --format=%s $c)"
done
```

Reading the version out of `app/build.gradle.kts` at each commit is what makes the grouping
honest — there are no release tags in this repository to trust instead.
</details>

---

## 5.20 (61) — 6 Aug 2026

> Two icons that were saying the wrong thing: the rules list opened and closed with money
> up and down arrows, and an approvals queue with nothing in it drew the ledger's receipt.
> Both now use the mark that means what they do.

- `78f4497` Icons that mean what they draw

## 5.19 (60) — 6 Aug 2026

> The empty Trash screen now shows a wastebasket rather than the ledger's receipt icon —
> the same glyph as the delete button that sends things there.

- `6664ee8` The empty bin draws a bin

## 5.18 (59) — 6 Aug 2026

> Trash: deleted entries wait 30 days and can be put back, one at a time or several at
> once. Deleting a single entry now offers an Undo, and both confirmations stop claiming a
> delete is forever.

- `dcb35ae` Trash as a statement, not a stack of cards
- `21bc4e3` Undo on a single delete, and a Trash that holds thirty days

5.17 (58) carried the same feature and was never pushed — it was installed to watch the
schema migration run, and the redesign landed before it left this machine.

## 5.16 (57) — 6 Aug 2026

> Backup and restore: the whole history in one file, so a lost phone stops meaning lost
> manual entries and lost category corrections. Settings, This app, Backup & restore.

- `66c2c8d` Three restore defects the tests could not see
- `fb10c9d` A backup that can actually be restored

5.14 (55) shipped the feature; 5.15 (56) was built and installed to check a fix on the
phone but never pushed, so no handset was offered it. 5.16 is the one to have.

## 5.13 (54) — 6 Aug 2026

> A launcher icon that sits on white at the size of every other icon on the home screen —
> the handshake was running past the mask edge and losing its wrist and fingertips to it.

- `1187263` Put the icon on white
- `9301056` Size the launcher icon against the icons beside it

**5.12 (53) was built and installed but never pushed** — the white background arrived
before it left this machine, so the two are one release.

## 5.11 (52) — 6 Aug 2026

> Re-upload now says what it leaves behind — retired months are counted and dated instead
> of silently skipped, and a re-upload with nothing in scope no longer looks like a broken
> sheet.

- `b903f2c` Say what a re-upload leaves behind

## 5.10 (51) — 5 Aug 2026

> Deleting an entry now asks first, and says which entry and what happens to it — a member
> sees that the owner has to agree.

- `9e012a2` Ask before a delete, and say which entry it is

## 5.9 (50) — 5 Aug 2026

> Amounts show paise, and every total agrees with the rows beneath it. Activity and Budgets
> get back the space they were losing, and a delete from an entry now says it is waiting on
> the owner.

- `1e6d696` Totals that agree with the rows under them, and the space four screens were losing

## 5.8 (49) — 5 Aug 2026

> Budgets can be cleared one at a time or reset together, a zero balance can finally be
> typed in, and the add button floats again.

- `e424150` Budgets you can drop, a balance that can be zero, and the button back over the page

## 5.7 (48) — 5 Aug 2026

> The launcher icon is now the colour handshake when themed icons are off. Themed icons keep
> the monochrome clasp.

- `d85bb50` Colour handshake as the launcher icon, monochrome kept for themed
- `3014fcd` Notes, memories and a handover, for picking this up on another machine

## 5.6 (47) — 5 Aug 2026

> Household members now sync, and a budget set before this feature existed is published
> rather than silently ignored. Found by putting a second device on the sheet and looking.

- `3506768` Publish a budget that predates rule-syncing, and allow a member to leave

## 5.4 (45) — 5 Aug 2026

> Household membership now syncs. A partner who has joined but not yet spent anything was
> indistinguishable from no partner at all — the members table only ever held the person
> holding the phone.

- `db30780` Let the household know who is in it

## 5.3 (44) — 5 Aug 2026

> Where it went now lists every category instead of the top six — it was silently dropping
> the smallest one, so the bars added up to less than Spent. Also corrects the Not counted
> caption, which described a card-bill rule the app no longer follows.

- `820a545` Where it went: show every category, and stop explaining a rule we dropped

## 5.2 (43) — 5 Aug 2026

> The budget and your bank balance now meet: a 'Safe to spend' figure that is the smaller of
> what the budget allows and what the accounts actually hold. Also fixes the budget never
> syncing to the other phone, and account balances never being pushed at all.

- `49d1204` Make the budget and the bank balance the same conversation

## 5.1 (42) — 5 Aug 2026

> Fixes the white flash on launch — the window behind the app had no dark value, and in dark
> mode the splash settings were being ignored entirely.

- `971c276` Fix the white splash: a missing night colour and a qualifier trap

## 5.0 (41) — 5 Aug 2026

> The splash mark is smaller now, and settles into place instead of appearing.

- `dcf33c5` A smaller splash mark, and a settle rather than an entrance

## 4.9 (40) — 5 Aug 2026

> Soft mode now genuinely dims the light theme rather than only warming it, and the icon
> drops its blue for the app's own ink.

- `7fe1f82` Light Soft actually dims, and the icon loses its blue

## 4.8 (39) — 5 Aug 2026

> A softer contrast setting for long sittings, and six accent colours. Settings, This app,
> Appearance.

- `e1527ba` A softer contrast, and an accent you can choose

## 4.7 (38) — 5 Aug 2026

> You can now add an account by hand, before any payment has gone through it.

- `280fd99` Add an account by hand, before a payment has touched it

## 4.6 (37) — 5 Aug 2026

> Each of you now sees your own account balances; the household owner sees them all. Settings
> opens as an index.

- `b6bb8cd` Each person sees their own accounts; the owner sees all of them

## 4.5 (36) — 5 Aug 2026

> Account balances are now shown only to the household owner.

- `82611e9` Show account balances only to the household owner

## 4.4 (35) — 5 Aug 2026

> What is left now takes off each account's minimum balance, so the figure is what you can
> actually spend.

- `a659814` Minimum balances, so "what is left" means what you can spend

## 4.3 (34) — 5 Aug 2026

> You can now set a balance by hand for accounts your bank never quotes one for. It is marked
> as yours, and the bank overrules it automatically.

- `4c2663a` Balances you can set by hand, marked as yours

## 4.2 (33) — 5 Aug 2026

> Summary now shows what left your accounts, and what the bank last said was left in them.

- `cbe6b37` Summary: what left the accounts, and what is left in them

## 4.1 (32) — 5 Aug 2026

> New icon.

- `848d827` The clasp, as the launcher icon

## 4.0 (31) — 5 Aug 2026

> Every category picker now offers all sixteen categories — the entry screen and the add
> sheet were each hiding some.

- `8cc689b` Every picker offers all sixteen categories
- `c25bd05` Fifteen more logo studies, three of them hands
- `62d8d23` A mark cut from the app instead of drawn to resemble it

## 3.9 (30) — 5 Aug 2026

> The add button no longer sits on top of an amount.

- `ad207b4` Stop the add button covering an amount

## 3.8 (29) — 5 Aug 2026

> Sort now picks a category from the same grid every other screen uses.

- `8fd3c36` Sort picks a category from the same grid as everywhere else

## 3.7 (28) — 5 Aug 2026

> Every category now has one name and one coloured icon, the same on every screen.

- `d07dc04` One name and one coloured mark per category, everywhere

## 3.6 (27) — 5 Aug 2026

> Swiping an entry no longer deletes or categorises it. Scrolling the list is just scrolling
> now.

- `320c330` Remove swipe-to-delete and swipe-to-categorise from Activity

## 3.5 (26) — 5 Aug 2026

> Both Settings layouts are here to stay. The switch between them now lives in Developer.

- `9637c21` Keep both Settings layouts; hide the switch behind developer mode

## 3.4 (25) — 5 Aug 2026

> Settings can now be drawn two ways — one long page, or an index of five pages. Pick one
> under Settings layout; the other will be removed.

- `bc402af` Build the index layout for Settings, alongside the one-page one

## 3.3 (24) — 5 Aug 2026

> Settings now matches the design: one panel per group, status on every heading, and captions
> that read as sentences instead of shouting.

- `3440891` Settings, drawn the way the mockup draws it

## 3.2 (23) — 5 Aug 2026

> Settings is five groups instead of eleven sections, and anything that is silently switched
> off now says so at the top.

- `dd7a2a0` Settings as five groups, and permissions you can actually see

## 3.1 (22) — 4 Aug 2026

> A payment can now open the capture prompt over whatever you are doing, so you can name and
> categorise it without opening Ours. Turn it on in Settings.

- `fc2c93f` Capture prompt over other apps, and stop showing a blank entry
- `587bede` Mockup: Settings as five groups instead of eleven sections

## 3.0 (21) — 4 Aug 2026

> The new-expense notification now actually appears — it was being discarded by Android
> before anything was drawn.

- `f3e9cbe` Make the expense notification actually appear

## 2.9 (20) — 4 Aug 2026

> The Activity filter now lists only the categories your month actually contains, biggest
> first, with counts — and Untagged is finally one of them.

- `76d7d92` Filter Activity by what the month contains, not by the enum

## 2.8 (19) — 4 Aug 2026

> Adding an expense by hand now looks the way the design draws it — amount first, everything
> else optional.

- `b269451` Draw the new-expense sheet the way the mockup draws it
- `745436e` Mockup: four ways out of the eighteen-chip filter strip

## 2.7 (18) — 4 Aug 2026

- `bd0bba7` Draw the category picker the way the mockup draws it

## 2.5 (16) — 4 Aug 2026

- `921b9a9` Make adding by hand match capturing

## 2.4 (15) — 4 Aug 2026

- `8ddc861` Capture a payment when it happens, and show every category
- `2ed39eb` Six logo studies, judged at the size that matters

## 2.3 (14) — 4 Aug 2026

- `d47f31d` Backfill named accounts onto history, and stop losing them
- `46e1f8c` Add the at-home checklist
- `06187a8` Mock up capture, the category grid and the note

## 2.1 (12) — 4 Aug 2026

- `1c3fd3e` Let the household name the accounts its bank refuses to

## 2.0 (11) — 4 Aug 2026

- `2dbdfa6` Keep the tabs on a transaction

## 1.9 (10) — 4 Aug 2026

- `5915d42` Tell a round trip apart from a transfer
- `7821045` Write down what counts as spending

## 1.8 (9) — 4 Aug 2026

- `bbef0e2` Collapse a card bill's two halves, and its meridiem twins

## 1.5 (6) — 4 Aug 2026

- `ab9a7f9` Stop reserving room for a button that isn't there

## 1.4 (5) — 4 Aug 2026

- `c773c78` Group the About panel instead of scattering it

## 1.3 (4) — 4 Aug 2026

- `2ca8a38` Fix the manifest the updater reads, and show it working

## 1.2 (3) — 4 Aug 2026

- `66052ab` Say what the unlock is waiting for

## 1.1 (2) — 4 Aug 2026

- `8908f1e` Status bar follows the app's theme, and ownership can be declared

## 1.0 (1) — 3–4 Aug 2026

The app before it could update itself: 37 commits, all shipping under the same version code,
so there is no finer grouping to recover. Oldest first.

**The sheet and the sync log**

- `21e030d` Ours: SMS expense tracker with two-phone sync
- `c52361a` Sheet setup walkthrough, in the app
- `c76a782` Strip the raw bank message before it reaches the sheet
- `62c334a` Add an explicit re-upload for a recreated or cleared sheet
- `f5b96f0` Fix compaction deleting events it had decided to keep
- `b50a2ee` Report what a sync actually moved
- `3245959` Sync respects the tracking cutoff, and re-upload clears the sheet first
- `bd79a40` Remove the shared-folder transport
- `81a3c20` Push the backlog in batches so a large re-upload can finish
- `ee74ebd` Say when the sheet's script is out of date
- `bb3859d` Two phones have now exchanged a log
- `019d2f2` Teach rules through the sheet, and stop the database from wiping itself

**The household**

- `ae6fa84` Derive the household id from the invite secret
- `17090df` Support a household of more than two
- `3076d3e` Keep nearby sync running, and migrate pre-derivation households

**Reading the messages**

- `a8ebb2f` An account number is not a payee
- `7bdd44f` Count transfers and card bills as spending
- `d453072` Stop losing evening transactions, and stop duplicating paired messages
- `953b607` Detect recurring charges
- `b99c539` Make the Notifications source actually work
- `2e9b034` Actually ask for notification permission, and stop lying in Settings

**The screens**

- `ecc9f10` Match three screen details the mockup specifies
- `ccf638f` Match the rendered mockup: ticks, rules, chips, and an always-present ruler
- `51da8eb` Short category names in entry captions
- `45a7927` Ship the mono face, fix the Summary bars, give Sort a way in
- `733e61d` Float the add button bottom-right
- `510afa4` Bulk multi-select on Activity
- `db1ca95` Rename a payee, and let the owner decide on deletions
- `7221a47` About, and a locked door in front of editing an amount
- `aefb5a8` Keep the widget in step with the data

**Shipping it**

- `65b6b58` Update itself, from its own repository
- `d9e5d38` Publish the release APK the update manifest points at
- `f31059c` Serve the update APK from a URL that actually works

**The README, kept honest**

- `c466b18` README: correct claims that no longer hold
- `36a1409` README: raw SMS bodies are synced, and the sheet stores them in the clear
- `5977a6c` README: two of the three "known gaps" were built and never crossed off
- `23961bf` Bring the README back in line with the app
