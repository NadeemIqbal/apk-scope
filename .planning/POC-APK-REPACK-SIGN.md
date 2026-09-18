# POC Tracking: On-Device APK Repack, Sign & Instrumented HTTPS Inspection

Kept separate from Milestone 10 (Security Audit) per the task's own instruction. Does not renumber,
reopen, or touch `.planning/REQUIREMENTS.md`, `.planning/ROADMAP.md`, `.planning/STATE.md`, or
`.planning/PROJECT.md`'s Milestone 10 content in any way. Governing decision:
[docs/POC_APK_REPACK_SIGN_DECISION.md](../docs/POC_APK_REPACK_SIGN_DECISION.md).

## Branch record

- Branch: `poc/apk_repack_sign`
- Branched from: `dev` at `21b0438` (2026-09-14)
- Working tree at branch time: clean — no prior uncommitted work existed to preserve
- `main` at branch time: untouched, not inspected/modified by this POC
- This file, and all commits under this POC, stay on this branch only until a separate, explicit
  merge decision

## Status

`planned` — architectural/technical plan not yet approved. No implementation has started.

## Scope (bounded, per task instruction §3)

- One standalone APK (no split/bundle support)
- One explicitly supported ABI (target: `arm64-v8a`, matching physical Pixel 8 test device and typical
  current hardware — confirm against actual test device before implementation)
- One controlled fixture, purpose-built for this POC (not the existing `fixture` or `riskfixture`
  modules, which serve Milestone 8/9/10 acceptance and must not be repurposed or modified by this POC)
- One named, versioned pinning implementation in that fixture (e.g. OkHttp `CertificatePinner` against
  a known SPKI pin) — exact choice to be recorded once the fixture is built
- One named, versioned Frida Gadget build
- One proven instrumentation-loading mechanism, chosen after inspecting existing dependencies
  (`com.android.tools.smali:smali-dexlib2:3.0.3` is already present in
  `core/staticanalysis/build.gradle.kts` — first candidate to evaluate before adding new tooling)

## Requirements (POC-local IDs — independent of MS9/MS10 numbering)

Requirement IDs and acceptance criteria to be finalized during the plan-mode design pass and recorded
here before implementation begins, per `docs/VERIFICATION_STRATEGY.md`'s "required acceptance
contract." Placeholder structure:

- **POC-FIX01**: Purpose-built fixture app with one named/versioned pin implementation and one pinned
  HTTPS request to a controlled endpoint.
- **POC-COMPAT01**: On-device compatibility inspection of a selected APK; explicit rejection (not
  silent failure) of split packages, unsupported ABIs, and malformed/oversized archives.
- **POC-MOD01**: On-device bounded extraction, Gadget config + script insertion, instrumentation-load
  wiring, and rebuild — entirely on-device, no desktop substitution.
- **POC-SIGN01**: On-device signing identity generation, alignment, signing, and signature/package
  verification before install is offered.
- **POC-INSTALL01**: Existing Work Profile install/confirmation flow reused unmodified; documented
  handling of an incompatible-signature conflict with an already-installed package (no auto-uninstall,
  no data loss).
- **POC-RUN01**: Proof the Gadget actually loads in the target process (not just present in the APK)
  and that the specific pin-check hook fires.
- **POC-INSPECT01**: Request reaches the existing HTTPS inspection engine and Traffic Inspector through
  the existing VPN/CA path, unmodified.
- **POC-EVID01**: Persisted evidence carries modified-artifact provenance distinct from original-APK
  findings; survives restart; original fixture's own (non-bypassed, rejected) pin behavior is recorded
  separately as evidence the fixture's pinning is real.
- **POC-UI01**: Experimental UI surfaces compatibility, progress, signing/verification result,
  install readiness, instrumentation status, captured traffic, and explicit failure reasons, with the
  required "Modified test APK. Behavior may differ from the original." label on every relevant screen.

## Next action

Design the concrete technical approach (extraction/patching/signing implementation, instrumentation
proof method, evidence schema) and present it for user approval before writing implementation code.
