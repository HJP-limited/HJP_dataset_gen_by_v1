package com.hjp.tool.android

data class CalendarDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String?,
    val attendeeEmails: List<String>,
)

data class MessageDraft(
    val channel: MessageChannel,
    val to: String,
    val subject: String?,
    val body: String,
)

enum class MessageChannel { EMAIL, SMS }

data class MessageDraftRequest(
    val channel: MessageChannel,
    val recipientName: String?,
    val purpose: String,
    val userContext: String,
    val requestedTone: String? = null,
)

data class GeneratedMessageDraft(
    val subject: String?,
    val body: String,
)

data class MessageDraftGeneration(
    val draft: GeneratedMessageDraft,
    val source: String,
    val rawOutput: String? = null,
    val jsonParsed: Boolean,
    val usedFallback: Boolean,
    val validationReason: String,
)

interface MessageDraftGenerator {
    suspend fun generate(request: MessageDraftRequest): MessageDraftGeneration
}

/** Platform boundary implemented with Android Intents in the app and safe recorders on Desktop. */
interface CalendarComposerBackend {
    val isMock: Boolean get() = false
    fun isAvailable(): Boolean
    suspend fun open(draft: CalendarDraft): Boolean
}

/** Platform boundary implemented with Android Intents in the app and safe recorders on Desktop. */
interface MessageComposerBackend {
    val isMock: Boolean get() = false
    fun isAvailable(channel: MessageChannel? = null): Boolean
    suspend fun open(draft: MessageDraft): Boolean
}
