---
name: partner-phone-android-16
description: The second phone this app syncs to runs Android 16 (API 36); app currently targets SDK 34
metadata:
  node_type: memory
  type: project
---

Ours is sideloaded to exactly two phones, and the partner's phone runs **Android 16
(API 36)**. The app is built with `compileSdk = 34` / `targetSdk = 34`.

**Why:** stated by the user on 2026-08-03 as a constraint on the build, right after
approving the v2 design. It bounds what the sync and permission paths have to survive
in the real world — the two-phone pair is not homogeneous.

**How to apply:** targetSdk 34 still installs and runs on Android 16, so this is not a
blocker. But check anything permission- or transport-shaped against API 36 behaviour
before claiming it works on both phones: Nearby Connections needs the Android 13+
`BLUETOOTH_SCAN`/`BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT` + `NEARBY_WIFI_DEVICES`
runtime grants, and edge-to-edge is only forced at targetSdk 36. Do not bump targetSdk
without asking — `READ_SMS` and the notification-listener paths are the fragile ones.

A headless emulator is a usable stand-in for the second phone and is the only way to
catch sync bugs that cannot be seen from one device — two of them were found that way on
5 Aug 2026. Launch with
`emulator -avd <name> -no-window -gpu swiftshader_indirect -no-snapshot`; the windowed
mode segfaults on this machine's graphics stack.

Related: [[ours-v2-design-approved]]
