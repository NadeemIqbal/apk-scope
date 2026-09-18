package com.nadeem.apkscope.core.staticanalysis

import java.util.zip.ZipFile

/**
 * High-level framework or development platform used to construct the APK.
 */
enum class AppPlatform(val displayName: String, val category: String) {
    FLUTTER("Flutter", "Cross-Platform"),
    REACT_NATIVE("React Native", "Cross-Platform"),
    NATIVE_KOTLIN("Native (Kotlin)", "Native"),
    NATIVE_JAVA("Native (Java)", "Native"),
    UNITY("Unity", "Game Engine"),
    CORDOVA("Apache Cordova", "Hybrid WebView"),
    CAPACITOR_IONIC("Capacitor / Ionic", "Hybrid WebView"),
    XAMARIN_MAUI("Xamarin / .NET", "Cross-Platform"),
    GODOT("Godot Engine", "Game Engine"),
    UNREAL_ENGINE("Unreal Engine", "Game Engine"),
    UNKNOWN("Android", "Unknown");

    companion object {
        fun fromName(name: String?): AppPlatform {
            if (name.isNullOrBlank()) return UNKNOWN
            return try {
                valueOf(name)
            } catch (_: Exception) {
                entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) } ?: UNKNOWN
            }
        }
    }
}

/**
 * Detailed platform detection information.
 */
data class AppPlatformInfo(
    val platform: AppPlatform,
    val details: String? = null,
)

/**
 * Analyzes the APK container entries and declared components to identify the development framework.
 */
object PlatformDetector {

    fun detect(zip: ZipFile, componentNames: List<String>): AppPlatformInfo {
        var hasFlutterLibs = false
        var hasFlutterAssets = false

        var hasReactNativeLibs = false
        var hasReactNativeBundle = false
        var hasHermes = false
        var hasJsc = false

        var hasUnityLibs = false
        var hasUnityData = false
        var hasIl2cpp = false
        var hasMono = false

        var hasCordovaJs = false
        var hasCapacitorConfig = false

        var hasXamarinLibs = false
        var hasXamarinAssemblies = false

        var hasGodotLibs = false
        var hasGodotPck = false

        var hasUnrealLibs = false

        var hasKotlinMetadata = false

        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name

            // 1. Flutter checks
            if (name.startsWith("lib/") && (name.endsWith("/libflutter.so") || name.endsWith("/libapp.so"))) {
                hasFlutterLibs = true
            }
            if (name.startsWith("assets/flutter_assets/")) {
                hasFlutterAssets = true
            }

            // 2. React Native checks
            if (name.startsWith("lib/")) {
                if (name.endsWith("/libreactnativejni.so") || name.endsWith("/libreactnative.so") || name.endsWith("/libfbjni.so")) {
                    hasReactNativeLibs = true
                }
                if (name.endsWith("/libhermes.so")) {
                    hasHermes = true
                }
                if (name.endsWith("/libjsc.so")) {
                    hasJsc = true
                }
            }
            if (name == "assets/index.android.bundle") {
                hasReactNativeBundle = true
            }

            // 3. Unity checks
            if (name.startsWith("lib/")) {
                if (name.endsWith("/libunity.so")) {
                    hasUnityLibs = true
                }
                if (name.endsWith("/libil2cpp.so")) {
                    hasIl2cpp = true
                }
                if (name.endsWith("/libmono.so") || name.endsWith("/libmonosgen-2.0.so")) {
                    hasMono = true
                }
            }
            if (name.startsWith("assets/bin/Data/")) {
                hasUnityData = true
            }

            // 4. Cordova & Capacitor
            if (name.startsWith("assets/www/cordova.js") || name.startsWith("assets/www/cordova_plugins.js")) {
                hasCordovaJs = true
            }
            if (name.startsWith("assets/capacitor.config.json") || name.startsWith("assets/capacitor.plugins.json")) {
                hasCapacitorConfig = true
            }

            // 5. Xamarin / .NET
            if (name.startsWith("lib/") && (name.endsWith("/libmonodroid.so") || name.endsWith("/libxamarin-app.so"))) {
                hasXamarinLibs = true
            }
            if (name.startsWith("assemblies/") || name.endsWith(".dll")) {
                hasXamarinAssemblies = true
            }

            // 6. Godot
            if (name.startsWith("lib/") && name.endsWith("/libgodot_android.so")) {
                hasGodotLibs = true
            }
            if (name.startsWith("assets/") && name.endsWith(".pck")) {
                hasGodotPck = true
            }

            // 7. Unreal Engine
            if (name.startsWith("lib/") && (name.endsWith("/libUnreal.so") || name.endsWith("/libUE4.so"))) {
                hasUnrealLibs = true
            }

            // 8. Kotlin
            if (name.startsWith("META-INF/") && name.endsWith(".kotlin_module")) {
                hasKotlinMetadata = true
            }
            if (name == "kotlin-tooling-metadata.json" || name.startsWith("kotlin/")) {
                hasKotlinMetadata = true
            }
        }

        // Component class checks
        val hasFlutterComponent = componentNames.any { it.startsWith("io.flutter.") }
        val hasReactNativeComponent = componentNames.any { it.startsWith("com.facebook.react.") }
        val hasUnityComponent = componentNames.any { it.contains("unity3d") }
        val hasCordovaComponent = componentNames.any { it.contains("org.apache.cordova") }
        val hasCapacitorComponent = componentNames.any { it.contains("com.getcapacitor") }
        val hasGodotComponent = componentNames.any { it.contains("org.godotengine.godot") }

        // Evaluation hierarchy
        if (hasFlutterLibs || hasFlutterAssets || hasFlutterComponent) {
            return AppPlatformInfo(AppPlatform.FLUTTER, "Dart AOT")
        }

        if (hasReactNativeLibs || hasReactNativeBundle || hasReactNativeComponent) {
            val details = when {
                hasHermes -> "Hermes Engine"
                hasJsc -> "JavaScriptCore"
                else -> "JavaScript"
            }
            return AppPlatformInfo(AppPlatform.REACT_NATIVE, details)
        }

        if (hasUnityLibs || hasUnityData || hasUnityComponent) {
            val details = when {
                hasIl2cpp -> "IL2CPP Scripting"
                hasMono -> "Mono Scripting"
                else -> "Unity Engine"
            }
            return AppPlatformInfo(AppPlatform.UNITY, details)
        }

        if (hasGodotLibs || hasGodotPck || hasGodotComponent) {
            return AppPlatformInfo(AppPlatform.GODOT, "GDScript / C#")
        }

        if (hasUnrealLibs) {
            return AppPlatformInfo(AppPlatform.UNREAL_ENGINE, "C++ Engine")
        }

        if (hasCapacitorConfig || hasCapacitorComponent) {
            return AppPlatformInfo(AppPlatform.CAPACITOR_IONIC, "Capacitor Hybrid")
        }

        if (hasCordovaJs || hasCordovaComponent) {
            return AppPlatformInfo(AppPlatform.CORDOVA, "WebView Hybrid")
        }

        if (hasXamarinLibs || hasXamarinAssemblies) {
            return AppPlatformInfo(AppPlatform.XAMARIN_MAUI, ".NET / C#")
        }

        if (hasKotlinMetadata) {
            return AppPlatformInfo(AppPlatform.NATIVE_KOTLIN, "Kotlin Android")
        }

        return AppPlatformInfo(AppPlatform.NATIVE_JAVA, "Java Android")
    }
}
