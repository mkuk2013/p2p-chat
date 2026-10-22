plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Launcher/splash PNGs are generated assets: the master logo travels in this
// repo as base64 text (branding/logo-512.b64.txt), so a fresh clone may not
// contain the PNG files yet. Regenerate them before resources are processed.
// Skipped automatically when the icons are already present (normal local dev).
val regenIcons = tasks.register<Exec>("regenIcons") {
    description = "Regenerates launcher/splash PNGs from branding/logo-512.b64.txt"
    onlyIf { !file("src/main/res/mipmap-xxxhdpi/ic_launcher.png").exists() }
    workingDir = rootDir
    commandLine(
        "bash", "-c",
        "python3 -m pip install --quiet pillow 2>/dev/null || pip3 install --quiet pillow; " +
            "python3 branding/regen-icons.py"
    )
}
tasks.named("preBuild") { dependsOn(regenIcons) }

android {
    namespace = "com.p2pchat.messenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.p2pchat.messenger"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-crypto"))
    implementation(project(":core-database"))
    implementation(project(":transport-ble"))
    implementation(project(":mesh-engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle.bcprov)

    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
