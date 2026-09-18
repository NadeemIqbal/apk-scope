# Product Vision

## Purpose

APK Scope is an Android application for understanding an APK before and while it runs. It combines local static inspection, controlled execution where Android permits it, network behavior analysis, and evidence driven reporting on devices without root.

The product should answer four questions clearly:

1. What does this APK declare or reference?
2. What did it actually do during this session?
3. Which observations support each finding?
4. What could the tool not observe or establish?

## Intended users and experience

Serve developers, security researchers, and technically curious Android users who need a practical view of application capabilities and network behavior without a desktop analysis environment.

The primary workflow is to select an APK, review static findings, prepare a Managed Work Profile session, confirm installation, launch the target, exercise its behavior, inspect captured traffic, end the session, complete removal, and review the report.

The interface must distinguish Personal from Sandbox, the target from the monitoring app, and static results from runtime evidence. Readiness, capture, import, cleanup, and report completion are separate states.

## Product pillars

### Local static inspection

Inspect package metadata, manifest declarations, signatures, permissions, components, relevant configuration, and bounded DEX content. Present embedded URL candidates, SDK signatures, and selected API references with provenance and coverage limits.

A useful static result explains why a capability was identified and where it was found. It does not imply that code executed.

### Controlled Android execution

Use a Managed Work Profile to separate target data and apply supported policies. Respect Android provisioning, installation, removal, and authentication flows.

The environment shares the device's Android kernel. It is not equivalent to a disposable desktop VM. Session cleanup must describe what was removed and what remains, including the retained Work Profile.

### Network understanding

Provide readable HTTP requests and responses, optional HTTPS inspection for compatible targets, WebSocket messages where readable, and progressively verified protocol coverage.

Show destination, protocol evidence, timing, direction, status, and capture completeness. Filters should make real observations easier to find without concealing unknowns or omitted content.

Offer metadata observation independently of payload inspection. Optional inspection changes the connection path and may affect target behavior; the report must disclose when it was enabled.

### Explainable evidence and reports

Keep declarations, VPN observations, Android DPM events, and derived findings distinct. Correlate them through explicit references rather than merging them into unsupported certainty.

Reports must survive the normal session lifecycle and explain pending evidence, failed collection, truncation, and unsupported behavior. Risk scoring must remain interpretable and must not be presented as malware probability.

## Operating principles

* Require no root, privileged installation, or desktop connection for the supported product workflow.
* Prefer local processing and local report access. Previously stored reports and static analysis should remain useful without Internet access.
* Recognize that external dynamic tests need connectivity and that the target may send data to external services.
* Prefer trustworthy coverage over an impressive protocol list.
* Expose Android limitations at the point where they affect user decisions.
* Make capture retention and deletion understandable.
* Treat unsupported and unknown as legitimate outcomes.

## What the product is not trying to become

APK Scope is not an antivirus with guaranteed detection, a malware probability oracle, a desktop VM, an Android emulator, or a kernel instrumentation platform.

It is not a universal TLS decryptor, a certificate pinning bypass tool, a mechanism to read other applications' private storage, or a way to control Personal Profile activity from the Work Profile.

It is not a promise of comprehensive source reconstruction, whole program taint analysis, universal behavioral coverage, or containment against Android kernel exploits.

It is not a cloud APK upload service, a required remote proxy service, or a general device surveillance product. Future optional integrations would require a separate decision and explicit data handling design.

## Product success

A successful release lets a user complete the documented workflow on a supported device and inspect findings that are traceable to actual evidence. A reviewer can reproduce the advertised capabilities with the fixture, understand the coverage limits, and distinguish a failed capture from a quiet application.

Feature completion follows [Verification Strategy](VERIFICATION_STRATEGY.md), not screenshot quality or parser count. Platform and security boundaries are defined in [Architecture Constraints](ARCHITECTURE_CONSTRAINTS.md). Future scope is governed by [Future Capabilities](FUTURE_CAPABILITIES.md).
