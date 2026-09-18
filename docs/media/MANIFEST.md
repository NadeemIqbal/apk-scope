# APK Scope — Demo Media Manifest

This directory contains the current visual proof for the APK Scope README. Media was captured on
2026-09-18 from the installed APK running on the `emulator-5554` Google emulator (`sdk_gphone16k_arm64`,
Android 17 / API 37). The working tree was dirty because the repository was being relocated to a
root-level Gradle project; the capture records UI state only and is not a release certification.

## Provenance

- APK Scope package: `com.nadeem.apkscope`.
- Target shown in the current static result: `com.apksandbox.riskfixture` (Risk Signal Fixture).
- The target APK was copied to `/sdcard/Download/` and selected through Android's system document
  picker. It was not installed into the Personal profile with `adb install`.
- Screens were captured from the live emulator with Android SystemUI demo values (10:00, full battery,
  and no personal notifications) to keep the frame reproducible.
- No screenshot was synthesized and no database row was manually inserted for these captures. Existing
  device reports may remain visible in the Reports history.
- The original walkthrough is a static-focused media sample. The live recordings below are separate
  Work Profile verification runs and are annotated with their limitations.

## Main walkthrough

| File | Duration | Format | Contents |
| --- | ---: | --- | --- |
| `demo/apk-scope-walkthrough.mp4` | 24.3s | 720×1600 H.264, no audio | Home dashboard → Risk Signal Fixture static analysis → findings → Evidence report |
| `demo/apk-scope-preview.gif` | 24.3s loop | 300×666 GIF | Lightweight README preview of the same walkthrough |

The two source captures were recorded with Android `screenrecord`; the final MP4 was concatenated and
re-encoded with `ffmpeg` using fast-start metadata. The GIF is a small preview and should not be treated
as a separate evidence run.

## Live verification recordings

| File | Duration | Format | Contents and boundary |
| --- | ---: | --- | --- |
| `demo/apk-scope-ca-vpn-flow.mp4` | 180.2s | 720×1600 H.264, no audio | Disposable Work Profile setup, HTTPS/WSS fixture activity, and Live Monitor VPN DNS/domain/connection evidence. CA was not installed, so plaintext HTTPS decryption is not demonstrated. |
| `demo/apk-scope-frida-dynamic-flow-clean.mp4` | 175.1s | 720×1600 H.264, no audio | Development-only Frida/APK patching flow through Work Profile launch and receiver startup. The pinned `httpbin.org` request fails because the fixture's embedded SPKI pin is stale; no successful Frida payload capture is claimed. |
| `demo/apk-scope-frida-protocol-companion.mp4` | 34.5s | 720×1600 H.264, no audio | In-sandbox fixture log showing successful HTTPS JSON/POST calls and WSS HTTP 101/text/binary frames, followed by APK Scope traffic observations. This is protocol evidence, not proof of Frida interception. |

The CA and protocol recordings are real emulator captures, not synthesized UI. WSS is retained as research-status
evidence; it is not advertised as a release-supported decrypted protocol.

## Screenshots

All screenshots are 1344×2992 PNGs from the same app build and emulator state.

| File | Screen and purpose |
| --- | --- |
| `screenshots/01-dashboard.png` | APK Scope Home with the Risk Signal Fixture recent session and the Choose APK action |
| `screenshots/02-static-analysis.png` | Analysis Detail with identity, static score, signature state, and key findings |
| `screenshots/03-sandbox-config.png` | Sandbox configuration with CA Mode and Frida Mode choices clearly separated |
| `screenshots/04-security-audit.png` | Static security audit summary with findings, review items, and passed checks |
| `screenshots/05-deep-analysis.png` | Deeper static inspection cards for DEX files, API references, and URL candidates |
| `screenshots/06-analysis-coverage.png` | DEX analysis coverage list and inspection counts |
| `screenshots/07-reports.png` | Reports history with static and runtime status shown independently |
| `screenshots/08-evidence-report.png` | Saved evidence report with risk score, completeness, and finding provenance |
| `screenshots/09-more.png` | More/settings view with local retention and protocol disclosures |
| `screenshots/10-getting-started.png` | In-app Getting started entry point and workspace readiness state |

## Interpretation boundaries

The Risk Signal Fixture intentionally declares and exercises risk-relevant patterns, so its displayed
score is expected to be elevated. The score is an explainable rule result, not a malware probability.
Static DEX references are not proof of execution. A report that says runtime is “Not run” or evidence is
“Not collected” should be read exactly that way.

The screenshots do not establish universal Android/OEM compatibility, complete network coverage, or
support for gRPC, SSE, QUIC/HTTP/3, or Frida as a release workflow. Those capabilities remain bounded by
the verification statuses in the repository's supervisor context and verification strategy.

## Privacy review

- No device serial, account identity, phone number, Wi-Fi SSID, or personal notification content is
  included in the delivered media.
- The visible package names and risk findings belong to the test fixture.
- Screenshots do not expose terminal commands, local filesystem paths, or inspector private keys.
