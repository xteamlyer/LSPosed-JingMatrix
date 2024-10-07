plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.service"

    sourceSets {
        val main by getting
        main.apply {
            manifest.srcFile("service/service/src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("service/service/src/main/java"))
            aidl.setSrcDirs(listOf("service/interface/src/main/aidl"))
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
        aidl = true
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
}