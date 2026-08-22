plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    androidResources {
        noCompress += "onnx"
        noCompress += "tflite"
        noCompress += "task"
        noCompress += "model"
        noCompress += "litertlm"
    }
    namespace = "com.example.hjp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.hjp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
        /**
         * Model-free variant for emulator lifecycle checks.
         *
         * It exists so a lifecycle run cannot accidentally prove anything about inference: the
         * assets tree is replaced with one that carries the card fixture and no model at all, so
         * there is no Gemma, no EmbeddingGemma and no semantic index inside the APK. The device and
         * release variants are untouched.
         */
        create("lifecycle") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
    }
    sourceSets {
        // The on-device models live outside src/main so that a build type can decline them. Build
        // types merge their assets *with* main, so leaving the models in main would have shipped
        // them in every variant no matter what the lifecycle variant declared. Debug and release
        // both pull the same directory, so their packaged contents are unchanged.
        getByName("debug") { assets.srcDirs("src/modelAssets/assets") }
        getByName("release") { assets.srcDirs("src/modelAssets/assets") }
    }
    // Opt-in, so the ordinary `connectedAndroidTest` path keeps testing the debug variant.
    testBuildType = if (project.hasProperty("hjpLifecycleTests")) "lifecycle" else "debug"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":agent-contract"))
    implementation(project(":tool-contract"))
    implementation(project(":tool-contact"))
    implementation(project(":search-core"))
    implementation(project(":tool-android-intents"))
    implementation(project(":tool-datetime"))
    implementation(project(":llm-litert"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.localagents.rag)
    implementation(libs.protobuf.javalite)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
