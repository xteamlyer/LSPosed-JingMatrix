plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.api"

    sourceSets {
        val main by getting
        main.apply {
            manifest.srcFile("api/api/src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("api/api/src/main/java"))
        }
    }

    defaultConfig {
        consumerProguardFiles("api/api/proguard-rules.pro")
    }

    buildFeatures {
        androidResources = false
        buildConfig = false
    }

}

dependencies {
    compileOnly(libs.androidx.annotation)
}
