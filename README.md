# Guardian

Guardian is a native Android privacy and security product built around one question:

> What is my device doing right now, and should I care?

Guardian turns technical device and network behavior into plain-English explanations and gives the user meaningful controls rather than vague "all clear" messages.

## First product pillars

- Device and app security analysis
- Permission-risk explanations
- Local VPN/firewall foundation
- Real emergency **Lock Down** mode
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

## Current milestone

`0.1.0-alpha` establishes the Guardian product foundation:

- User-authorized Android `VpnService`
- IPv4/IPv6 Lock Down routing
- Packet-drop kill-switch behavior while Lock Down is active
- Persistent local Guardian event timeline
- Launcher-app privacy permission snapshot
- Foreground-service notification and stop action
- CI debug APK build

### Lock Down behavior

Lock Down is intentionally strict. Once the user grants Android VPN permission and activates it, Guardian routes device traffic into its local VPN interface and discards that traffic until Lock Down is stopped. Guardian itself is excluded so the control app remains usable.

This is the first firewall primitive, not the final per-app firewall. Selective forwarding, per-app allow/block rules, destination intelligence, DNS/tracker classification, and network history are later milestones.

## Privacy boundary

Guardian is local-first. Security events and privacy-snapshot results in this milestone remain on-device. No analytics SDK or remote scan upload is included.

## Build

Android Studio can import the repository directly. CI uses Gradle 8.11.1 and uploads the debug APK as a workflow artifact.

The repository bootstrap workflow generates Guardian's own Gradle wrapper after the first project commit.

```bash
./gradlew :app:assembleDebug
```

## Product rule

Guardian must not imply it can prove a device or app is malware-free. Risk scores are review signals and explanations, not verdicts.

Copyright © 2026 Guardian. All rights reserved.
