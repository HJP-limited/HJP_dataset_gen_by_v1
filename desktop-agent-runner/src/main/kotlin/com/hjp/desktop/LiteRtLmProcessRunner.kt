package com.hjp.desktop

import java.io.File
import java.io.BufferedReader
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class LiteRtProcessConfig(
    val executable: File,
    val model: File,
    val backend: String,
    val timeoutMillis: Long,
)

data class LiteRtProcessOutput(
    val generatedText: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val command: List<String>,
)

fun interface ModelTextRunner {
    fun generate(prompt: String): LiteRtProcessOutput
}

class LiteRtLmProcessRunner(
    private val config: LiteRtProcessConfig,
) : ModelTextRunner {
    override fun generate(prompt: String): LiteRtProcessOutput {
        validateFiles()
        val command = listOf(
            config.executable.absolutePath,
            "run",
            config.model.absolutePath,
            "--prompt",
            prompt,
            "--backend",
            config.backend,
            "--cache",
            "memory",
            "--temperature",
            "0.1",
            "--top-k",
            "20",
            "--max-num-tokens",
            "4096",
        )
        val process = ProcessBuilder(command).start()
        // The Python CLI calls sys.stdin.read() even when --prompt is supplied. Sending EOF is
        // therefore required; otherwise the child waits forever before model inference starts.
        process.outputStream.close()
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "litert-process-reader-${READER_SEQUENCE.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        val stdoutFuture = readAsync(process.inputStream.bufferedReader(), executor)
        val stderrFuture = readAsync(process.errorStream.bufferedReader(), executor)
        try {
            if (!process.waitFor(config.timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminate(process)
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                throw LiteRtProcessException(
                    "LiteRT-LM 실행 시간이 ${config.timeoutMillis / 1_000.0}초를 초과했습니다.",
                )
            }
            val stdout = stdoutFuture.get(READER_JOIN_SECONDS, TimeUnit.SECONDS)
            val stderr = stderrFuture.get(READER_JOIN_SECONDS, TimeUnit.SECONDS)
            val exit = process.exitValue()
            if (exit != 0) {
                val details = stderr.ifBlank { stdout }.takeLast(4_000).trim()
                throw LiteRtProcessException(
                    "LiteRT-LM subprocess 종료 코드가 0이 아닙니다: $exit" +
                        if (details.isBlank()) "" else "\n$details",
                )
            }
            return LiteRtProcessOutput(extractGeneratedText(stdout), stdout, stderr, exit, command)
        } finally {
            if (process.isAlive) terminate(process)
            process.inputStream.close()
            process.errorStream.close()
            stdoutFuture.cancel(true)
            stderrFuture.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(READER_JOIN_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun validateFiles() {
        if (!config.executable.isFile || !config.executable.canExecute()) {
            throw LiteRtProcessException("LiteRT-LM 실행 파일을 찾을 수 없습니다: ${config.executable.absolutePath}")
        }
        if (!config.model.isFile || !config.model.canRead()) {
            throw LiteRtProcessException("모델 파일을 찾을 수 없습니다: ${config.model.absolutePath}")
        }
    }

    private fun readAsync(
        reader: BufferedReader,
        executor: java.util.concurrent.ExecutorService,
    ): Future<String> =
        executor.submit<String> {
            reader.use {
                val output = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.length < MAX_CAPTURE_CHARS) {
                        output.append(buffer, 0, minOf(count, MAX_CAPTURE_CHARS - output.length))
                    }
                }
                output.toString()
            }
        }

    private fun terminate(process: Process) {
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    internal fun extractGeneratedText(stdout: String): String {
        val clean = stdout.replace(ANSI_ESCAPE, "").replace("\r", "").trim()
        val marked = listOf("[MODEL_OUTPUT]", "Model output:", "Output:", "Assistant:")
            .mapNotNull { marker -> clean.lastIndexOf(marker).takeIf { it >= 0 }?.let { clean.substring(it + marker.length).trim() } }
            .firstOrNull { it.isNotBlank() }
        return marked ?: clean
    }

    private companion object {
        const val MAX_CAPTURE_CHARS = 1_048_576
        const val READER_JOIN_SECONDS = 5L
        val READER_SEQUENCE = AtomicInteger()
        val ANSI_ESCAPE = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
    }
}

class LiteRtProcessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
