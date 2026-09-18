# Existing product baseline

These are reported historical characteristics from the supplied conversation. Confirm against the current checkout before making public claims.

## Workflow

Personal dashboard selects an APK and reviews static findings. A managed Work Profile hosts the target app and runtime monitoring. Android installation and removal confirmations are part of the user flow. Opening the target and opening Live Monitor are separate actions. Ending a session clears target data through the supported lifecycle, requests removal, imports artifacts, and produces a report. Do not claim the entire Work Profile is recreated for each session.

## Evidence model

DeclaredCapability: APK manifest and package inspection describes declared capabilities.
ObservedBehavior: Work Profile VPN observations describe traffic actually observed and policy outcomes.
AndroidEvidence: DevicePolicyManager network events provide independent OS evidence, including package attribution where available.
Risk findings: deterministic inferences based on evidence; never present inference as direct observation.

DPM delivery is asynchronous. The VPN does not produce DPM evidence. No direct kernel telemetry collection is claimed.

## Static analysis

Reported fields include metadata, permissions, components, signing information, SDK settings, network security declarations, native libraries, and APK hash. Do not claim DEX parsing, decompilation, taint analysis, or source analysis without confirming implementation. Deeper DEX inspection is future work.

## Network baseline

Existing userspace forwarding lives behind a Work Profile VpnService. The historical baseline observed supported connection metadata and explicit DNS; it did not decrypt HTTPS. Encrypted DNS limits VPN hostname visibility. A target requiring its own active VPN can conflict with the monitor in the same profile. Historical IPv6 behavior was blocking rather than forwarding; confirm locally.

The POC adds a distinct optional inspection mode. Documentation must separate ordinary monitoring from enabled POC interception, rather than globally claiming either no TLS interception or universal HTTPS decoding.

## Isolation limits

A Managed Work Profile shares the host Android kernel. It is not a VM or emulator and does not guarantee containment. Private Space is distinct. Provisioning depends on device and policy support; another corporate Work Profile may prevent provisioning.

## Risk and privacy

Historical ranges: 0 to 24 Low; 25 to 49 Moderate; 50 to 74 High; 75 to 100 Critical. Historical rule versions: static-v1, runtime-v1, combined-v1. Verify current definitions; do not change scoring during the POC.

The risk score is not a malware probability. A harmless fixture intentionally exercising a private destination probe can produce an elevated score.

Analysis was described as local, but target APKs may communicate with external servers. Do not say no data leaves the device. The inspection POC uses dummy data and adds sensitive capture handling obligations.

## UX and repository

Personal uses blue and Sandbox uses teal, with explicit profile labels. Keep the current distinction and do not redesign the app for this POC.

The agent report places Android modules beneath  and repository docs at the root. Earlier records used a different directory. Resolve actual paths; do not hardcode either assumption or a developer workstation path.

Use the Gradle wrapper and toolchain recorded in the checkout. Historical toolchain statements and build claims are not authoritative. An unsigned release build is not an official published binary.
