package com.hjp.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteRtLmProcessRunnerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing executable and model errors are explicit`() {
        val runner = LiteRtLmProcessRunner(LiteRtProcessConfig(
            File(temporaryFolder.root, "missing-bin"), File(temporaryFolder.root, "missing-model"), "cpu", 1_000,
        ))
        val first = runCatching { runner.validateFiles() }.exceptionOrNull()
        assertTrue(first?.message.orEmpty().startsWith("LiteRT-LM 실행 파일을 찾을 수 없습니다:"))

        val executable = temporaryFolder.newFile("litert-lm").apply { setExecutable(true) }
        val second = runCatching {
            LiteRtLmProcessRunner(LiteRtProcessConfig(executable, File(temporaryFolder.root, "missing-model"), "cpu", 1_000))
                .validateFiles()
        }.exceptionOrNull()
        assertTrue(second?.message.orEmpty().startsWith("모델 파일을 찾을 수 없습니다:"))
    }

    @Test
    fun `ProcessBuilder keeps Korean and spaces model path as one argument`() {
        val directory = temporaryFolder.newFolder("한글 경로")
        val executable = File(directory, "litert lm stub").apply {
            writeText("#!/bin/sh\nprintf 'Output: stub-ok\\n'\n")
            check(setExecutable(true))
        }
        val model = File(directory, "모델 파일.litertlm").apply { writeBytes(byteArrayOf(1)) }
        val output = LiteRtLmProcessRunner(LiteRtProcessConfig(executable, model, "cpu", 5_000))
            .generate("안녕 세계")

        assertEquals(model.absolutePath, output.command[2])
        assertEquals("stub-ok", output.generatedText)
    }

    @Test
    fun `runner closes child stdin and captures stdout and stderr`() {
        val executable = executable(
            "stdin-eof-stub",
            """
                #!/bin/sh
                input=${'$'}(cat)
                printf 'Output: stdin-eof-received\n'
                printf 'stderr-captured\n' >&2
            """.trimIndent(),
        )
        val output = runner(executable, timeoutMillis = 3_000).generate("stdin은 인자로 전달")

        assertEquals("stdin-eof-received", output.generatedText)
        assertTrue(output.stdout.contains("stdin-eof-received"))
        assertTrue(output.stderr.contains("stderr-captured"))
    }

    @Test
    fun `timeout terminates child and reader threads`() {
        val executable = executable(
            "timeout-stub",
            """
                #!/bin/sh
                trap '' TERM
                cat
                while :; do sleep 1; done
            """.trimIndent(),
        )
        val error = runCatching {
            runner(executable, timeoutMillis = 150).generate("timeout")
        }.exceptionOrNull()

        assertTrue(error is LiteRtProcessException)
        assertTrue(error?.message.orEmpty().contains("초과"))
        repeat(20) {
            if (readerThreads().isEmpty()) return@repeat
            Thread.sleep(25)
        }
        assertFalse("reader thread가 남아 있습니다: ${readerThreads()}", readerThreads().isNotEmpty())
    }

    private fun executable(name: String, script: String): File =
        File(temporaryFolder.root, name).apply {
            writeText(script + "\n")
            check(setExecutable(true))
        }

    private fun runner(executable: File, timeoutMillis: Long): LiteRtLmProcessRunner {
        val model = File(temporaryFolder.root, "stub-model.litertlm").apply {
            if (!exists()) writeBytes(byteArrayOf(1))
        }
        return LiteRtLmProcessRunner(LiteRtProcessConfig(executable, model, "cpu", timeoutMillis))
    }

    private fun readerThreads(): List<String> = Thread.getAllStackTraces().keys
        .map(Thread::getName)
        .filter { it.startsWith("litert-process-reader-") }
}
