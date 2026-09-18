package com.nadeem.apkscope.core.risk.audit

/**
 * Every [AuditRule.id] the v1 static audit catalog can produce — see
 * docs/SECURITY_AUDIT_RULES.md for the corresponding human-readable rule catalog table. Listed in
 * one place so persistence and test code have a single source of truth for "what rule IDs can this
 * engine version produce" (mirrors `core:risk`'s `RuleIds` convention).
 */
object AuditRuleIds {
 const val DEBUGGABLE_BUILD = "AUDIT_DEBUGGABLE_BUILD"
 const val SIGNATURE_INTEGRITY = "AUDIT_SIGNATURE_INTEGRITY"
 const val EXPORTED_SURFACE_RATIO = "AUDIT_EXPORTED_SURFACE_RATIO"
 const val ACCESSIBILITY_OVERLAY_TAPJACKING = "AUDIT_ACCESSIBILITY_OVERLAY_TAPJACKING"
 const val SMS_INTERNET_EXFIL_SURFACE = "AUDIT_SMS_INTERNET_EXFIL_SURFACE"
 const val BOOT_PERSISTENCE_NETWORK = "AUDIT_BOOT_PERSISTENCE_NETWORK"
 const val OUTDATED_TARGET_SDK = "AUDIT_OUTDATED_TARGET_SDK"
 const val NATIVE_CODE_UNVERIFIED = "AUDIT_NATIVE_CODE_UNVERIFIED"
 const val CLEARTEXT_TRAFFIC_ENABLED = "AUDIT_CLEARTEXT_TRAFFIC_ENABLED"
 const val BACKUP_ENABLED = "AUDIT_BACKUP_ENABLED"
 /** Milestone 10, Phase 10.3, MS10-NET01 — real `network_security_config.xml` content, not just manifest-attribute presence. */
 const val NETWORK_SECURITY_CONFIG_REVIEW = "AUDIT_NETWORK_SECURITY_CONFIG_REVIEW"
}
