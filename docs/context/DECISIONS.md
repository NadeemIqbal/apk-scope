# Decision record

## Confirmed user direction

1. HTTP and HTTPS traffic reading and analysis are the first product priority.
2. Build a small certificate based HTTPS POC on a separate branch.
3. The agent prompt authorizes on device Work Profile inspection, an explicitly trusting fixture, focused validation, and documentation.
4. GSD is already initialized. Supply knowledge for that setup rather than reinitializing it.

## Accepted implementation boundaries

1. Reuse the existing VPN entry point; no second VPN.
2. Default monitoring continues without payload interception. POC interception is explicit opt in.
3. Preserve upstream certificate and hostname validation.
4. Limit the experiment to HTTP/1.1 over TLS, the fixture, and approved test destinations.
5. No pinning bypass, APK patching, root requirement, or broad protocol expansion.
6. Separate installed certificate status, application trust, and successfully decoded traffic.
7. Keep independent DPM evidence and deterministic risk rules unchanged.
8. Work stays on the POC branch with no commit, push, merge, or publication under this authorization.

## Working choices to verify

Bouncy Castle certificate construction, native TLS sockets, a loopback preamble, in memory retention, DPM certificate installation, and specific public fixture endpoints were reported by the implementation agent. They are implementation choices, not immutable product requirements. Modify them only to resolve a concrete POC defect or constraint, documenting why.

## Unresolved after closure

Broader app compatibility, production key storage and lifecycle, plaintext HTTP breadth, persistent captures, exports, WebSocket support, expanded protocol handling, and production release readiness need separately scoped decisions. Do not infer them from successful fixture interception.
