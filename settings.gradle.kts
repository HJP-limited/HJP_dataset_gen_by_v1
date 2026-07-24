pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HJP"
include(":agent-contract")
include(":tool-contract")
include(":agent-core")
include(":agent-routing")
include(":search-core")
include(":tool-contact")
include(":tool-external-actions")
include(":tool-datetime")
include(":llm-litert-common")
include(":desktop-agent-runner")

// JVM-only invocations must be configurable on a Mac with no Android SDK installed.
// Android modules remain included for normal/root/Android tasks and keep their original graph.
val requestedProjectTasks = gradle.startParameter.taskNames.filter { it.startsWith(":") }
val jvmOnlyProjects = setOf(
    "agent-contract", "tool-contract", "agent-core", "agent-routing", "search-core",
    "tool-contact", "tool-external-actions", "tool-datetime", "llm-litert-common",
    "desktop-agent-runner",
)
val jvmOnlyInvocation = requestedProjectTasks.isNotEmpty() && requestedProjectTasks.all { task ->
    task.removePrefix(":").substringBefore(":") in jvmOnlyProjects
}
if (!jvmOnlyInvocation) {
    include(":app")
    include(":tool-android-intents")
    include(":llm-litert")
}
