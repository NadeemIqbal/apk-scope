# Flutter Artifact Recovery Acceptance Contract

Requirement ID: `FLUTTER-RECOVERY01`

Status: **source implemented; component verified**. The Android viewer compiles, but the normal
on-device import → Components → Flutter Artifacts interaction has not yet been product-verified on a
physical device. Do not describe this capability as original Dart source reconstruction.

## Supported scope

For an APK detected as Flutter, recover locally available evidence from:

* `lib/<abi>/libapp.so` Dart AOT ELF files;
* legacy Dart VM/isolate snapshot entries;
* `assets/flutter_assets/**`, including direct UTF-8 text, asset manifests, localization files,
  shader source, and zlib-compressed `NOTICES.Z` content.

Binary recovery includes printable ASCII, valid UTF-8, and UTF-16LE values. Results retain the APK
entry, uncompressed byte offset, encoding, and a conservative category (Dart URI, URL, source path,
asset path, symbol-like value, message, or other). Duplicates across ABIs are merged while retaining up
to four locations. When the value limit is full, later high-signal values can displace lower-signal
generic values.

## Explicit exclusions

The result is not the original `.dart` project, widget tree, local variable names, source formatting,
or exact control flow. Obfuscation, compiler tree shaking, encrypted/generated strings, downloaded
code, and values constructed only at runtime remain outside static coverage. `libflutter.so` is
inventoried but not mined as target application logic because it is framework engine code.

## Resource and privacy contract

* 64 MiB maximum uncompressed read per APK entry.
* 96 MiB maximum total inspected bytes.
* 8 MiB maximum decompressed notices payload.
* 2,048 maximum Flutter artifact entries.
* 5,000 maximum deduplicated recovered values, 2,048 characters per value, and four locations/value.
* 64 direct text previews, 24,000 characters each, and a 240,000-character rendered report.
* All processing is local; the APK and recovered content are not uploaded.
* Reached bounds and skipped binary assets are reported explicitly.

## Verification evidence

JVM fixtures cover direct assets, ELF/AOT literals and architecture, URL/Dart-URI classification,
UTF-16LE, cross-ABI deduplication and provenance, compressed notices, high-signal retention after the
value cap, and non-Flutter negative handling.

A separately generated real Flutter release APK was also inspected during implementation. It contained
three real `libapp.so` ABI variants plus a packaged JSON asset. The production inspector recovered a
distinctive UI string and URL specifically from `libapp.so`, recovered the direct asset text, identified
15 Flutter artifacts, inspected 8,993,619 bytes, and truthfully reported the 5,000-value cap. This is
component evidence against a real release artifact, not on-device product-UI acceptance.
