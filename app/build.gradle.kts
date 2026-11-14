import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Release signing.
//
// CI: the workflow decodes the SIGNING_KEYSTORE_BASE64 secret to a file and
//     exports SIGNING_KEYSTORE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS and
//     SIGNING_KEY_PASSWORD.
// Local: put the same four values in keystore.properties at the repo root
//     (storeFile=..., storePassword=..., keyAlias=..., keyPassword=...).
// Without either, release builds are signed with the debug key so they still
// install, but updates between differently signed builds are not possible.
// ---------------------------------------------------------------------------
data class ReleaseKey(val file: File, val storePassword: String, val alias: String, val keyPassword: String)

val releaseKey: ReleaseKey? = run {
    val env = System.getenv()
    val envFile = env["SIGNING_KEYSTORE"]
    if (!envFile.isNullOrBlank() && file(envFile).exists()) {
        return@run ReleaseKey(
            file(envFile),
            env["SIGNING_STORE_PASSWORD"].orEmpty(),
            env["SIGNING_KEY_ALIAS"].orEmpty(),
            env["SIGNING_KEY_PASSWORD"] ?: env["SIGNING_STORE_PASSWORD"].orEmpty(),
        )
    }
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use { load(it) } }
        val store = p.getProperty("storeFile")?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            return@run ReleaseKey(
                store,
                p.getProperty("storePassword").orEmpty(),
                p.getProperty("keyAlias").orEmpty(),
                p.getProperty("keyPassword") ?: p.getProperty("storePassword").orEmpty(),
            )
        }
    }
    null
}

android {
    namespace = "com.claude.drawterm"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.claude.drawterm"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "dev"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DDT_ROOT=" + rootProject.file("drawterm").invariantSeparatorsPath,
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.0"
        }
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey.file
                storePassword = releaseKey.storePassword
                keyAlias = releaseKey.alias
                keyPassword = releaseKey.keyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseKey != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
