plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.hjp.desktop.MainKt")
}

val modelEval by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[modelEval.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get())
configurations[modelEval.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation(project(":agent-contract"))
    implementation(project(":agent-core"))
    implementation(project(":agent-routing"))
    implementation(project(":llm-litert-common"))
    implementation(project(":tool-contract"))
    implementation(project(":tool-contact"))
    implementation(project(":tool-datetime"))
    implementation(project(":tool-external-actions"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.litert.lm.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit {
        excludeCategories("com.hjp.desktop.RealModelSmokeTest")
    }
}

tasks.register<Test>("modelSmokeTest") {
    description = "Runs opt-in tests that invoke the real LiteRT-LM model."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(tasks.testClasses)
    useJUnit {
        includeCategories("com.hjp.desktop.RealModelSmokeTest")
    }
    enabled = providers.environmentVariable("RUN_LITERT_LM_SMOKE_TEST").orNull == "1"
}

tasks.register<JavaExec>("modelAgentEval") {
    description = "Runs the selected real LiteRT model agent/tool/compose evaluation."
    group = "verification"
    dependsOn(tasks.named(modelEval.classesTaskName))
    classpath = modelEval.runtimeClasspath
    mainClass.set("com.hjp.desktop.ModelAgentEvalMainKt")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("modelPerformance") {
    description = "Measures selected model initialization, latency, current-process RSS and draft generation."
    group = "verification"
    dependsOn(tasks.named(modelEval.classesTaskName))
    classpath = modelEval.runtimeClasspath
    mainClass.set("com.hjp.desktop.ModelPerformanceMain")
    workingDir = rootProject.projectDir
}
