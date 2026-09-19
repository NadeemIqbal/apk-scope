import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}
android {
    namespace = "com.nadeem.apkscope"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.nadeem.apkscope"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:crossprofile"))
    implementation(project(":core:report"))
    implementation(project(":core:database"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:staticanalysis"))
    implementation(project(":core:risk"))

    // POC: APK modification, alignment, and signing
    // Unified pipeline: extraction → file injection → ZIP alignment → signing
    implementation("com.android.tools.build:apksig:8.2.0")
    // ZIP writer that back-patches entry sizes (no data descriptors) — matches aapt output so
    // repacked res/xml assets load on-device. java.util.zip.ZipOutputStream cannot do this.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("io.github.reandroid:ARSCLib:1.3.5")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's unit-test stub jar throws on every org.json.* method call (verified — see
    // SandboxStatusReportTest's own history: it failed with RuntimeException before this was
    // added). A real, independent implementation of the same API, widely used for exactly this
    // case, lets SandboxStatusReport's JSON round-trip run as a plain local JVM test.
    testImplementation("org.json:json:20240303")

    // Checkpoint 5, item 36: real-device coverage for WorkNetworkObservationSink's bounded queue/
    // writer thread (needs a real Context for WorkEvidenceDatabaseProvider — not expressible as a
    // plain JVM unit test), matching core:database's own androidTest dependency versions.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Checkpoint 5.5, item 7: cold-start UI-restoration regression coverage needs a real Compose
    // hierarchy + real ViewModelStoreOwner/NavBackStackEntry ownership — not expressible as a JVM
    // ViewModel unit test (this bug class is specifically about *runtime ownership*, not logic).
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Pinned explicitly: the transitively-resolved espresso-core (3.5.x) throws
    // `NoSuchMethodException: InputManager.getInstance` on this project's targetSdk/compileSdk 36+
    // emulator image — a known espresso/platform version gap, not a test-logic issue.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    // Milestone 9 (Pixel 8 acceptance, fifth pass, item 1/4): WorkInstallPermissionRemediation
    // -InstrumentedTest needs to simulate "the user returns from the real Settings screen" for its
    // `requestOpenWorkInstallSettings` coverage — that call genuinely launches a system Settings
    // Activity via `startActivityForResult` and suspends until it finishes, so an automated test
    // needs a real, off-process way to close that screen (a `pressBack()`), not a mock.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// ── Frida loader payload → app assets ──────────────────────────────────────────
// The repack pipeline injects frida_loader.dex (the FridaLoaderFactory
// AppComponentFactory) into a *target* APK at patch time. That dex is built by the
// standalone :payload:fridaloader module (it is payload for the target, not APK
// Scope code), and consumed here purely as an asset to carry. Resolving it through
// a configuration keeps the modules isolated instead of reaching into the payload
// module's tasks; the stage task drops it into a generated assets dir wired into
// the merged assets, so ReVancedApkRepacker can read it via context.assets.
val fridaLoaderPayloadDeps = configurations.dependencyScope("fridaLoaderPayloadDeps").get()
val fridaLoaderPayload = configurations.resolvable("fridaLoaderPayload") {
    extendsFrom(fridaLoaderPayloadDeps)
}.get()

dependencies {
    add(
        fridaLoaderPayloadDeps.name,
        project(mapOf("path" to ":payload:fridaloader", "configuration" to "fridaLoaderDex")),
    )
}

val stageFridaLoaderDex = tasks.register<StageFridaLoaderAsset>("stageFridaLoaderDex") {
    payload.from(fridaLoaderPayload)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageFridaLoaderDex,
            StageFridaLoaderAsset::outputDir,
        )
    }
}

// Copies the resolved frida_loader.dex payload into a generated assets directory
// that AGP wires into the variant's merged assets.
abstract class StageFridaLoaderAsset @Inject constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val payload: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        fs.sync {
            from(payload)
            into(outputDir)
        }
    }
}
