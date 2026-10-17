import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val minSdkProp = (project.findProperty("retrobox.minSdk") as String?)?.toInt() ?: 21

// Release signing: keystore.properties in the repo root (git-ignored), or another file named by
// -Pretrobox.keystoreProperties=<path> / RETROBOX_KEYSTORE_PROPERTIES. A relative storeFile is relative to that file.
val keystoreFile: File = ((project.findProperty("retrobox.keystoreProperties") as String?) ?: System.getenv("RETROBOX_KEYSTORE_PROPERTIES"))
    ?.let { rootProject.file(it) } ?: rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
if (keystoreProps.isNotEmpty()) {
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { keystoreProps.getProperty(it).isNullOrBlank() }
    require(missing.isEmpty()) { "${keystoreFile.path} is missing: ${missing.joinToString()}" }
}

// Cores left out of the APK, e.g. -Pretrobox.excludeCores=snes9x2005 for a build you may give to others
// (Snes9x 2005 is non-commercial; see THIRD_PARTY_NOTICES.md). The app then reports that system's core as not installed.
val excludedCores = (project.findProperty("retrobox.excludeCores") as String?).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.retrobox.tv"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.retrobox.tv"
        minSdk = minSdkProp
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild {
            cmake {
                // 16 KB page-size compatible; static STL so the host has no extra .so dependency.
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = keystoreFile.absoluteFile.parentFile.resolve(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        // Libretro cores are dlopen()ed by path, so they must exist as real files in nativeLibraryDir.
        jniLibs {
            useLegacyPackaging = true
            excludedCores.forEach { excludes += "**/lib${it}_libretro_android.so" }
        }
    }
    lint { abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// License texts shipped in the APK (assets/licenses/), shown under Settings → About & licenses:
// the app's own GPL-2.0, THIRD_PARTY_NOTICES.md, licenses/*, and each core's license copied by cores/build-cores.sh.
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val copyLicenses by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE")) { rename { "retrobox-GPL-2.0.txt" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { rename { "THIRD_PARTY_NOTICES.txt" } }
    from(rootProject.file("licenses"))
    from(rootProject.file("cores/out/licenses"))
    into(licenseAssets.map { it.dir("licenses") })
}
android.sourceSets["main"].assets.srcDir(licenseAssets)
tasks.named("preBuild") { dependsOn(copyLicenses) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.16.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")   // JVM tests need a real org.json (the Android one is a stub there)

    // On-device tests (tools/run-device-tests.sh): real cores, real GPU, real SQLite, D-pad navigation.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")   // the empty activity createComposeRule() hosts screens in
}
