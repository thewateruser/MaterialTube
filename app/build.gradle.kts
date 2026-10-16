import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional release signing: provide keystore.properties (local) or MATERIALTUBE_* env vars (CI).
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    fun env(name: String, key: String) = System.getenv(name)?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    env("MATERIALTUBE_KEYSTORE", "storeFile")
    env("MATERIALTUBE_KEYSTORE_PASSWORD", "storePassword")
    env("MATERIALTUBE_KEY_ALIAS", "keyAlias")
    env("MATERIALTUBE_KEY_PASSWORD", "keyPassword")
}
// Default: the project's own fixed key (signing/materialtube.jks), so every build -- local or CI --
// has the same signature and updates install over each other. Override with your own key above.
if (signing.getProperty("storeFile").isNullOrBlank()) {
    signing["storeFile"] = rootProject.file("signing/materialtube.jks").path
    signing["storePassword"] = "materialtube"
    signing["keyAlias"] = "materialtube"
    signing["keyPassword"] = "materialtube"
}
val hasReleaseKey = signing.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let { file(it).isFile } == true

android {
    namespace = "dev.materialtube"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.materialtube"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.8.2"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    // Material 3 Expressive (MaterialExpressiveTheme, LoadingIndicator, ToggleButton, MaterialShapes...)
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.browser:browser:1.8.0")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Stream extraction (signature/n-param deciphering etc.), same library NewPipe uses.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:3313901108f0f52df68c28a04d88c2891f9c400b")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is stubbed in unit tests; use the real implementation there.
    testImplementation("org.json:json:20250517")
}
