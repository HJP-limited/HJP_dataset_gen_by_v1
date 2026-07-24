plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":agent-contract"))
    api(project(":tool-external-actions"))
    implementation(project(":agent-routing"))
    implementation(project(":tool-contract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    compileOnly(libs.litert.lm.jvm)
    testImplementation(libs.junit)
}
