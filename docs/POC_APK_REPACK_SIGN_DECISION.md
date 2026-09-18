# Decision Record: On-Device APK Repack/Sign + Instrumented HTTPS Inspection POC

Date: 2026-09-14. Branch: `poc/apk_repack_sign`. Starting HEAD: `21b0438` (branched from `dev`, working
tree clean at branch time — no uncommitted work existed to preserve).

## What this document is

An explicit, user-authorized architectural exception, scoped narrowly to this branch and this
capability. It does not amend [Product Vision](PRODUCT_VISION.md), [Architecture
Constraints](ARCHITECTURE_CONSTRAINTS.md), [Supervisor Context](SUPERVISOR_CONTEXT.md), or
[HTTPS Inspection POC](HTTPS_INSPECTION_POC.md) — those documents remain the durable description of
the supported APK Scope product and continue to govern `dev`/`main` and every capability outside this
POC. [Architecture Constraints](ARCHITECTURE_CONSTRAINTS.md)'s own preamble anticipates exactly this:
*"An exception requires an explicit architectural decision and user authorization where scope or
permissions change."* This is that decision.

## The conflict identified

Five independent, durable documents state that APK Scope does not patch APKs or bypass certificate
pinning:

- [PRODUCT_VISION.md](PRODUCT_VISION.md) — "It is not ... a certificate pinning bypass tool."
- [ARCHITECTURE_CONSTRAINTS.md](ARCHITECTURE_CONSTRAINTS.md) — "Do not bypass target pinning, alter
  third party APK trust settings, install a system CA, or disable upstream chain or hostname
  verification."
- [SUPERVISOR_CONTEXT.md](SUPERVISOR_CONTEXT.md) — rejected approach: "Weakening TLS verification,
  bypassing pinning, or disabling monitoring to obtain successful demonstrations: these invalidate the
  product's boundaries."
- [HTTPS_INSPECTION_POC.md](HTTPS_INSPECTION_POC.md) — "Bypassing in-app certificate pinning requires
  runtime binary hooking (e.g., Frida/Xposed) or reverse-engineering bytecode patches, which APK Scope
  does not attempt or perform."
- The project's private planning constraint 6 — "Do not bypass pinning, patch APKs, require root, or
  add a second VPN."

This POC's requested capability — repack a controlled fixture APK to inject a pinned Frida Gadget
build, re-sign it, and use it to demonstrate that instrumentation can defeat that fixture's own pin
check — is exactly what those documents rule out for the product.

## The decision

The user (project owner) was presented with this conflict directly and explicitly chose: **proceed as
an isolated, one-off exception.** Terms of that exception, binding for all work under this POC:

1. **Branch-scoped.** Lives only on `poc/apk_repack_sign`. Not merged into `dev` or `main` without a
   separate, explicit user decision made after reviewing this POC's actual evidence — this branch
   creation does not pre-authorize that merge.
2. **Fixture-scoped.** Applies only to one named, versioned, controlled fixture APK built for this
   purpose (see the POC tracking doc for its identity). No claim of, or support for, arbitrary
   third-party APKs. Unsupported inputs must be detected and explicitly rejected, never silently
   processed.
3. **Never described as the product's capability.** Every UI surface this POC adds must read as
   experimental and distinct from normal APK Scope analysis, per the task's required label: "Modified
   test APK. Behavior may differ from the original." Existing docs describing APK Scope as not
   patching APKs or bypassing pinning are not to be edited to accommodate this POC — this decision
   record is the reconciliation, not a rewrite of those claims.
4. **Own signing identity, not the product's.** A local POC-only signing key is generated on-device;
   the modified APK is never described as signed by the original developer, and the existing
   `appsbynadeem-upload-keystore.jks` upload key is never used for this purpose (see the user's global
   working notes on the suite's shared upload key — unrelated and must stay unrelated).
5. **Everything else in the durable docs still applies.** Upstream certificate/hostname validation
   stays enabled and unweakened; no second VPN; no root; existing Work Profile, CA lifecycle, VPN,
   Traffic Inspector, and evidence-persistence infrastructure are reused, not duplicated; risk scoring
   is unchanged; evidence from the modified fixture is never promoted into an original-APK finding.
6. **GSD stays separate.** This POC is tracked in a private planning record, not inside Milestone 10's
   (Security Audit) requirements, roadmap, or state. Milestone 10's numbering, requirement IDs, and
   history are untouched.

## Why this is an exception and not a reinterpretation

The existing docs' "no pinning bypass" language describes the **shipping product's** behavior toward
**arbitrary third-party APKs** a user points it at. This POC does something categorically different: it
modifies a fixture the project itself owns and built, purely to demonstrate — as a research artifact,
clearly labeled, never merged — that instrumentation-based inspection is technically distinguishable
from the product's actual CA-trust-based inspection path. Recording it as an exception (rather than
quietly deciding the constraint "doesn't really apply here") is what keeps the distinction honest: the
main product's claims remain exactly as strong, and exactly as limited, as they were before this
branch existed.
