import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins { id("com.android.application") }

// Bumped between generations so we can tell old code from new code at runtime.
val generation = (project.findProperty("hrGeneration") as String?)?.toInt() ?: 1

// module.prop's autoHotReload, so the "an app update reloads by itself" path can be exercised
// without it being on for every other test in the suite.
val autoHotReload = (project.findProperty("hrAutoReload") as String?)?.toBoolean() ?: false

// How many Java entry classes to declare. Hot reload is specified only for exactly one, so
// building with 2 is how the UNSUPPORTED answer is checked, and with 0 how a module the daemon
// should refuse outright is.
val entries = (project.findProperty("hrEntries") as String?)?.toInt() ?: 1

/**
 * Writes META-INF/xposed/. Generated rather than checked in, because three of the contracts under
 * test are decided by what is in it: autoHotReload, and the entry count that decides whether a
 * module is hot-reloadable at all.
 */
abstract class GenerateXposedResources : DefaultTask() {
    @get:Input abstract val generation: Property<Int>

    @get:Input abstract val autoHotReload: Property<Boolean>

    @get:Input abstract val entries: Property<Int>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDirectory.get().asFile.resolve("META-INF/xposed")
        dir.mkdirs()
        val gen = generation.get()
        dir.resolve("module.prop")
            .writeText(
                buildString {
                    appendLine("id=org.matrix.hrmodule")
                    appendLine("name=Hot Reload Test")
                    appendLine("version=gen$gen")
                    appendLine("versionCode=$gen")
                    appendLine("author=review-harness")
                    appendLine("description=API 102 hot reload conformance harness")
                    appendLine("minApiVersion=102")
                    appendLine("targetApiVersion=102")
                    appendLine("staticScope=false")
                    appendLine("autoHotReload=${autoHotReload.get()}")
                }
            )
        dir.resolve("java_init.list")
            .writeText(
                buildString {
                    val count = entries.get()
                    if (count >= 1) appendLine("org.matrix.hrmodule.ModuleMain")
                    if (count >= 2) appendLine("org.matrix.hrmodule.SecondEntry")
                }
            )
        dir.resolve("scope.list").writeText("org.matrix.hrtarget\n")
    }
}

val hrGeneration = generation
val hrAutoHotReload = autoHotReload
val hrEntries = entries

val generateXposedResources =
    tasks.register<GenerateXposedResources>("generateXposedResources") {
        generation.set(hrGeneration)
        autoHotReload.set(hrAutoHotReload)
        entries.set(hrEntries)
    }

android {
    namespace = "org.matrix.hrmodule"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "org.matrix.hrmodule"
        minSdk = 31
        targetSdk = 37
        versionCode = generation
        versionName = "gen$generation"
        buildConfigField("String", "GENERATION", "\"V$generation\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes { release { isMinifyEnabled = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.resources?.addGeneratedSourceDirectory(
        generateXposedResources,
        GenerateXposedResources::outputDirectory,
    )
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    // Never packaged; see legacystub/build.gradle.kts for why linking against it is the only
    // legacy-API test that survives dex obfuscation.
    compileOnly(project(":legacystub"))
}
