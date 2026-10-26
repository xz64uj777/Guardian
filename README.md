# Guardian

Guardian is a native Android family digital-protection app built around one goal:

> Bring family digital protection into one app.

It aims to make device and app activity understandable, provide practical privacy controls, and report only what Guardian can support with evidence. Guardian is still under active development; this README distinguishes implemented foundations from features that still need validation.

## Current development status

- **Current development build:** `0.7.6-alpha-debug`
- **Debug application ID:** `com.guardianlayer.app.debug` (`com.guardianlayer.app` base ID)
- **Version code:** `50`
- **CI status:** Unit tests and debug APK assembly passed for the current per-app-watch development branch.
- **Real-device status:** Installation and end-to-end behavior still require confirmation on a physical Android device. A successful CI build is not proof that the APK installs or that Chrome remains usable.
- **Per-app-watch work branch:** `fix/per-app-watch-window-baselines`
- **Related pull request:** [#6 — Preserve Guardian test windows when switching apps](https://github.com/xz64uj777/Guardian/pull/6)

This README reflects the current development status; it does not claim that every feature is complete or production-ready. Check the linked pull request and [GitHub Actions](https://github.com/xz64uj777/Guardian/actions) for current review and CI status.

## Current capabilities and work areas

### Tracker Shield and app activity

Guardian includes a Tracker Shield / app-activity workflow intended to show DNS activity that can be confidently attributed to an app, along with blocked/allowed decisions and tracker-domain signals.

The current development work addresses keeping a separate live-test baseline for each watched app when switching between apps such as Chrome (`com.android.chrome`) and Facebook (`com.facebook.katana`). Switching views should not silently replace one app's test window with another's.

Reports distinguish:

- **Right now / last 60 seconds** — recent activity in the current time window.
- **Live test window / This watch** — activity counted for the selected test/watch window.
- **Retained or recent exact history** — previously observed activity, not necessarily new traffic in the current watch.
- **Shared or unattributed traffic** — traffic Guardian cannot confidently assign to one app.

These categories are not interchangeable. Separate per-app windows do **not** by themselves prove perfect simultaneous app attribution. Chrome/Facebook switching, attribution accuracy, and browser usability still need real-device verification.

### VPN and blocking foundations

Guardian has a local VPN-based protection foundation and per-app protection controls. The exact behavior depends on the selected mode and current implementation; do not assume every menu item represents a complete production firewall. Full connection forwarding/inspection and robust tracker-domain filtering must not be claimed unless implemented and verified.

### Security and privacy principles

- Explain the evidence behind a signal instead of presenting a privacy signal as a malware verdict.
- DNS/tracker observations are not, by themselves, proof that an app is malicious.
- Local-first handling is the design goal for device activity and protection settings.
- Users must retain a clear way to stop Guardian protection and restore normal network access.
- Preserve existing installations and saved reports during testing; do not recommend uninstalling as routine troubleshooting when it could remove local data.

## Known limitations and validation still required

- The current per-app-watch changes passed automated unit tests and debug APK assembly, but still require installation and regression testing on a real phone.
- Verify that a new debug APK updates the existing debug installation. If Android reports **“App not installed,”** do not uninstall the current app until saved data and package/signing compatibility have been checked.
- Verify Chrome loads normally with Guardian enabled, including when tracker blocking is active. A build that makes Chrome slow or unable to load pages is a regression.
- Verify Chrome and Facebook maintain independent watch windows when switching repeatedly between them.
- Confirm reports clearly separate fresh traffic, retained history, and shared/unattributed DNS activity.
- Confirm saved test results survive navigation and reopening the relevant screens.
- Guardian is not a substitute for a full commercial antivirus engine and must not claim a device or app is malware-free based solely on its current signals.

## Android development setup

- Kotlin / Android Gradle project
- Java 17
- minSdk 26
- compileSdk 36
- targetSdk 36
- Gradle 8.11.1
- Android namespace/base application ID: `com.guardianlayer.app`

Build a debug APK locally with:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs unit tests, assembles the debug APK, and uploads it as a workflow artifact. Use the artifact from the specific successful run you intend to test; do not assume an older artifact contains current changes.

Debug builds use a stable debug-only signing configuration intended to support updates across CI builds. This debug signing key is not for production releases.

## Release and scope notes

Guardian is an evolving family digital-protection product, not yet a finished production release. Parent/child phone integration and additional family features must not be represented as completed until implemented and tested.

Vscan remains a separate private scanner/security-engine project. Guardian does not expose or copy the private Vscan source tree; shared capabilities should be integrated through a deliberately extracted module or documented interface.

## Product rule

**Report what Guardian can substantiate.** A tracker signal is a privacy indicator, not a malware finding; a successful build is not a successful device test; and a feature is not complete until its real behavior has been validated.

Copyright © 2026 Guardian. All rights reserved.
