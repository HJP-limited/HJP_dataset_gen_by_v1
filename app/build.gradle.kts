import java.security.MessageDigest

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
        // The instrumentation evaluation reads its dataset from a *generated* directory only.
        //
        // `setSrcDirs` replaces the default `src/androidTest/assets` rather than adding to it. That
        // is the point: the hand-maintained copy under that directory had drifted to the v1 scenario
        // set while the JVM evaluation moved to v2, and nothing could see the divergence because the
        // two lived in different trees with no link between them. The generated directory is derived
        // from the JVM resources by `syncRyeongEvalAssets`, which fails the build on a SHA mismatch,
        // so a drift of that kind now cannot reach an APK.
        //
        // The v1 files stay on disk untouched; they are simply no longer an asset source.
        getByName("androidTest") {
            assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/ryeongEvalAssets").get().asFile))
        }

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

/**
 * Copies the Ryeong evaluation dataset into the androidTest assets, from one authority.
 *
 * The JVM evaluation reads `app/src/test/resources/ryeong/`. Before this task existed the
 * instrumentation evaluation read a hand-maintained copy under `src/androidTest/assets/ryeong/`,
 * and the two silently diverged: the JVM side moved to the 139-scenario v2 set while the device side
 * stayed on the 130-scenario v1 set. A copy that nothing verifies is a copy that will drift, so the
 * copy is now produced by the build and checked against a declared digest on every run.
 *
 * The digests are the ones the v2 freeze declares. A missing source, a changed source, or a source
 * whose digest does not match fails the build rather than packaging something unexpected.
 */
data class VerifiedAndroidTestAsset(val source: File, val sha256: String)

val ryeongEvalAssetSources = mapOf(
    "ryeong/scenarios_v2.json" to VerifiedAndroidTestAsset(
        layout.projectDirectory.file("src/test/resources/ryeong/scenarios_v2.json").asFile,
        "2d86ce0a1ca8be195554cb0292f48a0fb1d39b892f88a51295caff7943cdf685",
    ),
    "ryeong/cards_eval1000.json" to VerifiedAndroidTestAsset(
        layout.projectDirectory.file("src/test/resources/ryeong/cards_eval1000.json").asFile,
        "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24",
    ),
    // A-8 consumes the identical 400-scenario Gold authority used by the JVM replay.  This is a
    // verified packaging copy only: the evaluator remains the source file under tools/.
    "agent_eval/eval_set_v1_e32.json" to VerifiedAndroidTestAsset(
        rootProject.layout.projectDirectory.file(
            "tools/agent_eval_multiturn_v1/data/eval_set_v1_e32.json",
        ).asFile,
        "1eacb9f831be395fd70c1c520c4847cdadda02a4df4b142a9dce50bc1a0a92da",
    ),
)

val syncRyeongEvalAssets by tasks.registering {
    group = "build"
    description = "Copies the frozen Ryeong evaluation dataset into androidTest assets, digest-checked."

    val outputDir = layout.buildDirectory.dir("generated/ryeongEvalAssets")
    val expected = ryeongEvalAssetSources

    // Declared so Gradle re-runs this whenever a source or the expected digest changes, and so a
    // stale generated directory is never reused as if it were fresh.
    inputs.files(expected.values.map { it.source })
    inputs.property("expectedDigests", expected.mapValues { (_, asset) -> asset.sha256 })
    outputs.dir(outputDir)

    doLast {
        val target = outputDir.get().asFile
        // Rebuilt from scratch: an output left behind by an earlier configuration must not survive
        // into this one.
        target.deleteRecursively()
        target.mkdirs()
        expected.forEach { (relative, asset) ->
            val source = asset.source
            if (!source.isFile) {
                throw GradleException("ryeong eval asset source missing: ${source.path}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(source.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            if (digest != asset.sha256) {
                throw GradleException(
                    "ryeong eval asset digest mismatch for $relative\n" +
                    "  file     ${source.path}\n" +
                    "  expected ${asset.sha256}\n" +
                        "  actual   $digest",
                )
            }
            val destination = File(target, relative)
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
        logger.lifecycle("syncRyeongEvalAssets: ${expected.size} asset(s) verified and copied to ${target.path}")
    }
}

// Every androidTest asset-merge task consumes the generated directory, so the copy always runs
// first and no variant can package a stale or unverified dataset.
tasks.matching { it.name.startsWith("merge") && it.name.contains("AndroidTestAssets") }
    .configureEach { dependsOn(syncRyeongEvalAssets) }

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
    // The device-run evidence contract, compiled into both the host unit tests and the
    // instrumentation APK. One implementation, so a rule proved on the host is proved for the run
    // that happens on the device.
    testImplementation(project(":device-evidence"))
    androidTestImplementation(project(":device-evidence"))
    // Initialization-only physical-device probe imports Engine directly without widening the
    // production API surface of :llm-litert.
    androidTestImplementation(libs.litert.lm.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
