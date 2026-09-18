package com.nadeem.apkscope.core.staticanalysis

import java.io.Serializable

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-SECRET01-03 — categories this scanner recognizes by
 * *structural format*, not by generic "looks random" entropy scoring. Format-based fingerprinting
 * (a fixed prefix/length/checksum shape specific to one credential type) is the same approach
 * industry secret-scanners (gitleaks, trufflehog) use precisely because it is what lets a public
 * identifier (a Firebase project id, a package name, a version string) stay unflagged without a
 * separate allow-list: those values simply do not have any of these shapes.
 */
enum class SecretCategory(val title: String) : Serializable {
 PRIVATE_KEY_BLOCK("Private Key Block"),
 AWS_ACCESS_KEY("AWS Access Key ID"),
 GOOGLE_API_KEY("Google API Key"),
 SLACK_TOKEN("Slack Token"),
 JWT("JSON Web Token"),
}

/**
 * A secret candidate this scanner found. [maskedValue] is the *only* representation of the matched
 * value this type ever carries — see [SecretCandidateScanner.mask]'s own doc for why the raw value is
 * never retained past the moment of matching (MS10-SECRET02: masked in every output surface, which
 * this type structurally enforces rather than merely promises).
 */
data class SecretFinding(
 val category: SecretCategory,
 val maskedValue: String,
 val dexEntry: String,
 val callingClass: String?,
 val callingMethod: String?,
) : Serializable
