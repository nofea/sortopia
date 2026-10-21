import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val natives: Configuration by configurations.creating

// Release signing (README, "Release build"). Each value comes from an environment variable (CI)
// or else from the git-ignored keystore.properties at the repo root (local). Without them the
// release build is produced unsigned, so tasks like the checksum refresh still run anywhere.
val keystoreProps =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }

fun signingValue(
    env: String,
    prop: String,
): String? = providers.environmentVariable(env).orNull?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("SORTOPIA_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("SORTOPIA_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("SORTOPIA_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("SORTOPIA_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
