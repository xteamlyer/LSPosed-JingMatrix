plugins { id("com.android.application") }

// Bumped between generations so we can tell old code from new code at runtime.
val generation = (project.findProperty("hrGeneration") as String?)?.toInt() ?: 1

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

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
