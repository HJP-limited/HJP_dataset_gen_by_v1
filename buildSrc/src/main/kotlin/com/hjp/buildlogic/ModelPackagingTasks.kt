package com.hjp.buildlogic

import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

abstract class VerifyExactModelTask : DefaultTask() {
    @get:Input
    abstract val modelPath: Property<String>

    @get:Input
    abstract val expectedName: Property<String>

    @get:Input
    abstract val expectedSize: Property<Long>

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun verify() {
        val configuredPath = modelPath.orNull?.takeIf(String::isNotBlank)
            ?: error(
                "Gemma 4 model path is required. Set HJP_GEMMA4_MODEL or " +
                    "-PhjpGemma4ModelPath=/absolute/path/to/${expectedName.get()}",
            )
        val modelFile = File(configuredPath)
        check(modelFile.isFile) { "Gemma 4 model not found: ${modelFile.absolutePath}" }
        check(modelFile.name == expectedName.get()) {
            "Gemma 4 model filename mismatch: expected ${expectedName.get()}, found ${modelFile.name}"
        }
        check(modelFile.length() == expectedSize.get()) {
            "Gemma 4 model size mismatch: expected ${expectedSize.get()} bytes, " +
                "found ${modelFile.length()} bytes"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        modelFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expectedSha256.get()) {
            "Gemma 4 model SHA-256 mismatch: expected ${expectedSha256.get()}, found $actual"
        }
    }
}

abstract class VerifyExactAssetsTask : DefaultTask() {
    @get:InputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @get:Input
    abstract val expectedFiles: ListProperty<String>

    @get:Input
    abstract val forbiddenFragments: ListProperty<String>

    @TaskAction
    fun verify() {
        val root = assetsDirectory.get().asFile
        check(root.isDirectory) { "Merged assets not found: ${root.absolutePath}" }
        val actual = root.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        val expected = expectedFiles.get().toSet()
        check(actual == expected) { "Unexpected merged assets. expected=$expected actual=$actual" }
        check(actual.none { path -> forbiddenFragments.get().any(path::contains) }) {
            "Forbidden merged asset found: $actual"
        }
    }
}
