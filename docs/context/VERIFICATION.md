# Verification and completion checklist

## Required evidence record

For each requirement record status, supporting code or test artifact, execution scope, timestamp, and any limitation. Use PASS only when the relevant evidence exists. Otherwise use FAIL, NOT RUN, or REPORTED ONLY.

Count tests from XML or equivalent authoritative result files. Gradle task counts and build success do not substitute for assertions. Avoid double counting copied or stale reports.

## Runtime demonstration

1. Identify device, API level, actual Work Profile, and package instances without hardcoded user IDs.
2. Start through supported project provisioning and installation flows.
3. Show CA generated and installed in the intended profile and inspection explicitly enabled.
4. Trigger a fixture HTTPS GET and POST using dummy content and no explicit proxy shortcut.
5. Correlate fixture execution, VPN forwarding, engine capture, and viewer transaction using nonsecret identifiers and timestamps.
6. Show GET response JSON and POST request plus response JSON in the viewer.
7. Distinguish debug client trust configuration from payload capture provenance.

## Negative and regression cases

A target client with no CA trust must reject interception.
An invalid upstream certificate must be rejected for a trust reason.
A trusted chain with an incorrect hostname must be rejected for an identity reason.
A prohibited destination must remain blocked before upstream connection.
An unrelated app must not obtain arbitrary protected forwarding via the inspector listener.
A fragmented ClientHello must not be dropped, duplicated, or cause unbounded buffering.
A body larger than the capture limit must arrive intact while the saved preview is truncated.
Sensitive known fields must be redacted before retention.
Inspection disabled must preserve the original permitted forwarding behavior.
Reset must stop active inspection and remove its captures, certificate, and key material through supported APIs.

Use controlled TLS servers or a test harness where necessary to isolate failure causes. Do not replace certificate tests with a generic network failure. Do not weaken production destination rules to accommodate a test endpoint; separate harness configuration explicitly.

## Completion criteria

All active requirements are evidence backed, with no unresolved TLS validation or policy bypass defect. Full GET and POST route is demonstrated. Supported limitations and exclusions are documented. If a device or capability is unavailable, finish what can be verified and report the milestone incomplete with the exact remaining check.

Do not repeat historical whole product fuzzing, process death campaigns, or provisioning matrices merely to close this small POC. Run focused checks for changed behavior and any concrete regression.

## Final report format

Branch and working tree summary.
Implementation changes in this pass.
Actual unit and device test totals with failures and skips.
Runtime profile and route evidence.
GET and POST capture result.
Target trust, upstream trust, and hostname rejection results.
Policy, parsing, redaction, disabled mode, and reset results.
Requirements still open.
POC demonstrated or incomplete, with reasons.
No commit, push, merge, or release.
