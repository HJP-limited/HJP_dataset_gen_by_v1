plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":tool-contract"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
