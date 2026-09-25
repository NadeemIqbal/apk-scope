# Decision: private storage inspection POC

Date: 2026-09-25. Branch: `codex/private-storage-inspector`.

## Decision

The project owner has explicitly requested implementation of the private-storage-inspection plan.
This authorizes an **opt-in, branch-scoped experimental feature** that instruments the APK the user
selects and runs in the APK Scope Work Profile session. It authorizes the described inspection of
that target's own Work Profile installation, including existing preferences/files and supported
live reads and writes. It does not authorize inspection of other packages, the Personal Profile's
copy, or device-wide storage.

This is a new, separate exception to the existing APK-repack POC decision. It does not expand or
reinterpret that fixture-only decision, change product/release claims, or authorize merging this
branch. A follow-up release, merge, or broadening beyond the selected target requires a separate
decision after review of implementation and verification evidence.

## Boundaries

- No root, shell-only data extraction, cross-app sandbox escape, Personal-profile data access, or
  extraction of Android Keystore private keys.
- Instrumentation is visible and opt-in. The UI and reports identify that the APK was modified and
  re-signed, that behavior may differ from the original, and which capture hooks were active.
- Read access is limited to the instrumented target process and storage roots Android exposes to
  that app. Unsupported processes and access paths remain uncovered and are disclosed.
- Collection, display, retention, deletion, and export follow
  [PRIVATE_STORAGE_INSPECTOR_PLAN.md](PRIVATE_STORAGE_INSPECTOR_PLAN.md). Raw values and media remain
  local and are masked in views by default. Sharing content requires deliberate selection.
- Inspecting storage does not authorize editing target data, changing target behavior, silently
  suppressing controls, or attributing local access to network disclosure.
- Target-originated data and event fields are untrusted; receiver authentication proves the session
  peer identity, not that the instrumented target faithfully reports all activity.

## Status and next decision point

This document records scope authorization, not implementation or capability support. Status remains
**planned** until the applicable acceptance gates in the plan pass. Start with feasibility and the
read-only stored-data slice. Before exposing captured values to a user, review collection controls,
byte/time/storage bounds, exclusion of the APK Scope channel credential, target-impact measurements,
and actual Work Profile behavior. Preserve existing milestones and their GSD history.
