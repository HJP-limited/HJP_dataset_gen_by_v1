plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":agent-contract"))
    implementation(project(":tool-contract"))
    implementation(project(":tool-external-actions"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":tool-contact"))
    testImplementation(project(":tool-external-actions"))
    testImplementation(project(":tool-datetime"))
    testImplementation(libs.junit)
}
