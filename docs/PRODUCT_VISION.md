# Guardian product vision

## North star

**Bring family digital protection together in one app.** Guardian should answer: "What is happening on our devices, what deserves attention, and how can we help each other safely?"

This is a direction being reconstructed from the existing project and the owner's stated intent—not a claim that the original full specification has been recovered.

## Preserve the existing foundation

Guardian 0.7.0-alpha remains the baseline. Keep its local-first VPN/firewall controls, Tracker Shield, per-app event history, saved exact-watch investigations, and plain-English evidence explanations. Do not rewrite the working protection core just to make room for family features.

## Product pillars

1. **Protect:** app and APK review, privacy exposure, tracker blocking, device-posture signals, and clear security guidance.
2. **Connect:** household setup and invitation-based device linking, with explicit acceptance and revocation.
3. **Coordinate:** family alerts, help requests, and agreed safeguards that communicate only what is necessary.
4. **Explain:** every warning should state what was observed, why it matters, what Guardian cannot know, and what action the person can take.
5. **Respect privacy:** make sharing transparent and consent-based. Family membership must not silently grant unrestricted access to another person's app activity, messages, files, or browsing.

## Proposed delivery order

- Strengthen local security review and remediation on one device.
- Add a local-only family hub prototype with clearly marked sample/demo state if backend identity is not ready.
- Design secure household invitations, device identity, role/age boundaries, and revocation before implementing remote links.
- Add opt-in family alerts with minimal disclosure and clear per-alert sharing rules.
- Add parental/guardian safeguards only where Android permits them, with explicit setup, visible state, and a recovery path.
- Test multi-device flows and privacy boundaries before describing them as available.

## Product rules

- Never call a permission combination, tracker request, filename pattern, or risk score proof of malware.
- Never imply a full-device scan covers Android private app data, root-only areas, or all runtime behavior.
- Never claim a device is linked, protected, or monitored remotely unless the implementation has verified that state.
- Preserve user control and recovery options; do not trap a family member in an app or silently take device-admin powers.
- Keep sensitive scan results on-device unless the user explicitly enables a clearly described sharing action.

## Reuse policy

Vscan is the canonical source for Android APK and security-posture analysis. PhoneGuard (allin1) contains useful app-permission, device-state, shared-storage, and diagnostic work. Reuse should be selective and reviewed for package names, permissions, dependencies, privacy behavior, and Android limitations. Windows-only scanner features are not portable to Android without a real Android equivalent.

## Current status

Guardian Family Hub is the product direction, not an already shipped feature set. The first security-engine integration on the security-engine-integration branch adds local, user-selected APK review. Household linking, remote family alerts, and centralized family management remain future implementation work.
