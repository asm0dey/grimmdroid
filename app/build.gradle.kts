plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        optIn.add("org.readium.r2.shared.ExperimentalReadiumApi")
    }
}

android {
    namespace = "io.github.asm0dey.grimmdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.asm0dey.grimmdroid"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.2.0"
    }

    signingConfigs {
        // Release signing comes from the environment (fastlane release / CI); absent locally.
        System.getenv("GRIMMDROID_KEYSTORE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("GRIMMDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GRIMMDROID_KEY_ALIAS")
                keyPassword = System.getenv("GRIMMDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // -PdebugSigned: local release build signed with the debug key, installable over debug.
            signingConfig =
                if (providers.gradleProperty("debugSigned").isPresent) {
                    signingConfigs.getByName("debug")
                } else {
                    signingConfigs.findByName("release")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // Readium 3.4.0 refuses to build without core library desugaring.
        isCoreLibraryDesugaringEnabled = true
    }

    // JVM-only integration tests compile with the unit tests but run only in integrationTest (needs a live server).
    sourceSets["test"].kotlin.directories += "src/integrationTest/kotlin"

    buildFeatures {
        compose = true
    }

    lint {
        // Readium ships Timber's lint checks; the app logs with android.util.Log.
        disable += "LogNotTimber"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                // Robolectric's Android 17 (SDK 37) sandbox reaches into jdk.internal.access.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                it.exclude("io/github/asm0dey/grimmdroid/integration/**")
                it.systemProperty("grimmdroid.fixtures", rootProject.file("integration/fixtures").path)
            }
        }
    }
}

// MigrationTestHelper reads the exported schemas as assets, and Robolectric serves only the main variant's merged
// assets, so the debug build (never release) carries them.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addStaticSourceDirectory("$projectDir/schemas")
    }
}

// Room writes each schema version here (committed), for migration tests once the schema changes.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.jsoup)
    implementation(libs.gl.utils)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Not part of check: run against integration/docker-compose.yml (see .github/workflows/integration.yml).
tasks.register<Test>("integrationTest") {
    description = "Runs LiveServerTest against a running Grimmory (GRIMMORY_URL, default http://localhost:6060)."
    group = "verification"
    val unit = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    include("io/github/asm0dey/grimmdroid/integration/**")
    useJUnitPlatform()
    systemProperty("grimmdroid.fixtures", rootProject.file("integration/fixtures").path)
    environment("GRIMMORY_URL", System.getenv("GRIMMORY_URL") ?: "http://localhost:6060")
    outputs.upToDateWhen { false }
}
