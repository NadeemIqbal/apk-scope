# Future Capabilities

This is a feasibility and prioritization map, not an implementation authorization or support declaration. Several items already have development code. Reconcile their status against [Supervisor Context](SUPERVISOR_CONTEXT.md) and current evidence before planning new work.

All capabilities require the acceptance contract in [Verification Strategy](VERIFICATION_STRATEGY.md). Preserve the current no root, local analysis, and evidence integrity constraints.

## Priority order

1. Close the current genuine traffic viewer, persisted correlation, attribution, and lifecycle gaps.
   **Closed 2026-09-14 (Milestone 9)** — see `.planning/STATE.md`'s "Final Milestone 9 requirement
   reconciliation" for the criterion-by-criterion evidence, including a physical-device run of the full
   viewer/correlation/persistence/lifecycle path. This closure covers HTTP/1.1 and HTTP/2 specifically;
   it does not extend to gRPC or SSE product-level verification (priority 3 below), which remain
   unstarted.
2. Harden existing HTTP, HTTPS, WebSocket, filtering, and static analysis paths.
3. Establish product level HTTP/2, gRPC, and SSE support for explicitly tested subsets. **HTTP/2 is
   done** (product verified, including physical-device evidence). **gRPC and SSE remain at
   component/engine-integration verification only** — real client/server exchange through the
   production relay has been proven, but neither has been re-driven through the real
   fixture-in-Work-Profile UI end to end the way HTTP/2 has. This is the next unstarted item in this
   priority order; nothing in Milestone 9's work began it.
4. Improve classification, reporting, and correlation without overstating certainty.
5. Research QUIC and HTTP/3 inspection separately before authorizing a substantial implementation.

Do not restart completed work simply because it appears here. Inspect existing code and extend only the missing coverage or behavior.

## Feasible on normal Android

These capabilities can use ordinary application privileges and local APK access. Feasible does not mean currently complete.

| Capability | Intended scope | Required acceptance |
| --- | --- | --- |
| Deeper DEX analysis | Bounded multidex inspection with extraction provenance and coverage accounting | Known fixtures, malformed inputs, cancellation, memory/time bounds, and truthful skipped coverage |
| Embedded URL extraction | Literal URL candidates and their class/method references where available | Distinguish unused strings, code references, duplicates, and unknown/generated values |
| SDK signature detection | Versioned signatures with confidence and supporting matches | Positive and negative fixtures, obfuscation limits, no invented SDK version or execution claim |
| Selected sensitive API references | A curated, documented set of API references relevant to security analysis | Exact reference location, catalog version, negative tests, no claim of invocation |
| Traffic filters | Search captured destination, protocol, status, and readable content | Correct combined filters, session boundaries, redaction preservation, and responsive bounded search |
| Improved reports | Evidence links, coverage, pending/import states, and clearer findings | Reopen retained reports, stable references, rule versions, and no loss of provenance |
| Session comparison | Differences between comparable saved observations | Explain unequal exercise, capture duration, settings, and coverage; do not infer code changes from traffic differences |

DEX inspection does not promise recovery of encrypted strings, downloaded code, native behavior, whole program control flow, or runtime values. Catalog matches identify evidence of inclusion, not maliciousness.

## Feasible with limitations

| Capability | Conditions and boundary | Required acceptance |
| --- | --- | --- |
| Plaintext HTTP inspector | Traffic traverses the VPN and is readable; the target permits cleartext | Real fixture requests and responses, framing, timing, capture limits, and viewer integration |
| Optional HTTPS inspection | Target explicitly trusts the inspection CA and uses a supported TLS/protocol path | Both TLS connections verified, negative trust cases, payload capture, and truthful unsupported states |
| WebSocket recorder | Readable WS or compatible inspected WSS; only documented handshake and extension forms | Both directions, event times, message boundaries, fragmentation, close/control handling |
| HTTP/2 | Supported readable or inspected transport; explicit ALPN and stream behavior | Real VPN route, concurrent streams, flow control, body fidelity, viewer and persistence |
| gRPC | Readable supported HTTP/2 path | Real messages, metadata and trailers; individual verification for unary and each streaming mode |
| Server Sent Events | Readable HTTP response with supported streaming behavior | Incremental events before EOF, long duration bounds, cancellation, and reconnect semantics |
| Better network classification | Available packet, handshake, DNS, DPM, and transaction evidence | Per label source and confidence, negative cases, unknown states, and no port only certainty |
| Improved evidence correlation | Valid bounded transport and defensible target/session attribution | Exact versus host matching, redaction ambiguity, idempotency, late evidence, and persistence |
| PCAP or HAR export | Explicitly selected evidence with documented capture boundaries | Valid format, redaction, bounded export, and clear distinction between packets and decoded transactions |
| Additional dynamic observations | Events exposed through permitted Android APIs, such as session lifecycle, policy outcomes, and available DPM evidence | Name the API and role, verify device/API coverage, and distinguish a policy state from actual target behavior |

gRPC message framing does not make arbitrary Protobuf fields understandable. Human readable field names and semantics may require schemas. HTTP status 200 does not establish gRPC success; inspect gRPC status. See the [gRPC protocol](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md).

HTTP/2 support must name its tested transport and protocol subset. Do not assume that TLS HTTP/2 support also covers cleartext upgrades, WebSocket extended CONNECT, or every extension. See [HTTP/2](https://www.rfc-editor.org/rfc/rfc9113).

SSE is an event stream over HTTP, not a WebSocket variant. Its support needs incremental parsing and lifecycle handling. See [Server Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html).

Network categories such as advertising, analytics, or tracking require explicit classification sources and uncertainty. Shared hosting, CDN addresses, ports, and static SDK signatures are insufficient by themselves to establish a connection's purpose.

## Research required

### QUIC observational classification

Development reports describe an experimental parser. Audit it before extending it. Determine which packet forms and versions can be classified from available bytes, what context is required, and when the result must remain unknown.

UDP port 443 is not proof of QUIC. QUIC identification is not proof of HTTP/3. QUIC is a transport that can carry different application protocols. See [QUIC transport](https://www.rfc-editor.org/rfc/rfc9000).

Acceptance requires real fixture traffic, unrelated UDP negatives, malformed and truncated datagrams, version boundaries, bounded state, and accurate labels. Passive classification does not authorize encrypted application payload claims.

### HTTP/3 and QUIC payload inspection

Evaluate a userspace QUIC termination design, compatible client trust, protocol negotiation, UDP routing, connection migration, HTTP/3 streams, QPACK, packaging, dependencies, and resource cost. HTTP/3 runs over QUIC; a TCP TLS inspector is not sufficient. See [HTTP/3](https://www.rfc-editor.org/rfc/rfc9114).

Root is not inherently necessary for every possible compatible client proxy design. However, feasibility for this architecture is unproven, and arbitrary third party traffic is not generally decryptable merely by observing QUIC or installing a CA.

The research deliverable is a decision document, prototype evidence if authorized, explicit unsupported cases, and measured cost. Do not publish guessed binary size or ABI requirements as established facts.

Blocking UDP to induce TCP fallback is a separate policy experiment, not HTTP/3 inspection. It may change or break target behavior and must never be silent.

### Additional runtime visibility

Evaluate further observations only when a public Android API and a permitted role provide them. Document consent, collection scope, attribution, battery impact, and blind spots before implementation.

Do not expand into Accessibility driven surveillance, arbitrary log collection, or target instrumentation simply to fill a report. These are not authorized substitutes for missing platform access.

## Not realistically available to this product without root or privileged access

* Arbitrary target process memory inspection or systemwide syscall tracing.
* Reading other applications' private data directories as a normal application.
* Unrestricted kernel instrumentation or complete devicewide packet/process attribution.
* Installing a system trust anchor through ordinary application privileges.
* Bypassing profile boundaries and Android access controls to collect otherwise unavailable data.

These are exclusions, not a planned privileged edition. Root or privileged access would change the threat model and still would not guarantee universal visibility.

Some goals are stronger impossibilities rather than privilege problems: guaranteed malware detection, proof that unexercised behavior cannot occur, and universal decryption of arbitrary pinned or application encrypted traffic. Do not advertise them even as future root features.

## Roadmap governance

Each selected capability needs a requirement identifier, scoped acceptance criteria, a status supported by evidence, and explicit exclusions. Preserve existing GSD numbering and deferred items.

Before adding a capability, determine whether the missing work is parsing, transport, product integration, persistence, UI, or verification. A decoder is not the feature when the user cannot obtain trustworthy results through the normal workflow.
