# APK installation failure: root cause analysis

- Date: 2026-09-25
- Affected flow: Frida inspection → Work Profile APK handoff → Android PackageInstaller confirmation
- Observed device: `emulator-5554`, Android API 37, Work Profile user changed from 10 to 11 during the investigation
- Primary target: `com.apksandbox.pinnedfixture`

## User-visible failure

During a demo, APK Scope reaches the installation-confirmation step and remains there or later shows:

> Installation was interrupted. Start a new sandbox session to retry.

Technical detail shown by the app:

> Package absent and PackageInstaller session no longer exists

The APK is not primarily being rejected by Android. In the captured reproduction, Android created and displayed the confirmation activity, but APK Scope opened its own cross-profile status activity while that confirmation was active. A second race then allowed a stale status read to mark the session failed while Reinstall was replacing the installer session.

## Confirmed event timeline

Evidence is in `.planning/debug/install-2026-09-25/user-reproduction.log`.

### Confirmation-screen interruption

For session `8da242c2-5764-4793-8f9e-eb5ac91e1cd5`, installer session `1033442159`:

1. `11:14:17.530`: APK Scope receives `STATUS_PENDING_USER_ACTION`.
2. `11:14:17.584`: APK Scope posts the installation notification.
3. `11:14:47.072`: Android starts `PackageInstaller.InstallStart` for the Work Profile.
4. `11:14:47.303`: APK Scope starts another `WORK_QUERY` from Personal.
5. `11:14:47.358`: Android starts `PackageInstaller.v2.ui.InstallLaunch`.
6. `11:14:47.492`: the Work-side `SandboxWorkQueryActivity` starts.
7. The visible screen returns to APK Scope's Preparing Sandbox screen. The installer remains pending; no install rejection occurred.

The 189 ms gap between Android's confirmation launch and APK Scope's Work query is sufficient to cover or replace the confirmation task. The query activity is launched through `ForwardIntentToManagedProfile`, so it changes the foreground task in the same Work Profile where the installer UI is running.

### False failure during Reinstall

For the same logical APK Scope session:

1. Reinstall abandons old installer session `1033442159`.
2. Android sends `STATUS_FAILURE_ABORTED` for the old session.
3. Before the new Work-side report is visible, a status query reads the previous durable report as `INSTALLING` and sees the old installer ID absent from `mySessions`.
4. The query writes `FAILED` with `Package absent and PackageInstaller session no longer exists`.
5. Reinstall creates installer session `59766811` and reports `INSTALLING` afterward.
6. A later replacement creates session `34829896`; Android returns `INSTALL_SUCCEEDED` at `11:16:10.729`.
7. Work records `INSTALLED` at `11:16:10.820`, but the Personal screen has already rendered the stale failure and does not recover automatically.

This is a stale-observation race. “The installer session from the report is gone” is not enough to conclude that the logical APK Scope session failed: Reinstall may already be replacing it, or a newer installer generation may exist but not yet be included in the query response.

### Incorrect package-presence fallback

Earlier logs also showed Work reconciliation promoting a session to `INSTALLED` solely because `getPackageInfo()` found the package. That package could be an older APK from a previous session. Personal reconciliation already documented the opposite rule, but `SandboxWorkQueryActivity.exportEvidence()` still had the unsafe fallback.

That fallback could skip the Android confirmation for a new APK and launch the old APK with an old Frida token. It has been removed in the current working tree. Package presence alone must never establish completion for a new PackageInstaller session.

## Root causes

### R1 — automatic polling opens a foreground Activity during Android confirmation

`SandboxPreparingViewModel.startRecoveryLoop()` repeatedly called `requestReconciliation()`. Each reconciliation used a cross-profile `WORK_QUERY`, which starts `SandboxWorkQueryActivity` in the Work Profile. This is an intrusive foreground operation, not a read-only background query.

The polling interval was short enough to overlap Android's confirmation task. Android's confirmation UI and APK Scope's query activity compete for the same Work Profile task stack. The result is a valid confirmation request that the user cannot reliably see or complete.

Status: automatic polling has been removed from the current working tree. Status import is now user initiated through `Check installation status`.

### R2 — reconciliation treated an old installer generation as the current generation

`SandboxWorkQueryActivity.exportEvidence()` used the durable report's `installSessionId` and tested whether that ID existed in `PackageInstaller.mySessions`. During Reinstall, the old ID is intentionally abandoned before the replacement ID is reported. The query therefore converted a normal replacement window into a terminal `FAILED` state.

The previous logical session had no generation/attempt identity that lets reconciliation distinguish:

* old installer callback;
* current installer attempt;
* replacement attempt already requested but not yet reported;
* query started before a newer durable Work report was committed.

Status: implemented in the current working tree. `InstallAttemptStore` persists a monotonic logical
attempt and marks Reinstall as superseding before abandoning the old Android session. Export queries
are now observational: they never infer installation success or cancellation from package/session
presence, never write lifecycle changes, and never tear down the VPN.

### R3 — callback and query writes are not ordered by installer generation

Work evidence is cumulative, but lifecycle `state` is always replaced by the newest patch. A stale query can write `FAILED` after a newer `INSTALLING` or `INSTALLED` report unless the write carries and checks an attempt/generation token. The same problem can occur with callbacks that arrive after Reinstall has abandoned their session.

Status: implemented in the current working tree. Reports carry `installAttemptId`; Work evidence
serializes read/merge/write operations and ignores older attempt patches; the callback receiver
ignores superseded callbacks. A shared Work mutex covers commands, callbacks, identity checks and
side effects. Personal persists the attempt in Room (schema 11, data-preserving migration) and rejects
older reports, including while waiting for a replacement Android session ID. Push/pull imports update
the latest Personal row atomically. A retry clears the prior install error; Work success cannot inherit it.

### R4 — lost Personal callback leaves the screen stale after a real successful install

The Work callback can correctly record `INSTALLED` while its cross-profile push back to Personal is delayed or lost. The old polling loop was intended to recover this, but it caused R1 and R2. After polling removal, the product needs a safe, non-intrusive recovery path.

Status: partially addressed. A manual status action exists; the durable Work report remains the source of truth. Automatic foreground polling must not be restored.

### R5 — install permission is a separate prerequisite failure

The Work Profile can report `canRequestPackageInstalls=false`. This is a legitimate Android prerequisite failure, not an APK-format failure. The reproduction showed the permission initially false, then true after Settings remediation. The current app already opens the Work Profile's settings through a cross-profile query.

Status: handled separately. Do not merge this prerequisite error with PackageInstaller cancellation or stale-session errors.

## Implementation and verification status

Source implemented:

* Removed automatic Activity-based polling and immediate status queries after Continue/Reinstall.
* Added the explicit `Check installation status` action with single-flight and timeout guards.
* Made Work evidence export observational; package/session presence never invents an install outcome.
* Added durable attempt generations to callbacks, Work storage and Personal Room records. Older
  callbacks are rejected before VPN teardown or report forwarding.
* Serialized Work commands and callbacks; duplicate Continue on a sealed session does not recommit.
* Made Personal push/pull updates atomic against the latest row. Explicit retry resets the install
  error and advances its generation; delayed successful callbacks can repair earlier inferred failures.
* Reinstall restores and verifies VPN isolation before committing a replacement.
* Added a data-preserving Room 10→11 migration and its instrumented regression.

Verification results are recorded below. Earlier synthetic query tests only established query behavior;
they did not prove normal Work Profile installation. Preserve unrelated storage-inspector changes.

## Remaining product verification

Use normal APK Scope controls, Android's installation notification, and Android's confirmation dialog.
ADB may install the host debug build and inspect evidence, but must not install the target APK.
Do not add timer-based cross-profile polling to recover a missed push: the explicit status action
imports durable Work evidence after the user returns to the app.

## Acceptance tests for the next model

The fix is not complete when the app compiles. Verify these cases on a real Work Profile or controlled emulator:

1. Fresh install: Android confirmation remains foreground until the user confirms; no `WORK_QUERY` starts during the confirmation.
2. Confirmed install: callback for the current attempt reports success, package/version verification passes, and the Personal screen reaches `INSTALLED`/`READY`.
3. Cancelled install: explicit user cancellation reports a retryable cancellation and leaves no active installer session.
4. Reinstall race: abandon old attempt, start new attempt, run reconciliation between both callbacks; the old attempt cannot write `FAILED` to the new attempt.
5. Late old callback: deliver an old `ABORTED` callback after the new attempt is pending; current attempt remains `INSTALLING`.
6. Existing package: leave an older same-package APK installed; start a new attempt; package presence alone never reports success.
7. Lost Personal push: Work records successful install while Personal is stopped; relaunch Personal and explicitly refresh; it imports success and does not show the stale error.
8. Process death: kill Personal during confirmation and during callback delivery; relaunch and recover from the current durable Work report without creating a duplicate installer attempt.
9. Permission denied: `canRequestPackageInstalls=false` shows Work Profile settings guidance and never creates a PackageInstaller session.
10. Demo loop: repeat at least five fresh sessions without manual app-data clearing, profile deletion, or ADB installation. Record every attempt ID, installer ID, callback status, final UI state, and any failed query.

## Evidence and limitations

The evidence proves the foreground-activity interference and stale-generation failure on the observed emulator. It does not prove that every Android OEM behaves identically, nor that every installation failure in the last two weeks has the same cause. The next implementation should preserve separate error categories for permission denial, user cancellation, APK rejection, session supersession, and missing/lost callback.

Do not claim “APK installation is fixed” until the acceptance cases above pass through the normal APK Scope UI without ADB assistance.

## Source map for the next model

Use these symbols as the starting points. Do not search the whole repository again unless a listed contract is missing.

| Responsibility | Source | Relevant symbols |
| --- | --- | --- |
| Personal screen and user actions | `app/src/main/java/com/nadeem/apkscope/ui/screens/sandbox/SandboxPreparingViewModel.kt` | `startPrepareIfNeeded`, `continueInstallation`, `reinstall`, `refreshInstallation`, `requestReconciliation` |
| Personal UI buttons and confirmation copy | `app/src/main/java/com/nadeem/apkscope/ui/screens/sandbox/SandboxPreparingScreen.kt` | `showContinueInstallation`, `showReinstall`, `Check installation status` |
| Personal-to-Work operation contract | `app/src/main/java/com/nadeem/apkscope/domain/sandbox/SandboxSessionCoordinator.kt` | `continueInstallation`, `reinstall`, `importEvidence`, `reconcile` |
| Work APK staging and commit | `app/src/main/java/com/nadeem/apkscope/sandbox/SandboxWorkerService.kt` | `runPrepareSequence`, `runContinueInstall`, `runReinstall`, `report` |
| Work installer callback | `app/src/main/java/com/nadeem/apkscope/sandbox/SandboxInstallResultReceiver.kt` | `handleInstallResult`, `awaitInstalledPackage`, callback preference keys |
| Work cross-profile query | `app/src/main/java/com/nadeem/apkscope/sandbox/SandboxWorkQueryActivity.kt` | `exportEvidence`, `respondJson`, `reconcile` branch |
| Durable Work report merge | `app/src/main/java/com/nadeem/apkscope/sandbox/WorkEvidenceStore.kt` | `recordFact`, `mergeReports` |
| Work attempt identity | `app/src/main/java/com/nadeem/apkscope/sandbox/InstallAttemptStore.kt` | `begin`, `bind`, `isReinstalling`, `finishReinstall` |
| Personal retry supersession | `app/src/main/java/com/nadeem/apkscope/sandbox/InstallRetryMarker.kt` | `mark`, `isSuperseded` |
| Report-to-Personal state merge | `app/src/main/java/com/nadeem/apkscope/domain/sandbox/SandboxSessionReportMerger.kt` | `mergeFacts`, `advanceThroughOperationalStates` |
| Lifecycle status mapping | `core/model/src/main/kotlin/com/nadeem/apkscope/core/model/InstallLifecycle.kt` | `outcome`, `interrupted` |

Important current behavior: `WorkEvidenceStore.mergeReports()` intentionally lets the patch's lifecycle `state` replace the existing state while retaining omitted fields. That is correct for normal ordered updates, but unsafe for stale reads. The generation guard belongs before `recordFact()` or inside a generation-aware store update; changing merge order alone will not solve the race.

## Verification sequence for future models

The generation guard is already implemented in the current working tree. A future model extending
this flow should verify the following contracts in order and change only the contract that is
actually missing:

1. Keep `installAttemptId` distinct from Android's `installSessionId`; both must round-trip through `SandboxStatusReport`.
2. Keep Reinstall's supersession marker durable before the old PackageInstaller session is abandoned, and clear it on every exit path.
3. Keep `exportEvidence` observational: no install-state writes or VPN teardown.
4. Keep `handleInstallResult` ignoring superseded callbacks while still logging their status and session ID.
5. Keep Work evidence writes serialized and reject lower attempt generations before merging lifecycle state.
6. Keep terminal Personal imports from older installer sessions out of the current session state.
7. Keep automatic polling removed. A status pull must remain an explicit user action.
8. Run the focused JVM tests, the available device regression test, and then one fresh normal-UI demo run. Preserve raw logcat and the exact final UI state.

## Lower-model task prompt

The following prompt can be given to a lower model verbatim:

> Read `docs/INSTALLATION_FAILURE_ROOT_CAUSE_ANALYSIS.md` first. Review the current logical install-attempt generation fix before changing code. Preserve unrelated dirty files and do not reset, commit, push, or use ADB to install the target APK. Keep automatic cross-profile polling disabled, keep package presence from establishing install success, and verify that Work reconciliation, installer callbacks, Work evidence writes, and Personal report import all reject stale/superseded attempts. Add or extend pure tests only for gaps you find. Run the focused JVM suite and the available device regression test, and report exact results. Do not claim full device support until a normal UI run confirms Android's installer remains foreground and the Personal screen reaches READY after the current attempt succeeds.

## Triage commands

These commands are read-only and capture evidence without changing the device:

```sh
adb devices -l
adb -s emulator-5554 shell pm list users
adb -s emulator-5554 shell dumpsys activity activities | rg 'topResumedActivity|PackageInstaller|SandboxWorkQueryActivity'
adb -s emulator-5554 logcat -d -v threadtime -s ApkScopeInstall ApkScopeHandoff ReconcileDiag SandboxWorkQuery PackageInstaller PackageInstallerSession ActivityTaskManager
```

For every reproduction, record the APK Scope session ID, Android installer session ID, logical attempt generation, Work user ID, callback status, top resumed activity, and final Personal state. Without all six values, a later model cannot distinguish an APK rejection from foreground-task interference or a stale-state race.
