plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val natives: Configuration by configurations.creating

android {
    namespace = "com.sortopia.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.sortopia"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs(rootProject.file("assets"))
            jniLibs.srcDirs(layout.buildDirectory.dir("gdx-natives"))
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        lintConfig = file("lint.xml")
        // Security-relevant checks are errors.
        error +=
            setOf(
                "HardcodedDebugMode",
                "ExportedReceiver",
                "ExportedService",
                "ExportedContentProvider",
                "AllowBackup",
                "DataExtractionRules",
                "SetWorldReadable",
                "SetWorldWritable",
                "WorldReadableFiles",
                "WorldWriteableFiles",
                "UnsafeProtectedBroadcastReceiver",
                "TrustAllX509TrustManager",
                "SecureRandom",
                "PackagedPrivateKey",
                "UnspecifiedRegisterReceiverFlag",
            )
    }

    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx.backend.android)

    val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    abis.forEach { abi ->
        natives(variantOf(libs.gdx.platform) { classifier("natives-$abi") })
        natives(variantOf(libs.gdx.freetype.platform) { classifier("natives-$abi") })
    }

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// Extract LibGDX's native .so files into a jniLibs folder per ABI.
val copyAndroidNatives by tasks.registering(Copy::class) {
    val outDir = layout.buildDirectory.dir("gdx-natives")
    into(outDir)
    natives.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
        val abi = artifact.classifier?.removePrefix("natives-") ?: return@forEach
        from(zipTree(artifact.file)) {
            include("*.so")
            into(abi)
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn(copyAndroidNatives)
}

// Lock what ships in the APK so `vuln-scan` can check exactly the release runtime dependencies.
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
