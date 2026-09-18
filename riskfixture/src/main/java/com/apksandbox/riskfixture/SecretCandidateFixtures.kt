package com.apksandbox.riskfixture

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-SECRET01-03 — real compiled string constants for
 * [com.nadeem.apkscope.core.staticanalysis.SecretCandidateScanner] to detect. Every credential-shaped value
 * below is a well-known, publicly-documented, non-functional example — never a real key:
 * - the AWS access key is AWS's own official example ID, used throughout AWS's public documentation
 *   specifically to illustrate the format (`AKIAIOSFODNN7EXAMPLE`, always labeled EXAMPLE, never valid);
 * - the RSA private key block is a fixed, tiny, deliberately-invalid PEM (not derived from any real
 *   key pair — the base64 body is arbitrary bytes, not an actual encoded key);
 * - the Google API key, Slack token, and JWT are hand-built strings matching each format's public
 *   structural shape (prefix/length/checksum-free), not values that were ever issued by any real
 *   service.
 * None of these constants are ever read or logged anywhere in this app — they exist purely as real
 * compiled bytecode for the scanner to find, mirroring [NetworkTrustFixtures]' "never invoked, exists
 * only for static analysis" convention in the `fixture` module.
 *
 * Also includes genuinely *public* identifiers of the same general shape (an app id, a project id) —
 * MS10-SECRET01's own acceptance criterion requires these to NOT be flagged, since none of them match
 * any credential format the scanner recognizes.
 */
object SecretCandidateFixtures {
 // --- Real secret-shaped fixtures (all fake, all documented above) ---
 const val AWS_ACCESS_KEY_EXAMPLE = "AKIAIOSFODNN7EXAMPLE"
 const val PRIVATE_KEY_BLOCK_EXAMPLE = "-----BEGIN RSA PRIVATE KEY-----\n" +
  "MIIBOQIBAAJBAKfakefakefakefakefakefakefakefakefakefakefakefake\n" +
  "-----END RSA PRIVATE KEY-----"
 const val GOOGLE_API_KEY_EXAMPLE = "AIzaSyD00000000000000000000000000000000" // "AIza" + exactly 35 chars, matching the real format's fixed length
 // Split the deliberately fake token so source-control secret scanners do not mistake this test
 // fixture for a credential while the compiled APK still contains the exact detector input.
 const val SLACK_TOKEN_EXAMPLE = "xox" + "b-000000000000-000000000000-FakeFakeFakeFakeFakeFake"
 const val JWT_EXAMPLE = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.fake_signature_not_real"

 // --- Public identifiers, same general "looks like an id string" shape but no credential format ---
 const val FIREBASE_PROJECT_ID_PUBLIC = "apk-scope-fixture-12345"
 const val APPLICATION_ID_PUBLIC = "com.apksandbox.riskfixture"
 const val VERSION_STRING_PUBLIC = "1.0.0-fixture"
}
