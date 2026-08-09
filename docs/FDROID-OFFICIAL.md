# Getting into F-Droid's own repository

Scoped 9 August 2026, not started. This is what it would take and what it would cost —
written down so the decision can be made once rather than re-derived.

The app already has **its own** F-Droid repository (README tutorial 5), and that works
today. This document is about the *official* one: the store a person finds by searching,
having added nothing.

---

## Where it stands

| Requirement | Status |
|---|---|
| FLOSS licence | ✅ GPL-3.0-only, since 9 Aug 2026 |
| No AI-generated-code prohibition | ✅ F-Droid has no such policy (IzzyOnDroid does, which is why that route is closed) |
| Source publicly buildable | ✅ a clone builds; only signing files are absent |
| Keeps our signing key | ✅ possible, but only via reproducible builds — see below |
| **No Google Play Services** | ❌ **`play-services-nearby`. The one blocker.** |

F-Droid's inclusion policy names Google Play Services explicitly as forbidden. Nothing
about the licence or the tooling stands in the way any more; this single dependency does.

## The signing problem, and the way round it

F-Droid builds from source and signs with **its own** key by default. An APK signed with
anything other than `ours-release.jks` cannot install over the live database on either
phone — `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way past it is the uninstall
that destroys manual entries and hand-made corrections.

Their **reproducible builds** mode avoids this. Two metadata fields:

- `Binaries:` — a URL where our own signed APK can be fetched
- `AllowedAPKSigningKeys:` — `c618446939df65fa5ad19a854914a9e7ee397c0a376bf1801c883728f8178ae2`

F-Droid then builds from source, compares against our binary, and on a match publishes
**our** APK. Their documentation is explicit that in this mode F-Droid never publishes an
APK it signed itself. So the household's phones could install from the official store.

It also means the release APK has to be **byte-for-byte reproducible** on their build
server. That is the genuinely uncertain part of this whole exercise; everything else is
mechanical. `Binaries:` also wants a stable URL — GitHub Releases, rather than the APK
committed into `release/`.

---

## The split itself — smaller than it looks

Every `com.google.android.gms` import in the app is in **one file**.

```
app/src/main/java/com/manuel/ours/data/sync/NearbyTransport.kt   299 lines, 13 gms imports
```

Nothing else touches the library. `SyncTransport` is a clean interface over `SyncEvent`,
and `SyncEngine`, `LogMerger` and every convergence test are defined on the event set
alone — they do not know a transport exists. The `findNearby` in `Daos.kt` is deduplication
and unrelated; the other matches across the tree are prose in comments.

So the work is not an extraction. It is a substitution.

### What moves, in full

| File | Change |
|---|---|
| `data/sync/NearbyTransport.kt` | → `src/full/java/…`, unchanged |
| `data/sync/NearbySyncService.kt` | → `src/full/java/…`, unchanged (83 lines) |
| new `src/fdroid/java/…/NearbyTransport.kt` | stub: `isAvailable()` false, `push`/`pull` no-ops, `requiredPermissions()` empty |
| `work/SyncWorker.kt` | none — injects `NearbyTransport`, and the stub answers `isAvailable() = false`, so the branch simply never runs |
| `OursApp.kt` | one `NearbySyncService.start` call, behind `BuildConfig.HAS_NEARBY` |
| `ui/screens/settings/SettingsViewModel.kt` | one call at line ~520, same gate |
| `ui/screens/settings/SettingsScreen.kt` | the "Keep syncing when nearby" block (~lines 301–430), the status string at ~1011, and the `bluetooth`/`nearby` search keywords at ~1246 |
| `src/fdroid/AndroidManifest.xml` | `tools:node="remove"` for nine Bluetooth/location/Wi-Fi permissions, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and the service declaration |
| `app/build.gradle.kts` | `flavorDimensions`, two flavours, `fullImplementation(libs.play.services.nearby)`, `buildConfigField` for `HAS_NEARBY` |

`AppPrefs.NEARBY_ALWAYS` can stay. It is one unread boolean, and removing a DataStore key
is a migration for no gain.

The stub answering `isAvailable() = false` is what makes this cheap: `SyncWorker` already
asks every transport whether it is usable before touching it, so the F-Droid build degrades
along a path the code already has and the tests already cover.

### Roughly

Half a day for the split. The unknown is reproducibility, which could be an afternoon or
could be a week, and F-Droid's review queue is slow regardless.

---

## The part worth thinking about before any of it

Two flavours means **two different apps with the same name, same icon and the same
`applicationId`**, differing in a feature that is invisible until someone needs it. A phone
that installs from the official store gets sheet sync only. A phone that installs from our
own repo gets Bluetooth as well. Both say "Ours 7.6". Nothing on either screen explains
which one is in front of you, and the household would be running the variant that the
public one is not.

That is a support problem we would be manufacturing for ourselves.

The honest alternative is to **drop Nearby entirely** and ship one build everywhere. What
Bluetooth actually buys is syncing with no internet and nothing written to the sheet —
worth something, but it has never been the path the household relies on, and sheet sync
carries shared rules while Bluetooth carries only transactions. One app that behaves the
same everywhere may be worth more than a transport used occasionally.

**Recommendation:** do not start the split until that question is answered. If Bluetooth
stays, the two-variant confusion is the real cost of official F-Droid, not the code. If it
goes, the blocker disappears, there are no flavours to maintain, and what is left is the
reproducible-build work alone.
