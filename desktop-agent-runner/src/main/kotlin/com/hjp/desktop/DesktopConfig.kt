package com.hjp.desktop

import java.io.File

enum class RunnerMode { FULL, ROUTER_ONLY, MODEL_ONLY, MODEL_AGENT }

data class DesktopConfig(
    val mode: RunnerMode,
    val prompt: String?,
    val debug: Boolean,
    val liteRtBin: File,
    val modelId: String,
    val modelFile: File,
    val backend: String,
    val cardDataFile: File,
    val timeoutMillis: Long,
    val projectRoot: File,
    val cacheDirectory: File = File(projectRoot, "desktop-agent-runner/build/cache/litertlm"),
) {
    companion object {
        fun from(args: Array<String>, env: Map<String, String> = System.getenv()): DesktopConfig {
            val options = CliOptions.parse(args)
            val root = locateProjectRoot(File(System.getProperty("user.dir")))
            val home = File(System.getProperty("user.home"))
            val mode = when (options.value("mode")?.lowercase() ?: "full") {
                "full" -> RunnerMode.FULL
                "router-only" -> RunnerMode.ROUTER_ONLY
                "model-only" -> RunnerMode.MODEL_ONLY
                "model-agent" -> RunnerMode.MODEL_AGENT
                else -> error("지원하지 않는 mode입니다: ${options.value("mode")}")
            }
            val backend = (options.value("backend") ?: env["LITERT_LM_BACKEND"] ?: "cpu").lowercase()
            require(backend in setOf("cpu", "gpu", "npu")) { "backend는 cpu, gpu, npu 중 하나여야 합니다: $backend" }
            val timeoutSeconds = options.value("timeout-seconds")?.toLongOrNull()
                ?: env["LITERT_LM_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 900L
            require(timeoutSeconds in 1..3_600) { "timeout-seconds는 1~3600이어야 합니다." }
            val explicitModelId = options.value("model-id") ?: env["LITERT_LM_MODEL_ID"]
            if (explicitModelId != null) {
                require(explicitModelId in MODEL_FILES) {
                    "지원하지 않는 model-id입니다: $explicitModelId. 지원 값: ${MODEL_FILES.keys.joinToString()}"
                }
            }
            val explicitModelPath = options.value("model") ?: env["LITERT_LM_MODEL"]
            val modelId = explicitModelId
                ?: explicitModelPath?.let(::inferModelId)
                ?: DEFAULT_MODEL_ID
            val modelFile = File(
                explicitModelPath ?: File(root, "models/${MODEL_FILES.getValue(modelId)}").path,
            )
            return DesktopConfig(
                mode = mode,
                prompt = options.value("prompt"),
                debug = options.flag("debug"),
                liteRtBin = File(options.value("litert-bin") ?: env["LITERT_LM_BIN"]
                    ?: File(home, "litert-lm-env/bin/litert-lm").path),
                modelId = modelId,
                modelFile = modelFile,
                backend = backend,
                cardDataFile = File(options.value("cards") ?: env["HJP_CARD_DATA"]
                    ?: File(root, "app/src/main/assets/cards/business_cards.json").path),
                timeoutMillis = timeoutSeconds * 1_000,
                projectRoot = root,
                cacheDirectory = File(options.value("cache-dir") ?: env["LITERT_LM_CACHE_DIR"]
                    ?: File(root, "desktop-agent-runner/build/cache/litertlm/$modelId").path),
            )
        }

        private fun inferModelId(path: String): String {
            val name = File(path).name.lowercase()
            return when {
                "gemma-4" in name || "gemma4" in name -> "gemma4-e2b-it"
                "gemma3" in name || "gemma-3" in name -> "gemma3-1b-it-int4"
                else -> error(
                    "모델 파일명에서 model-id를 판단할 수 없습니다: ${File(path).name}. " +
                        "--model-id를 함께 지정하세요.",
                )
            }
        }

        private fun locateProjectRoot(start: File): File {
            var current: File? = start.absoluteFile
            while (current != null) {
                if (File(current, "settings.gradle.kts").isFile && File(current, "app").isDirectory) return current
                current = current.parentFile
            }
            return start.absoluteFile
        }
    }
}

private class CliOptions private constructor(
    private val values: Map<String, String>,
    private val flags: Set<String>,
) {
    fun value(name: String): String? = values[name]
    fun flag(name: String): Boolean = name in flags

    companion object {
        fun parse(args: Array<String>): CliOptions {
            val values = linkedMapOf<String, String>()
            val flags = linkedSetOf<String>()
            var index = 0
            while (index < args.size) {
                val token = args[index]
                require(token.startsWith("--")) { "알 수 없는 인자입니다: $token" }
                val name = token.removePrefix("--")
                if (name in setOf("debug")) {
                    flags += name
                    index += 1
                } else {
                    require(index + 1 < args.size) { "$token 뒤에 값이 필요합니다." }
                    values[name] = args[index + 1]
                    index += 2
                }
            }
            val known = setOf(
                "mode", "prompt", "backend", "timeout-seconds", "litert-bin", "model", "cards",
                "cache-dir", "model-id",
            )
            val unknown = values.keys - known
            require(unknown.isEmpty()) { "알 수 없는 옵션입니다: ${unknown.joinToString()}" }
            return CliOptions(values, flags)
        }
    }
}

private const val DEFAULT_MODEL_ID = "gemma3-1b-it-int4"
private val MODEL_FILES = linkedMapOf(
    "gemma3-1b-it-int4" to "gemma3-1b-it-int4.litertlm",
    "gemma4-e2b-it" to "gemma-4-E2B-it.litertlm",
)
