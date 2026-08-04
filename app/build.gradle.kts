import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Signing config lives in keystore.properties, which is gitignored. The build stays
// usable without it — release simply comes out unsigned rather than failing, so a
// fresh clone can still compile.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.manuel.ours"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.manuel.ours"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.play.services.nearby)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    // Robolectric needs a real Context to build an in-memory Room database.
    testImplementation("androidx.test:core-ktx:1.6.1")

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Lets the throwaway corpus harness receive -Dcorpus=... from the command line.
tasks.withType<Test>().configureEach {
    systemProperty("corpus", System.getProperty("corpus") ?: "")
    testLogging { showStandardStreams = true }
}

/**
 * The Apps Script the user must paste into their spreadsheet is shipped inside the app
 * so the setup screen can offer it as copyable text.
 *
 * Copied from the single source in `sheet-sync/Code.gs` at build time rather than kept
 * as a second copy under `res/raw`. Two hand-maintained copies of a protocol
 * implementation drift, and the failure would be silent: the phone would speak a
 * version of the wire format the pasted script does not.
 */
val syncSheetScript by tasks.registering(Copy::class) {
    from(rootProject.file("sheet-sync/Code.gs"))
    into(layout.projectDirectory.dir("src/main/res/raw"))
    rename { "sheet_sync_script.txt" }
}

tasks.named("preBuild") { dependsOn(syncSheetScript) }

/**
 * Publishes a build the household's phones can find.
 *
 * Copies the signed APK next to a manifest naming its version, both inside the
 * repository, so `git push` is the whole release process — no server, no account, no
 * Play listing. The phones read the manifest and compare against their own
 * versionCode, so bumping that in defaultConfig is what makes an update visible.
 */
tasks.register("publishRelease") {
    group = "distribution"
    description = "Copies the signed release APK and version.json into release/"
    dependsOn("assembleRelease")

    doLast {
        val apk = layout.buildDirectory
            .file("outputs/apk/release/app-release.apk").get().asFile
        require(apk.exists()) { "No signed release APK — is keystore.properties present?" }

        val out = rootProject.file("release")
        out.mkdirs()
        val published = File(out, "Ours.apk")
        apk.copyTo(published, overwrite = true)

        // raw.githubusercontent, not github.com/raw: the latter answers 404 for a
        // binary blob served straight off a branch. Kept as a Kotlin comment — writing
        // it inside the JSON produced a manifest no parser would accept, which broke
        // the very check it was explaining.
        val code = android.defaultConfig.versionCode
        val name = android.defaultConfig.versionName
        File(out, "version.json").writeText(
            """
            {
              "versionCode": $code,
              "versionName": "$name",
              "notes": "",
              "apkUrl": "https://raw.githubusercontent.com/manuelfc1991/expense-tracker/master/release/Ours.apk",
              "sizeBytes": ${published.length()}
            }
            """.trimIndent() + "\n"
        )
        println("Published ${published.name} (${published.length() / 1024 / 1024} MB), version $name ($code)")
    }
}
