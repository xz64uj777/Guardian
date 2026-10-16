# Guardian

Guardian is a native Android privacy and security product built around one question:

> What is my device doing right now, and should I care?

Guardian turns technical device and network behavior into plain-English explanations and gives the user meaningful controls rather than vague "all clear" messages.

## First product pillars

- Device and app security analysis
- Permission-risk explanations
- Local VPN/firewall foundation
- Real emergency **Lock Down** mode
- Real per-app network blocking
- Local behavior/event timeline
- Privacy exposure scoring
- Clear review states instead of unsupported malware certainty

## Relationship to Vscan and PhoneGuard

Vscan remains the canonical local APK/security-posture engine. Guardian should reuse carefully selected Android-compatible security capabilities through reviewed, deliberately adapted modules—not pull in an entire unrelated app or expose private repository code wholesale. PhoneGuard (allin1) is another source of implemented device checks and storage diagnostics. Any transferred code must retain its coverage limits and local-first privacy boundary.

## Android baseline

- Kotlin 2.0.21
- Java 17
- minSdk 26
- target/compile SDK 36
- AGP 8.10.1
- Gradle 8.11.1
- Application ID: `com.guardianlayer.app`

## Current baseline: 0.7.0-alpha

Guardian's existing 0.7.0-alpha baseline includes the following working protection capabilities:

### Emergency Lock Down

Lock Down routes device traffic into Guardian's local VPN and discards it. Guardian itself is excluded so the user can always return to the control app and stop protection.

### Selective per-app firewall

The user can mark visible launcher apps as **BLOCKED**. When the Smart Firewall is active, only those selected packages are attached to Guardian's VPN via Android `VpnService.Builder.addAllowedApplication(...)`. Their packets are discarded, while unselected apps remain on Android's normal network route.

This provides genuine per-app internet blocking without pretending Guardian already has a full userspace TCP/UDP forwarder.

Existing firewall behavior:

- Per-app BLOCK / ALLOW rules stored locally
- Start / stop Smart Firewall independently of Lock Down
- Rules can be changed while the firewall is running and are reapplied immediately
- Foreground notification reports which protection mode is active
- Guardian Timeline records rule changes and firewall state
- Emergency Restore Network still stops any Guardian VPN mode
- Stable debug signing allows future CI debug APKs to update the installed test build

## What 0.2 does not claim yet

Selective blocking is not the same thing as full connection monitoring. Current capabilities and coverage must be described from the implementation; Guardian does not claim to inspect every connection or prove that any app is malware-free.

## Privacy boundary

Guardian is local-first. Security events, firewall rules, and privacy-snapshot results remain on-device. No analytics SDK or remote scan upload is included.

## Build

Android Studio can import the repository directly. CI uses Gradle 8.11.1 and uploads the debug APK as a workflow artifact.

```bash
./gradlew :app:assembleDebug
```

## Product rule

Guardian must not imply it can prove a device or app is malware-free. Risk scores are review signals and explanations, not verdicts.

Copyright © 2026 Guardian. All rights reserved.


## Product direction: Guardian Family Hub

Guardian is being developed toward one family digital-protection app rather than several disconnected security utilities. The existing 0.7.0-alpha privacy/firewall foundation stays intact; family features are a new layer, not a replacement or a claim that device pairing already exists.

Planned product pillars:
- **Protect:** local app/APK review, privacy and tracker protection, device-posture checks, and understandable security findings.
- **Connect:** a household view of devices, with explicit invitations and approval before a device joins.
- **Coordinate:** share relevant warnings and request help without exposing every person's private activity by default.
- **Explain:** show the evidence behind each warning and provide practical next steps.
- **Respect privacy:** make what is shared, with whom, and why visible to every participant.

Remote family device linking, account/identity, roles, cross-device alerts, and parental safeguards are future work until implemented and tested. See [the product vision](docs/PRODUCT_VISION.md).

## Local APK review integration

The security-engine-integration branch adds a user-selected APK review screen. It reads APK metadata, computes SHA-256, inspects requested permissions and filename risk signals, and optionally compares the hash against a bundled offline list. It does not install or execute the APK. The current bundled hash list is intentionally empty pending a curated, verified, versioned threat feed, so known-threat matching is not active until that feed is supplied. Heuristic scores are review priorities, not malware verdicts.
