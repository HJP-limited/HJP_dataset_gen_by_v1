import com.hjp.buildlogic.VerifyExactAssetsTask
import com.hjp.buildlogic.VerifyExactModelTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val gemma4ModelFileName = "gemma-4-E2B-it.litertlm"
val gemma4ModelSize = 2_588_147_712L
val gemma4ModelSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
val gemma4ModelPath = providers.environmentVariable("HJP_GEMMA4_MODEL")
    .orElse(providers.gradleProperty("hjpGemma4ModelPath"))
val deviceModelAssetsDir = layout.buildDirectory.dir("generated/deviceStandaloneModelAssets")

val verifyDeviceStandaloneModel by tasks.registering(VerifyExactModelTask::class) {
    group = "verification"
    description = "Validates the exact Gemma 4 E2B IT model before device packaging."
    modelPath.set(gemma4ModelPath.orElse(""))
    expectedName.set(gemma4ModelFileName)
    expectedSize.set(gemma4ModelSize)
    expectedSha256.set(gemma4ModelSha256)
}

val stageDeviceStandaloneModel by tasks.registering(Sync::class) {
    dependsOn(verifyDeviceStandaloneModel)
    from(gemma4ModelPath)
    into(deviceModelAssetsDir)
    rename(".*", gemma4ModelFileName)
}

android {
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
        buildConfigField("String", "HJP_MODEL_ID", "\"none\"")
        buildConfigField("String", "HJP_MODEL_FILE_NAME", "\"\"")
        buildConfigField("long", "HJP_MODEL_SIZE_BYTES", "0L")
        buildConfigField("String", "HJP_MODEL_SHA256", "\"\"")
        buildConfigField("boolean", "HJP_LITERT_ENABLED", "false")
        buildConfigField("boolean", "HJP_EMULATOR_MODE", "false")
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("device") {
            dimension = "runtime"
            buildConfigField("String", "HJP_MODEL_ID", "\"gemma4-e2b-it\"")
            buildConfigField("String", "HJP_MODEL_FILE_NAME", "\"$gemma4ModelFileName\"")
            buildConfigField("long", "HJP_MODEL_SIZE_BYTES", "${gemma4ModelSize}L")
            buildConfigField("String", "HJP_MODEL_SHA256", "\"$gemma4ModelSha256\"")
            buildConfigField("boolean", "HJP_LITERT_ENABLED", "true")
            buildConfigField("boolean", "HJP_EMULATOR_MODE", "false")
        }
        create("emulator") {
            dimension = "runtime"
            buildConfigField("String", "HJP_MODEL_ID", "\"none\"")
            buildConfigField("String", "HJP_MODEL_FILE_NAME", "\"\"")
            buildConfigField("long", "HJP_MODEL_SIZE_BYTES", "0L")
            buildConfigField("String", "HJP_MODEL_SHA256", "\"\"")
            buildConfigField("boolean", "HJP_LITERT_ENABLED", "false")
            buildConfigField("boolean", "HJP_EMULATOR_MODE", "true")
        }
    }

    buildTypes {
        create("standalone") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            versionNameSuffix = "-standalone"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        release {
            optimization {
                enable = false
            }
        }
    }

    sourceSets.maybeCreate("deviceStandalone").assets.directories.add(
        deviceModelAssetsDir.get().asFile.absolutePath,
    )
    androidResources {
        noCompress += "litertlm"
        ignoreAssetsPattern += ":*.xnnpack_cache_*"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val verifyDeviceStandaloneMergedAssets by tasks.registering(VerifyExactAssetsTask::class) {
    group = "verification"
    description = "Requires exactly one Gemma 4 model and the card seed in device assets."
    val mergedAssets = layout.buildDirectory.dir(
        "intermediates/assets/deviceStandalone/mergeDeviceStandaloneAssets",
    )
    assetsDirectory.set(mergedAssets)
    expectedFiles.set(listOf(gemma4ModelFileName, "cards/business_cards.json"))
    forbiddenFragments.set(listOf(".xnnpack_cache_", "gemma3", "functiongemma"))
}

val verifyEmulatorMergedAssets by tasks.registering(VerifyExactAssetsTask::class) {
    group = "verification"
    description = "Requires a model-free emulator asset set."
    val mergedAssets = layout.buildDirectory.dir(
        "intermediates/assets/emulatorDebug/mergeEmulatorDebugAssets",
    )
    assetsDirectory.set(mergedAssets)
    expectedFiles.set(listOf("cards/business_cards.json"))
    forbiddenFragments.set(listOf(".litertlm", ".xnnpack_cache_"))
}

tasks.matching { it.name == "preDeviceStandaloneBuild" }.configureEach {
    dependsOn(verifyDeviceStandaloneModel)
}
tasks.matching { it.name == "mergeDeviceStandaloneAssets" }.configureEach {
    dependsOn(stageDeviceStandaloneModel)
    finalizedBy(verifyDeviceStandaloneMergedAssets)
}
tasks.matching { it.name == "mergeEmulatorDebugAssets" }.configureEach {
    finalizedBy(verifyEmulatorMergedAssets)
}

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":agent-contract"))
    implementation(project(":agent-routing"))
    implementation(project(":tool-contract"))
    implementation(project(":tool-contact"))
    implementation(project(":tool-external-actions"))
    implementation(project(":tool-android-intents"))
    implementation(project(":tool-datetime"))
    add("deviceImplementation", project(":llm-litert"))
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
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
