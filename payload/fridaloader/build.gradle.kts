import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

// ── :payload:fridaloader ───────────────────────────────────────────────────────
// Produces frida_loader.dex: the compiled android.app.AppComponentFactory
// (FridaLoaderFactory) that the repack pipeline injects into a *target* APK as an
// extra classesN.dex. It is NOT part of APK Scope — it never runs in this app. It
// is target-independent payload (identical bytes for every target; per-target data
// is injected separately at patch time), so it is compiled once, here, and exposed
// to :app as an asset. It must be produced ahead of time because there is no Java
// compiler on an Android device — it cannot be generated during patching at runtime.
//
// The dex is rebuilt from source on every build, so it can never drift from
// FridaLoaderFactory.java or go missing (the failure mode that made a patched target
// crash at launch with ClassNotFoundException in LoadedApk.createAppFactory).

plugins { base }

abstract class GenerateFridaLoaderDex @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFile: RegularFileProperty

    @get:Input abstract val androidJarPath: Property<String>
    @get:Input abstract val javacPath: Property<String>
    @get:Input abstract val d8Path: Property<String>
    @get:Input abstract val minApi: Property<Int>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        val work = File(out.parentFile, "frida_loader_work")
        work.deleteRecursively(); work.mkdirs()
        out.mkdirs()
        File(out, "frida_loader.dex").delete()

        val classesDir = File(work, "classes").apply { mkdirs() }
        val androidJar = androidJarPath.get()

        // 1) Compile the single source file against android.jar.
        execOps.exec {
            commandLine(
                javacPath.get(),
                "-source", "1.8", "-target", "1.8",
                "-classpath", androidJar,
                "-d", classesDir.absolutePath,
                sourceFile.get().asFile.absolutePath,
            )
        }
        val classFiles = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.absolutePath }
            .toList()
        require(classFiles.isNotEmpty()) { "javac produced no .class files from ${sourceFile.get().asFile}" }

        // 2) Dex the compiled class(es) into the output dir.
        execOps.exec {
            commandLine(
                buildList {
                    add(d8Path.get())
                    add("--min-api"); add(minApi.get().toString())
                    add("--lib"); add(androidJar)
                    add("--output"); add(out.absolutePath)
                    addAll(classFiles)
                },
            )
        }

        // d8 always names its output classes.dex; consumers read frida_loader.dex.
        val emitted = File(out, "classes.dex")
        val target = File(out, "frida_loader.dex")
        require(emitted.exists()) { "d8 did not produce classes.dex in $out" }
        target.delete()
        check(emitted.renameTo(target)) { "could not rename classes.dex -> frida_loader.dex" }
        logger.lifecycle("Built ${target.name} (${target.length()} bytes) from ${sourceFile.get().asFile.name}")
    }
}

val generateFridaLoaderDex = tasks.register<GenerateFridaLoaderDex>("generateFridaLoaderDex") {
    group = "payload"
    description = "Compiles + dexes FridaLoaderFactory into frida_loader.dex payload."

    // Resolve the SDK location from standard sources (local.properties / env).
    val sdkDir: File = run {
        val props = Properties()
        val lp = rootProject.file("local.properties")
        if (lp.exists()) lp.inputStream().use { props.load(it) }
        val dir = props.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Android SDK not found (set local.properties sdk.dir or ANDROID_HOME).")
        File(dir)
    }

    sourceFile.fileValue(file("src/main/java/com/nadeem/apkscope/FridaLoaderFactory.java"))

    // android.jar: highest platform installed. Any recent android.jar resolves the
    // framework types this class references (AppComponentFactory is API 28+).
    val androidJar = File(sdkDir, "platforms").listFiles()
        ?.map { File(it, "android.jar") }
        ?.filter { it.exists() }
        ?.maxByOrNull { it.parentFile.name }
        ?: error("No android.jar found under ${File(sdkDir, "platforms")} — is the Android SDK installed?")
    androidJarPath.set(androidJar.absolutePath)

    // d8 from the highest installed build-tools.
    val d8 = File(sdkDir, "build-tools").listFiles()
        ?.map { File(it, "d8") }
        ?.filter { it.exists() }
        ?.maxByOrNull { it.parentFile.name }
        ?: error("No d8 found under ${File(sdkDir, "build-tools")} — install Android build-tools.")
    d8Path.set(d8.absolutePath)

    // javac from the JDK running Gradle (no reliance on PATH).
    javacPath.set(File(System.getProperty("java.home"), "bin/javac").absolutePath)

    // Matches APK Scope's minSdk; the loader class targets the same floor.
    minApi.set(30)

    outputDir.set(layout.buildDirectory.dir("frida_loader"))
}

// Expose frida_loader.dex to consumers (e.g. :app) as a resolvable artifact,
// keeping projects isolated instead of reaching into this module's tasks.
val fridaLoaderDex = configurations.consumable("fridaLoaderDex").get()
artifacts.add(fridaLoaderDex.name, generateFridaLoaderDex.flatMap { it.outputDir.file("frida_loader.dex") }) {
    builtBy(generateFridaLoaderDex)
}

tasks.named("assemble") { dependsOn(generateFridaLoaderDex) }
