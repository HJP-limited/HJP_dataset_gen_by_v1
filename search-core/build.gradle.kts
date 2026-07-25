plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("searchEvaluation") {
    group = "verification"
    description = "Compares the 0711 legacy scorer and the canonical Ryeong hybrid retrieval on 5,000 synthetic cards."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.hjp.searchlookup.eval.SearchEvaluationMain")
    val reportDir = providers.gradleProperty("searchEvalOutput")
        .orElse(layout.buildDirectory.dir("reports/search-evaluation").map { it.asFile.absolutePath })
    argumentProviders.add(CommandLineArgumentProvider { listOf(reportDir.get()) })
}
