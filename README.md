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

## Current milestone: 0.7.2-alpha

Installable test build. Not a Play Store release. The debug APK is signed with the repo's debug key so later CI builds can update the same test install. Package id: `com.guardianlayer.app.debug`.

### Emergency Lock Down

Lock Down routes device traffic into Guardian's local VPN and discards it. Guardian itself is excluded so the user can always return to the control app and stop protection.

### Smart Firewall

Mark launcher apps **BLOCKED**. Only those packages are attached to the VPN, and their packets are discarded. Other apps stay on Android's normal route. This is real per-app blocking, not a full userspace TCP/UDP forwarder.

### Tracker Shield

Mark apps **SHIELDED**. Guardian filters ordinary DNS for those apps and leaves their other traffic online. Encrypted DNS, cached destinations, and direct-IP traffic can bypass it. A shared session is reported as shared. Guardian does not invent a per-app source when the tunnel carries more than one app.

### Exact Watch

Watching one app keeps the saved Shielded set and temporarily routes only that app through Tracker Shield, so DNS from the test can be attributed to it. Restore puts every saved Shielded app back on the VPN. Hiding the live panel does not restart the test.

## What 0.7.2 does not claim

Guardian does not prove a device or app is malware-free. It does not inspect every connection, attribute destinations inside a multi-app tunnel, or ship a threat-hash scanner. Risk notes are review signals, not verdicts.

## Privacy boundary

Guardian is local-first. Security events, firewall rules, and privacy-snapshot results remain on-device. No analytics SDK or remote scan upload is included.

## Install the test build

Android Studio can import the repository directly. CI on `main` and `release/**` runs the unit tests and uploads `guardian-debug-apk`.

```bash
./gradlew :app:assembleDebug
```

Sideload that debug APK on a device running Android 8 or newer. It will not replace a Play-signed Guardian install, because the test id ends in `.debug`. This debug key must never be used for a production release.

## Product rule

Guardian must not imply it can prove a device or app is malware-free. Risk scores are review signals and explanations, not verdicts.

Copyright © 2026 Guardian. All rights reserved.
