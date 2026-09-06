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

## Relationship to Vscan

Vscan remains a separate private scanner/security-engine project. Guardian does **not** expose or copy the private Vscan source tree. Shared security capabilities should move through a deliberately extracted module or documented interface.

## Android baseline

- Kotlin 2.0.21
- Java 17
- minSdk 26
- target/compile SDK 36
- AGP 8.10.1
- Gradle 8.11.1
- Application ID: `com.guardianlayer.app`

## Current milestone: 0.2.0-alpha

Guardian now has two real VPN protection modes:

### Emergency Lock Down

Lock Down routes device traffic into Guardian's local VPN and discards it. Guardian itself is excluded so the user can always return to the control app and stop protection.

### Selective per-app firewall

The user can mark visible launcher apps as **BLOCKED**. When the Smart Firewall is active, only those selected packages are attached to Guardian's VPN via Android `VpnService.Builder.addAllowedApplication(...)`. Their packets are discarded, while unselected apps remain on Android's normal network route.

This provides genuine per-app internet blocking without pretending Guardian already has a full userspace TCP/UDP forwarder.

Current 0.2 behavior:

- Per-app BLOCK / ALLOW rules stored locally
- Start / stop Smart Firewall independently of Lock Down
- Rules can be changed while the firewall is running and are reapplied immediately
- Foreground notification reports which protection mode is active
- Guardian Timeline records rule changes and firewall state
- Emergency Restore Network still stops any Guardian VPN mode
- Stable debug signing allows future CI debug APKs to update the installed test build

## What 0.2 does not claim yet

Selective blocking is not the same thing as full connection monitoring. Guardian does not yet forward allowed traffic, inspect every connection, attribute destinations to apps, or perform tracker/domain filtering. Those require the next networking layer and will be added deliberately rather than mocked.

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
