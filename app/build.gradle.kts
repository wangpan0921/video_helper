plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.wangpan.videohelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wangpan.videohelper"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Credentials come from gradle properties (e.g. ~/.gradle/gradle.properties or -P flags)
            // so secrets stay out of version control; falls back to the repo's release.keystore
            // defaults for local/CI debug-release builds. The keystore itself is gitignored.
            val storePathProp = (project.findProperty("VH_STORE_FILE") as String?) ?: "release.keystore"
            val ksFile = rootProject.file("app/$storePathProp").takeIf { it.exists() }
                ?: rootProject.file(storePathProp)
            storeFile = ksFile
            storePassword = (project.findProperty("VH_STORE_PASSWORD") as String?) ?: "videohelper"
            keyAlias = (project.findProperty("VH_KEY_ALIAS") as String?) ?: "videohelper"
            keyPassword = (project.findProperty("VH_KEY_PASSWORD") as String?) ?: "videohelper"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
