# APK Scope GSD context pack

Prepared 10 September 2026 from the supplied conversation and agent report.

This is a handoff pack, not a repository audit or a verified GSD state export. The repository and installed GSD version were unavailable when this pack was prepared. The supplied planning files are content candidates, not guaranteed machine compatible templates. Use the installed GSD templates and existing project state as the schema authority.

## Import into the initialized repository

1. Extract this pack outside the repository first. Include the .planning directory when viewing the archive.
2. Read the repository instructions, existing GSD configuration, planning files, and current Git status.
3. Inspect the checkout and reconcile every reported implementation claim with code and test artifacts.
4. Merge the candidate PROJECT.md, REQUIREMENTS.md, ROADMAP.md, and STATE.md content into the existing GSD equivalents. Preserve existing frontmatter, milestone identifiers, phase numbering, history, and unrelated work. Do not replace existing files wholesale.
5. Add the supporting docs/context files and explicitly reference them from the active project and phase context. Merely placing custom documents in the repo does not ensure GSD reads them.
6. Use the installed GSD workflow to generate the active phase plan. Do not reinitialize GSD, change execution permissions, or enable automatic commits.
7. Resume the existing HTTPS POC branch and close the verification gaps before starting later roadmap work.

No config.json, AGENTS.md, GEMINI.md, generated plan, or verification PASS record is included intentionally. Preserve local instructions. Use installed commands rather than guessing a slash command from another GSD version.

## Agent import prompt

Read IMPORT.md and all files in this context pack. Inspect the initialized GSD setup and repository before merging context. Preserve its schema, existing history, and uncommitted work. Reconcile the report against implementation. Treat the HTTPS POC as reported implemented but incompletely verified. Map the closure requirements to the next appropriate phase without duplicating completed implementation. Continue the authorized small POC on poc/https_inspection. Do not commit, push, merge, publish, expand scope, or execute the future backlog. Record evidence and unknowns explicitly.

## Evidence vocabulary

Confirmed instruction: an explicit user requirement in the supplied conversation.
Reported baseline: historical project behavior described in that conversation.
Reported implementation: the attached agent claims implementation or passing tests.
Verified: reserve for evidence inspected in the actual repository or reproduced at runtime.
Proposed: future work, without implementation authorization.

## Files

.planning/PROJECT.md: purpose, value, scope, constraints.
.planning/REQUIREMENTS.md: acceptance requirements for the active milestone.
.planning/ROADMAP.md: closure phases and deferred product roadmap.
.planning/STATE.md: resumption state and next action.
docs/context/BASELINE.md: existing product and architecture context.
docs/context/HTTPS_POC.md: implementation report and verification gaps.
docs/context/DECISIONS.md: durable scope and engineering decisions.
docs/context/VERIFICATION.md: evidence checklist and completion conditions.
docs/context/SOURCES.md: provenance and confidence limitations.
