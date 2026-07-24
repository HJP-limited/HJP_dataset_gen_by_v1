package com.hjp.desktop

import com.hjp.tool.android.MessageDraftGeneration
import com.hjp.tool.android.MessageDraftGenerator
import com.hjp.tool.android.MessageDraftPrompt
import com.hjp.tool.android.MessageDraftRequest
import com.hjp.tool.android.SafeMessageDraftGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubprocessMessageDraftGenerator(
    private val runner: ModelTextRunner,
    private val modelId: String,
) : MessageDraftGenerator {
    override suspend fun generate(request: MessageDraftRequest): MessageDraftGeneration =
        try {
            val output = withContext(Dispatchers.IO) {
                runner.generate(MessageDraftPrompt.build(request))
            }
            SafeMessageDraftGenerator.fromModelOutput(
                request = request,
                rawOutput = output.generatedText,
                source = modelId,
            )
        } catch (error: Throwable) {
            SafeMessageDraftGenerator.fallback(
                request = request,
                source = modelId,
                reason = "model_error:${error::class.java.simpleName}",
            )
        }
}
