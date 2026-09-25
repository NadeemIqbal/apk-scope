# Private Storage Inspector — implementation plan

- Date: 2026-09-24
- Status: In progress on `codex/private-storage-inspector`; no storage-inspection capability is product verified.
- Working feature name: **Storage Inspector**

Implementation started 2026-09-25 with a Work Profile entry point and a user-triggered, bounded
snapshot of supported private roots and existing SharedPreferences. The current slice is
memory-only and experimental. It does not yet capture file contents, preview images, retain reports,
or observe live reads/writes. Phase A's on-device feasibility gate remains open.

## 1. User outcome

Let a user inspect the private data of an instrumented app running in the Work Profile:

1. **Already stored:** browse the data present when inspection starts.
2. **Being read:** see observed accesses, returned values or bytes where available, and failures.
3. **Being stored:** see observed writes, changes, deletions, and the evidence of persistence.
4. **Understand it:** view preferences as named values, structured files as formatted documents,
   images as thumbnails, databases as tables, and activity as plain-language events.
5. **Keep the result:** reopen retained evidence after the target stops or is removed.

This is a product feature with a dedicated interface. Users should not have to write Frida scripts,
interpret console output, or understand Android filesystem paths to use it.

“Any data” means a broad inventory of accessible target-owned storage, with progressive decoders
and explicit limitations. It does not mean universal decoding, complete observation of every I/O
mechanism, recovery of deleted data, or reconstruction of access history before hooks were active.

## 2. Scope and access decision

Use an agent inside the instrumented target process, delivered through the existing APK repack and
Frida Gadget path. The Work Profile and Profile Owner role do not grant access to another app's
private storage; the embedded agent executes with the target app's own access. See the
[Android application sandbox](https://source.android.com/docs/security/app-sandbox) and
[Frida Gadget model](https://frida.re/docs/gadget/).

The user's request authorizes planning this explicit instrumentation extension. Earlier project
documents exclude general target instrumentation from the default workflow; this proposal records
that scope difference rather than treating the feature as an existing supported capability. It is
an opt-in instrumented mode. No existing roadmap milestones or support claims are changed here.

### Installation identity and existing data

- Inspect the exact Work Profile installation that loads the agent.
- A freshly installed repacked APK starts with its own data. It does not inherit the Personal
  installation's preferences, images, databases, credentials, or Android Keystore entries.
- Re-signing generally prevents updating an existing differently signed installation in place.
  Do not promise to preserve its data, uninstall it automatically, or import it through shell access.
- Existing data can be inspected when it is already in that compatible instrumented installation,
  or is populated through the target's normal use or an explicit target-supported import.
- “Present at scan start” establishes existence during a scan interval, not when the app originally
  created the data or whether it ever read it.

### Included storage

Discover roots from the target's Android contexts and application metadata; never hardcode user IDs.
Inventory credential-protected private data, device-protected private data when accessible, files,
preferences, databases, cache, no-backup storage, and app-owned external files/cache directories.
Expose WebView and framework-managed subdirectories as files first. Treat third-party content URIs
and shared media as outside the initial private-storage inventory.

Credential-protected data can be unavailable while the profile is locked. Show **Locked — unlock
Work Profile**, without attempting to bypass Android authentication.
[Android Direct Boot storage](https://developer.android.com/privacy-and-security/direct-boot)

### Explicit boundaries

- No root, cross-app private-directory browsing, kernel hooks, or access to the Personal copy.
- No arbitrary process memory dump or Android Keystore private-key extraction.
- No assumption that encrypted or proprietary files can be decoded.
- No inference that reading local data means sending it over the network.
- No preference/file editing in this feature. Inspection can itself add I/O and timing overhead;
  disclose this and separate agent activity from target activity.

## 3. Existing integration points and known gaps

Source inspection on branch `codex/frida-target-command-console` found these starting points:

| Existing source | Role in this plan |
| --- | --- |
| `app/src/main/assets/frida-live-capture.js` | Existing target agent and authenticated command transport; add a modular storage agent, not storage logic mixed into SSL parsing. |
| `payload/fridaloader/src/main/java/com/nadeem/apkscope/FridaLoaderFactory.java` | Gadget startup and target credential handoff; negotiate storage capabilities and report hook readiness. |
| `app/src/main/java/com/nadeem/apkscope/poc/apkrepack/FridaTrafficMonitor.kt` | Current receiver; dispatch typed storage frames to a separate collector while preserving traffic and console behavior. |
| `app/src/main/java/com/nadeem/apkscope/poc/apkrepack/FridaChannelConfig.kt` and `FridaChannelProvider.kt` | Existing session credential and Binder-caller checks; retain and test them for the new protocol. |
| `core/database/`, `core/model/`, `core/crossprofile/` | Integrate storage evidence, durable state, and bounded transfer without putting content into network transaction records. |
| `app/src/main/java/com/nadeem/apkscope/ui/screens/workmonitor/` | Add the Storage Inspector entry from the active Work session. |

Important implementation facts:

- The loader currently schedules Gadget approximately 3 seconds after first activity resume, with
  a 10-second fallback. Startup reads/writes can precede hooks. The first implementation must show
  that gap; an early-start option requires its own compatibility investigation and verification.
- The receiver currently permits one connected target client. It does not establish multi-process
  coverage. Add explicit process coverage before claiming observation of secondary processes.
- Current code includes a target-private credential handoff file, `apk_scope_channel_token`.
  Exclude this, instrumentation assets, and transport internals from content capture and export.
- The agent's Java bridge availability needs a runtime probe. Native file hooks being available
  does not establish that SharedPreferences hooks can run.
- Existing target authentication identifies a channel participant, not an independent witness.
  Evidence remains **instrumented target evidence**, potentially affected by target tampering.

Proposed components: `StorageAgent`, `StorageChannelDispatcher`, `StorageCaptureRepository`,
`StorageSnapshotWorker`, `StorageDecoderRegistry`, `StorageReportBuilder`, and
`StorageInspectorViewModel`. Names and exact placement are provisional; reuse existing conventions.

## 4. Acquisition strategy

### A. Initial inventory and snapshots

1. Authenticate, negotiate protocol/capabilities, and obtain discovered storage roots.
2. Install available live hooks first; record readiness separately for each hook family/process.
3. Start a background, cancellable, paginated inventory. Record start/end times per scan and item.
4. Capture preference values and bounded previews for supported small files. Retrieve large content
   only on request. Inventory metadata must still include files whose content cannot be captured.
5. Version observations. A file that changes while reading becomes **Changed during capture**;
   never present a non-atomic inventory as one globally consistent snapshot.
6. Offer **Refresh inventory** and **Compare with previous snapshot**. Snapshot-only changes have
   an interval and unknown caller; they are not fabricated read/write events.

Follow regular files/directories within authorized roots. Resolve aliases and symlinks carefully,
reject escapes, avoid special files and pipes, and use descriptor-based validation to reduce path
replacement races. Never run recursive unrestricted reads of `/data`, `/proc`, or `/sdcard`.

Track files by a scoped object identity plus versions and path history. Include device/inode identity
when available, but do not assume it remains unique after deletion or across installations.

### B. SharedPreferences

- Identify named preference stores, storage context, and implementation class. Read well-formed
  on-disk XML safely and capture an in-memory snapshot through available app APIs separately.
- Hook supported `get*`, `getAll`, and `contains` calls. Preserve type, actual returned value,
  fallback/default where known, and whether the key existed. A default return does not prove a
  stored key exists. Do not add extra reads on hot paths simply to guess missing context.
- Track each editor's staged `put*`, `remove`, and `clear` operations and submission boundary.
  Handle clear/remove ordering, concurrent editors, strings, sets, and nullable inputs.
- Distinguish **Value staged**, **Apply requested**, **Commit returned true/false**, and
  **Value found in a later disk snapshot**. `apply()` updates memory and schedules asynchronous
  disk persistence; its return is not disk confirmation. `commit()` reports the synchronous write
  result. Do not label a staging call as persisted.
  [SharedPreferences.Editor semantics](https://developer.android.com/reference/android/content/SharedPreferences.Editor)
- Preserve old/new values only when actually observed, with source and timestamp. Otherwise show
  **Previous value unavailable**, not an invented empty value.
- Encrypted wrappers may expose plaintext at a verified logical API boundary while disk XML remains
  ciphertext. Preserve those as separate representations and report wrapper/version coverage.

### C. Files and images

- Observe supported Java I/O APIs and native file APIs. Start with file input/output streams,
  random-access I/O, native open/openat, read/pread, write/pwrite, close, rename, unlink, and truncate;
  extend supported channel/vector I/O explicitly. Probe available symbols and overloads per runtime.
- Capture actual completed byte counts and error outcomes. Sample read buffers after successful
  return; copy bounded write arguments before they can change, then apply the actual return count.
  Preserve offsets where known, partial operations, append semantics, and encoding boundaries.
- Maintain descriptor lifetimes, duplication, close/reuse, rename history, and path resolution.
  Opening a file is not reading it; creating a stream is not proof of successful storage.
- Associate overlapping Java/native events using operation IDs where possible. Display one logical
  operation with supporting low-level events; never deduplicate genuinely repeated reads by content.
- Capture an image/file version only when bytes are available and consistent enough to preview.
  A later file snapshot is not necessarily the exact bytes read earlier. Keep both timestamps and
  sources visible. Do not reconstruct missing byte ranges as though complete.
- Observe deletes/renames when hooks permit. Previously captured content may remain available;
  content never captured before deletion cannot be recovered by this design.
- Treat memory-mapped access, direct syscalls, custom runtimes, and uninstrumented processes as
  explicit live-event gaps. Mapping a file does not prove which bytes were read or dirtied.

### D. Databases, DataStore, and other formats

- SQLite/Room: inventory databases, offer schema and paginated read-only table previews, then add
  supported query/statement/transaction hooks. A query execution is distinct from rows actually
  consumed by the app. Rolled-back writes must not appear as committed state.
- Never copy a live SQLite main file alone and call it consistent: WAL and concurrent writes matter.
  Evaluate a supported transactional snapshot/backup integration; if unavailable, show metadata or
  a clearly marked best-effort capture. Do not checkpoint or mutate the target DB for inspection.
  [SQLite backup guidance](https://www.sqlite.org/backup.html)
- SQLCipher/encrypted databases: show encrypted/unknown unless a compatible, separately verified
  logical adapter observes data inside the app. Do not promise automatic key recovery.
- Preferences DataStore: support documented, verified versions with typed previews and update events.
  Proto DataStore/protobuf: require a matching schema for meaningful field names; otherwise expose
  bounded binary/structural previews with unknown semantics. Collector reads and app reads differ.
- WebView local storage, IndexedDB, cookies, MMKV, Realm, Flutter/Hive, and custom stores: inventory
  files immediately; add semantic adapters individually. Do not apply a decoder based only on a
  filename or claim semantic reads from an underlying file read.

### E. Prevent the observer from becoming the observed

Use a reentrancy guard, dedicated agent workers, operation origins, and explicit exclusions for agent
snapshotting, preview generation, hashing, token handoff, and transport. Propagate origin through
asynchronous agent work. Guards must not suppress unrelated app operations on other threads.

Hooks must preserve arguments, return values, exceptions, file offsets, and `errno`. They must not
block on UI, network, decoding, or disk persistence. Use bounded copies and an asynchronous queue.
Test the observer effect against the same fixture running without storage capture.

## 5. Transport, identity, and durable storage

Proposed flow:

```text
Instrumented target: snapshots + hooks
    -> bounded authenticated local storage frames
Work APK Scope: validate -> normalize -> persist -> decode -> Storage Inspector
    -> explicit bounded report/selected-content handoff
Personal APK Scope: retained storage summary and selected previews
```

- Add a negotiated storage protocol version; do not silently reinterpret existing traffic frames.
  Use typed operations for capabilities, inventory, preview, start/stop observation, and snapshot
  comparison. Normal UX never submits arbitrary user JavaScript.
- Bind target, analysis, session, install identity, and receiver generation at authentication. Ignore
  conflicting self-reported fields. Record agent/hook versions and original/patched APK identities.
- Use per-process instance IDs, connection epochs, monotonic sequence numbers, receiver times, and
  deduplication keys. Wall clocks alone cannot establish exact ordering across processes.
- Authenticate every connection, reject stale sessions, and rotate credentials at session end.
  Never log credentials, preference values, raw SQL bindings, or content bodies.
- Enforce encoded-byte limits before JSON parsing or base64 allocation. Validate chunk order,
  declared lengths, per-object totals, checksums, cancellation, deadlines, and compression limits.
- Separate metadata/event priority from optional content chunks so a large image cannot stall
  lifecycle messages or the existing traffic inspector. A shared transport needs fair scheduling.
- Persist events transactionally with an acknowledged durable sequence. Reconnect can replay only
  bounded unacknowledged data; deduplicate it. Report unrecoverable gaps after process death or
  buffer overflow. Do not promise exactly-once delivery or lossless capture under all load.
- Authenticate origin without trusting content. Treat paths, MIME types, strings, database schemas,
  images, and all target-supplied fields as untrusted input.

Suggested data records:

| Record | Essential fields |
| --- | --- |
| `StorageCapture` | Analysis/session, package, profile, install identity, capture settings, start/end, retention, capability manifest. |
| `StorageProcess` | Process instance, claimed PID/name, connection epoch, hook readiness, lifecycle, attribution strength. |
| `StorageItem` | Object identity, root, relative path/store/key, type, size, first/last seen, availability. |
| `StorageVersion` | Snapshot/event source, observed interval, logical/disk representation, bytes captured, consistency, content reference. |
| `StorageEvent` | Stable ID, process/sequence, operation, result/error, item/version, byte range, previous/new references, origin, hook family. |
| `StorageContent` | Opaque ID, encoding/MIME, capped length, capture range, content digest scope, encryption/redaction state. |
| `StorageCoverage` | Root/process/hook/decoder states, gaps, exclusions, truncation and drop counts, timestamps. |

Use Room for indexed metadata/events and inspector-private blobs for captured content. Never use
target filenames as blob paths. Bound blob storage and decoded caches independently. Database
migrations must preserve existing analyses. Content digests describe captured ranges, not whole
files unless the entire file was read; omit raw sensitive-value hashes from redacted exports.

## 6. Human-readable UX

### Entry and setup

Add **Storage Inspector** beside Traffic Inspector in the active instrumented Work session. Show
the app name/icon and **SANDBOX** label on every screen. For an incompatible/noninstrumented target,
explain why the feature is unavailable and offer the normal instrumented-session preparation flow.

Before capture, summarize: **Inspect stored data and observe supported reads and writes in this
app's sandbox installation.** Offer metadata-only capture and values/previews capture. Keep existing
data, live activity, process coverage, and retained content independently understandable. Defaults:
capture bounded values/previews locally, mask sensitive content in the UI, and require explicit
selection for cross-profile content transfer or export. Display these choices before starting.

### Main navigation

Use four primary tabs with a persistent coverage/status strip:

1. **Overview:** baseline items, observed reads/writes/deletes, storage changes, capture gaps, and
   plain-language highlights. Counts name their meaning: distinct items versus operations.
2. **Stored data:** Preferences, Files, Images, Databases, and Other. Human-friendly categories first;
   optional folder-tree mode for advanced users. Search names/paths/keys and captured text, with
   explicit “Search captured content” scope. Never fetch every large file just to search it.
3. **Activity:** live timeline filtered by read/write/delete, type, process, outcome, and time.
   Group repeated operations with accurate counts and expandable individual evidence.
4. **Changes:** compare selected snapshots or observed versions; added, changed, removed, and
   unavailable. Preserve the distinction between a known write and a snapshot difference.

Pausing timeline scrolling must not pause capture. Provide separate **Pause capture** and
**Resume capture** actions with a visible gap marker. Keep the selected item stable during updates.
Use text/icons as well as color, accessible labels, scalable text, and touch-sized controls.

Illustrative layout; numbers are examples, not captured evidence:

```text
Storage Inspector                  Example App · SANDBOX
Recording · Main process · 2 coverage gaps      [Pause capture]
[Overview] [Stored data] [Activity] [Changes]

Present in baseline: 84 items      Observed: 32 reads · 8 writes
Preferences  18 keys     Files  41     Images  22     Databases  3

Recent activity
12:04:18  Read preference “theme”           “dark”
12:04:19  Requested save of “signed_in”     false -> true
12:04:20  Wrote avatar.jpg                  42 KiB accepted
12:04:21  Deleted temporary file            upload.tmp

Coverage: observation began after app startup. [View details]
```

### Detail presentation

| Data | Default viewer | Essential detail |
| --- | --- | --- |
| SharedPreferences | Key, typed value, last observation, read/write badges | Store name; in-memory vs disk; previous/new values; defaults; persistence evidence. |
| JSON/XML/text | Pretty formatting, expandable structure, line search | Encoding, path, captured length/range, truncation, source time; never execute HTML/XML content. |
| Images | Masked thumbnail grid; tap to reveal and zoom | Dimensions, detected format, size, captured version, optional local metadata; no automatic OCR or face analysis. |
| SQLite | Schema and paginated tables | Snapshot consistency, query/transaction outcome, redacted bindings, BLOB preview availability. |
| Binary/protobuf/custom | Type hint, metadata, bounded hex/text preview | “Decoder unavailable” or “Schema required”; never invent field meanings. |
| Audio/video/large documents | Metadata and format icon initially | Optional later bounded preview adapter; no autoplay or full-file loading. |

Every item detail has **Content**, **Activity**, **Changes**, and expandable **Technical evidence**.
Show human-readable names first, exact paths/types/APIs on demand. A file is not a new finding on
every read: aggregate its history while preserving raw event references.

### Copy and interpretation rules

- “Found 18 saved preferences in the baseline scan,” not “The app created 18 preferences.”
- “Read preference `theme`; returned `dark`,” not “Read the file” for an in-memory getter.
- “Requested asynchronous save,” not “Saved to disk” for `apply()` return.
- “Write returned 42 KiB,” not “Durably persisted 42 KiB” without stronger evidence.
- “File changed between 12:00 and 12:02,” when only two snapshots establish the difference.
- “Could not preview encrypted content,” not “Empty file.”
- “No reads observed during this capture,” not “This data is never read.”

Use deterministic local summaries and evidence-linked templates. A field name such as `token` can
support **Possible credential**, not a confirmed vulnerability. App-private plaintext is not by
itself proof of public exposure. Nearby storage/network timestamps do not prove data exfiltration.

### Empty, loading, and failure states

Distinguish scanning, recording, disconnected, profile locked, process unobserved, hooks unavailable,
empty directory, no observed activity, excluded content, preview not captured, encrypted/unknown,
file changed/deleted, partial scan, capture limit reached, import pending/failed, and capture ended.
Each state supplies a useful action where possible: retry, unlock, reconnect, refresh, or view coverage.
After target removal, retained previews work; uncaptured content says **Target removed — unavailable**.

## 7. Privacy, retention, and lifecycle

- Raw values and content can be necessary for inspection. Mask likely secrets and personal data by
  default; allow deliberate local reveal. Masking is a presentation choice, distinct from irreversible
  redaction. Explain whether original bytes were retained before capture starts.
- Treat images, filenames, preference keys, SQL bindings, EXIF, thumbnails, search indexes, and
  clipboard contents as potentially sensitive too. Disable automatic clipboard copying and include
  these surfaces in deletion/export review.
- Encrypt retained payloads with inspector-controlled, nonexported keys; exclude captures from backup.
  Prevent screenshot/recent-task previews on sensitive reveal screens. Do not require a cloud service.
- Metadata-only mode omits values, byte snippets, and thumbnails at collection; validate this at the
  receiver as well. Never send raw content to a service to summarize or classify it.
- Default raw-content expiry: 24 hours, with a clear **Keep selected evidence** choice. Retained
  redacted summaries remain until deleted. State exact expiry on the capture screen and report.
- Delete actions remove blobs, thumbnails, derived text/indexes, keys where scoped, and metadata
  references. Report deletion completion truthfully; flash-storage physical overwrite is not promised.
- End-session sequence: stop collection -> drain within deadline -> persist completion/gaps -> save
  selected bounded handoff artifact -> continue target cleanup. If saving fails, show the failure and
  offer retry or an explicit discard path before losing uncopied content.
- Personal receives a bounded redacted summary by default only when the user saves the report.
  Selected full previews use a separate, explicitly selected payload artifact with matching byte
  budgets, validation, temporary grants, and atomic/idempotent import. Do not overload URL evidence.

## 8. Proposed resource budgets

These are initial engineering limits and acceptance targets, not measured capabilities. Change them
only with documented device measurements; include effective limits in every capture's metadata.

| Resource | Initial limit / behavior |
| --- | --- |
| Baseline inventory | 10,000 entries, depth 32, 30-second scan budget; return partial inventory with continuation state. |
| Snapshot content | 20 MiB total automatic previews per scan; metadata continues within inventory limits. |
| Preference/text value | 64 KiB encoded capture per value or preview; mark truncation. |
| Live I/O byte sample | 4 KiB per operation by default; retain actual completed length separately. |
| Image input | At most 10 MiB requested content; inspect headers before decode, downsample to at most 1 megapixel; bounded decoder worker. |
| Database browse | 100 rows per page, 100 tables initially, 5-second query deadline; BLOBs use separate preview budgets. |
| Channel frame | 64 KiB encoded UTF-8 including envelope; binary chunks at most 32 KiB before encoding. |
| Agent event queue | 2 MiB or 2,000 events, whichever comes first; drop content first, count all loss. |
| Event rate | 200 retained events/second/process; aggregate supported repetitions and record overflow gaps. |
| Process scope | One connected process in first slice; at most four in the multi-process extension. |
| Retained session | 100,000 events and 100 MiB total, with 500 MiB overall inspector storage ceiling. |
| Cross-profile transfer | 5 MiB summary; separate selected-preview artifact at most 20 MiB, chunked and validated. |
| Responsiveness | Target p95 event-to-UI latency under 1 second at the stated event rate; no target ANR. |
| Hook overhead | Target p95 added hot-hook time under 2 ms on a declared physical test device; measure worst cases too. |
| Capture cost | Target added agent RSS under 32 MiB, excluding existing Gadget baseline; measure CPU/battery in a 15-minute scenario. |

Limit notifications must not recursively generate more captured storage activity. In all modes,
preserve target I/O behavior rather than blocking the app to retain every inspection event.

## 9. Delivery phases and acceptance gates

Use local requirement IDs `SSI-*`; these do not renumber the existing GSD roadmap. Every phase
delivers runnable behavior with its own status and exclusions. Protocol/framework adapters ship only
after the corresponding real-device path is verified.

### Phase A — feasibility and evidence contract (`SSI-01`)

- Establish compatible fixture, ABI/runtime, Java/native bridge readiness, storage root discovery,
  authentication, startup gap, and original/patched install identity.
- Define schemas, capture options, UI wireframes, explicit exclusions, and measured budget baseline.
- Gate: demonstrate reading one pre-existing preference and one private image from the real
  instrumented Work installation through the authenticated receiver. Do not use shell reads as
  evidence of shipping access. Record unavailable mechanisms before proceeding.

### Phase B — existing stored-data browser (`SSI-02`)

- Implement inventory, preferences, text/JSON/XML, image thumbnails, binary metadata, cancellation,
  Room/blob persistence, coverage states, and Stored data/item detail screens.
- Gate: seeded-by-the-fixture data reaches the UI; refresh handles concurrent changes; reopen after
  inspector process death retains captured content; malformed/large inputs remain bounded.

### Phase C — live SharedPreferences (`SSI-03`)

- Implement getters, defaults, editors, commit/apply/remove/clear, memory/disk distinction, live
  timeline, and value comparisons. Verify actual platform implementations rather than guessing
  hook class names or depending on one Android release's internals.
- Gate: scripted fixture actions produce correct values/order/outcomes without inspector reads
  appearing as target reads; false commit and asynchronous-persistence cases display correctly.

### Phase D — live files and images (`SSI-04`)

- Implement supported Java/native file I/O, descriptor tracking, rename/delete, partial buffers,
  correlation, repeated-event grouping, capture gaps, and version-specific previews.
- Gate: fixture Java and native operations reach the normal UI with correct byte counts and content;
  denied/failed/partial I/O, descriptor reuse, binary data, and images behave correctly. Target file
  hashes/results match a run without the observer, apart from declared instrumentation artifacts.

### Phase E — databases and structured stores (`SSI-05`)

- Add read-only SQLite snapshots/table browser and verified query/transaction observation.
- Add Preferences DataStore and explicitly versioned encrypted-preference/structured-store adapters.
  Keep schema-less and encrypted formats inspectable as files with truthful decoding limits.
- Gate: WAL/concurrent writers, rollback, query row consumption, DataStore updates, encryption,
  oversized BLOBs, and schema mismatch all have correct, reproducible outcomes.

### Phase F — coverage, multi-process, and recovery (`SSI-06`)

- Extend the single-client channel to authenticated target processes with independent sequences,
  fair scheduling, lifetime tracking, reconnect gaps, and exclusions for isolated/unreachable processes.
- Investigate earlier hook startup without undoing the loader's Java-bridge compatibility work.
  Keep delayed-start coverage explicit where early startup is not verified.
- Gate: two target processes, restart/PID reuse, unrelated app rejection, late callbacks, a locked
  profile, and overload cannot mix sessions or silently claim complete coverage.

### Phase G — retained reports and complete UX (`SSI-07`)

- Finish Overview, Activity, Changes, evidence links, redacted human-readable HTML report and
  structured JSON export, selected image/file attachments, expiry, and cross-profile import.
- Gate: full normal flow from instrumented preparation through stored/live inspection, save, cleanup,
  inspector restart, and reopened report succeeds on an emulator and a physical supported device.
  Old reports remain readable after schema upgrades; deleting a capture removes derived content.

Phases B–D form the first useful release slice: existing preferences/files/images plus supported live
reads and writes. Phase E broadens semantic coverage; F broadens process/startup coverage; G closes
the full report workflow. Do not market later-phase functionality as part of the first slice.

## 10. Verification plan

Before authoring executable mobile tests, explore the implemented screens and interaction sequence
with ARTEMIS as required by the repository instructions. Diagnose devices with `adb devices -l`;
ask for device choice if ambiguous. This planning task itself runs no device actions or tests.

Create a purpose-built target fixture with explicit UI actions and its own expected-operation ledger.
The fixture populates data using normal app APIs; never insert synthetic events into APK Scope's
capture store to prove acquisition. Use only dummy values and generated test images.

Required scenarios:

- Preferences: existing values, absent keys/defaults, every supported type, repeated reads, staged
  edits, apply/commit, remove/clear, concurrent editors, encrypted wrappers, and process restart.
- Files: existing/empty/text/binary/image content; reads/writes/appends; partial and failed operations;
  rename/delete; descriptor reuse/duplication; Unicode paths; symlink escapes; concurrent mutations.
- Content: malformed XML/JSON/images, huge dimensions, truncated encodings, long values, unsupported
  MIME types, unknown schema, opaque encrypted data, and bounded search/preview decoding.
- Storage types: credential/device-protected roots, target-owned external storage, cache/no-backup,
  SQLite WAL/rollback/transactions, DataStore, and unsupported custom formats.
- Scope: uninstrumented second app, second target process, Personal copy, stale credentials, forged
  package/session fields, target-controlled data, transport disconnect, replay, and overlarge frames.
- Observer effect: agent snapshots/hashes/previews do not inflate app activity; original I/O results,
  offsets, errors, and target usability remain correct; network inspector/console still work.
- UX/lifecycle: lock/unlock, process death during scan/write/import, empty vs unsupported states,
  pause scrolling vs pause capture, grouped event expansion, accessibility, retained preview after
  uninstall, redacted export, raw reveal, retention expiry, and deletion of blobs/indexes/thumbnails.

Verification layers: JVM tests for schemas/decoders/limits; Android instrumentation for persistence,
decoding and lifecycle; agent/fixture tests for hooks; ARTEMIS-led product exploration and runnable
UI tests with explicit waits; full Work Profile and physical-device acceptance. Keep raw results,
fixture identity, build/dirty diff, effective settings, timestamps, and requirement mapping together.

## 11. Completion checklist

- [ ] `SSI-01`: target identity, prerequisites, capability manifest, and access limits are verified.
- [ ] `SSI-02`: existing private preferences/files/images are browsable with captured-content persistence.
- [ ] `SSI-03`: supported preference reads/writes report correct values and persistence semantics.
- [ ] `SSI-04`: supported file operations and image versions appear as accurate live events.
- [ ] `SSI-05`: database/structured-store adapters have explicit supported versions and negative cases.
- [ ] `SSI-06`: process/startup gaps, loss accounting, authentication, and recovery are verified.
- [ ] `SSI-07`: readable reports, selection-based export/import, cleanup, and expiry work end to end.
- [ ] UI never equates not observed with not performed, snapshot presence with creation, or local
      storage access with network disclosure.
- [ ] Every release claim names the tested Android/ABI/runtime, storage adapters, process scope,
      hook coverage, and capture limits.

Implementation references: [Frida JavaScript APIs](https://frida.re/docs/javascript-api/) for Java/native
hooking and in-process operations; the linked Android/SQLite specifications above for storage semantics.
The proposed architecture, UX, budgets, and phase sequence are design decisions requiring verification.
