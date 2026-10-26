# Guardian

Guardian is a native Android family digital-protection app built around one goal:

> Bring family digital protection into one app.

It aims to make device and app activity understandable, provide practical privacy controls, and report only what Guardian can support with evidence. Guardian is still under active development; this README distinguishes implemented foundations from features that still need validation.

## Current development status

- **Current development build:** `0.7.9-alpha-debug`
- **Debug application ID:** `com.guardianlayer.app.debug` (`com.guardianlayer.app` base ID)
- **Version code:** `53`
- **CI status:** Unit tests and debug APK assembly passed. Real-device testing confirmed Chrome remains usable and Chrome/Facebook stay Exact Live with separate activity in multi-app Tracker Shield.
- **Real-device status:** Verified on device (October 26, 2026). Chrome loads normally. Multi-app attribution holds for Chrome and Facebook. Live test window starts fresh and matches recent activity.
- **Active work:** Merged from `fix/per-app-watch-window-baselines` (PR #6).

This README reflects the current development status; it does not claim that every feature is complete or production-ready.

## Current capabilities and work areas

### Tracker Shield and app activity

Guardian includes a Tracker Shield / app-activity workflow that shows DNS activity attributed to an app, along with blocked/allowed decisions and tracker-domain signals.

- **Right now / last 60 seconds** — recent activity in the current time window.
- **Live test window / This watch** — activity counted for the selected test/watch window (starts fresh on open and includes the last 60 seconds).
- **Retained or recent exact history** — previously observed activity.
- **Shared or unattributed traffic** — traffic Guardian cannot confidently assign to one app.

### VPN and blocking foundations

Guardian has a local VPN-based protection foundation and per-app protection controls. The exact behavior depends on the selected mode and current implementation.

### Security and privacy principles

- Explain the evidence behind a signal instead of presenting a privacy signal as a malware verdict.
- DNS/tracker observations are not, by themselves, proof that an app is malicious.
- Local-first handling is the design goal for device activity and protection settings.
- Users must retain a clear way to stop Guardian protection and restore normal network access.

## Product rule

**Report what Guardian can substantiate.** A tracker signal is a privacy indicator, not a malware finding; a successful build is not a successful device test; and a feature is not complete until its real behavior has been validated.

Copyright © 2026 Guardian. All rights reserved.
