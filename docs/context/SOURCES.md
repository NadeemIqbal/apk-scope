# Sources and confidence

## Primary task sources

1. User conversation in this thread through 10 September 2026: product intent, priority, accepted agent prompts, roadmap suggestions, and review concerns.
2. User supplied Pasted text(20260910-145120).txt: implementation narrative, action log, test report, paths, and reproduction instructions. Reported claims were not independently checked against source code.
3. Earlier README and media context pasted by the user: historical product semantics and documentation boundaries. Treat these as baseline context, not current code proof.

No actual repository checkout, installed GSD configuration, Git diff, raw test reports, or live device was available to the pack author. Do not assign verified status from this pack alone.

## GSD compatibility

The upstream entry page https://github.com/gsd-build/get-shit-done was consulted and points to https://github.com/open-gsd/gsd-core . Individual current templates could not be retrieved in this environment. This pack deliberately avoids asserting an exact installed version, command syntax, configuration schema, state frontmatter, or phase numbering.

The existing repo installation is authoritative for GSD mechanics. Merge the planning content into its native templates and reference supporting context explicitly.

## Technical references already used in the discussion

Android certificate trust and debug configuration:
https://developer.android.com/privacy-and-security/security-config

AdGuard HTTPS filtering explanation:
https://adguard.com/kb/general/https-filtering/what-is-https-filtering/

AdGuard Android certificate store limitations:
https://adguard.com/kb/adguard-for-android/solving-problems/https-certificate-for-rooted/

WebSocket protocol:
https://www.rfc-editor.org/rfc/rfc6455.html

These explain platform and protocol concepts, not proof of APK Scope behavior. Consult current primary documentation when making implementation decisions.
