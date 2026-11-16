plugins {
    id("com.android.application")
}

// Release builds (CI) pass -PcopierVersion=1.2.3; local builds keep the numbers below.
val copierVersion: String? = providers.gradleProperty("copierVersion").orNull
// CI provides the release key through these variables. Without them, release builds are signed with
// this machine's debug key, so a locally built APK keeps installing over the last local one.
val releaseKeystore: String? = System.getenv("COPIER_KEYSTORE")

android {
    namespace = "dev.copier"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.copier"
        minSdk = 29
        targetSdk = 36
        val version = copierVersion ?: "0.5.0"
        versionName = version
        // 1.2.3 → 10203, so every build installs over the previous one.
        versionCode = (version.substringBefore('-').split('.').map { it.toInt() } + listOf(0, 0)).let { it[0] * 10_000 + it[1] * 100 + it[2] }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("COPIER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COPIER_KEY_ALIAS") ?: "copier"
                keyPassword = System.getenv("COPIER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // Shared with the Swift suite; both must reproduce every value in it.
            it.systemProperty("copier.vectors", rootProject.file("../testvectors/v1.json").absolutePath)
            // Set by mac/Tests/CopierTests/InteropTests.swift, which runs MacInteropTest against a live server.
            for (key in listOf("port", "mackey", "token", "offer", "offersha")) {
                providers.gradleProperty("copier.interop.$key").orNull?.let { value ->
                    it.systemProperty("copier.interop.$key", value)
                }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar ships org.json as stubs; JVM unit tests need the real implementation.
    testImplementation("org.json:json:20260814")
}
