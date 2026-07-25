package com.hjp.desktop

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    runBlocking {
        val config = try {
            DesktopConfig.from(args)
        } catch (error: Throwable) {
            System.err.println(error.message)
            return@runBlocking
        }
        if (config.debug) System.setProperty("hjp.search.debug", "true")

        if (config.mode == RunnerMode.MODEL_ONLY) {
            runModelOnly(config)
            return@runBlocking
        }

        val runner = try {
            DesktopAgentRunner(config).also {
                it.validateData()
                if (config.mode in setOf(RunnerMode.FULL, RunnerMode.MODEL_AGENT)) {
                    it.reportModelAvailability()
                }
            }
        } catch (error: Throwable) {
            System.err.println(error.message ?: "Desktop runner를 시작하지 못했습니다.")
            return@runBlocking
        }

        runner.use {
            val prompt = config.prompt
            if (prompt != null) {
                it.runPrompt(prompt)
            } else {
                println("HJP Desktop Agent (${config.mode.name.lowercase().replace('_', '-')}) — 종료: exit 또는 quit")
                while (true) {
                    print("사용자 입력 > ")
                    System.out.flush()
                    val line = readlnOrNull() ?: break
                    if (line.trim().lowercase() in setOf("exit", "quit")) break
                    if (line.isBlank()) continue
                    it.runPrompt(line, echoInput = false)
                }
            }
        }
    }
}

private fun runModelOnly(config: DesktopConfig) {
    println("주의: model-only 모드는 ToolRegistry와 명함 데이터베이스를 사용하지 않습니다.")
    if (config.debug) {
        println(
            "[MODEL_CONFIG] model_id=${config.modelId} model_path=${config.modelFile.absolutePath} " +
                "model_size_bytes=${config.modelFile.takeIf { it.isFile }?.length() ?: -1}",
        )
    }
    val runner = LiteRtLmProcessRunner(LiteRtProcessConfig(
        config.liteRtBin, config.modelFile, config.backend, config.timeoutMillis,
    ))
    fun invoke(prompt: String) {
        println("사용자 입력 > $prompt")
        try {
            val output = runner.generate(prompt)
            if (config.debug) {
                println("[MODEL_REQUEST] $prompt")
                println("[MODEL_RAW_OUTPUT] ${output.stdout}")
                if (output.stderr.isNotBlank()) println("[MODEL_STDERR] ${output.stderr.take(12_000)}")
            }
            println("Raw model output > ${output.generatedText}")
        } catch (error: Throwable) {
            System.err.println(error.message ?: "LiteRT-LM 모델 실행에 실패했습니다.")
        }
    }

    config.prompt?.let {
        invoke(it)
        return
    }
    while (true) {
        print("사용자 입력 > ")
        System.out.flush()
        val line = readlnOrNull() ?: break
        if (line.trim().lowercase() in setOf("exit", "quit")) break
        if (line.isNotBlank()) invoke(line)
    }
}
