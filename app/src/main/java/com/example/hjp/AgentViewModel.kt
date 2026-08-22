package com.example.hjp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.core.AgentKernelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MessageRole { USER, ASSISTANT, STATUS }
data class ChatMessage(val role: MessageRole, val text: String)

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val status: String? = null,
    val confirmationPrompt: String? = null,
    val modelReady: Boolean = false,
    val modelPath: String = "",
    val kernelMode: AgentKernelMode = AgentKernelMode.REACT,
    val kernelSwitchAvailable: Boolean = false,
)

/**
 * Owns the on-screen transcript for the current session.
 *
 * The screen transcript is never trimmed for model-context reasons; the kernel keeps its own
 * bounded prompt context. Because the ViewModel survives configuration changes and the container
 * lives in the Application, a rotation or a background/resume keeps the same session, while a
 * process restart necessarily starts empty: nothing is persisted anywhere.
 */
class AgentViewModel(
    private val container: AppContainer,
    private val onSessionUsed: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(AgentUiState(
        modelReady = container.modelReady,
        modelPath = container.modelFile.absolutePath,
        kernelMode = container.kernelMode,
        kernelSwitchAvailable = container.kernelSwitchAvailable,
    ))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()
    private var turnJob: Job? = null

    /** Bumped by every session replacement so in-flight work cannot write into a new session. */
    private var uiGeneration = 0L

    fun refreshReadiness() {
        _state.update { it.copy(modelReady = container.modelReady) }
    }

    fun send(rawText: String) {
        val text = rawText.trim()
        // One turn at a time: a second request while the on-device model is running is rejected
        // rather than queued or run in parallel, and `새 대화` always invalidates whatever is live.
        if (text.isEmpty() || _state.value.busy || !container.modelReady) return
        onSessionUsed()
        val generation = uiGeneration
        _state.update { it.copy(
            messages = it.messages + ChatMessage(MessageRole.USER, text),
            busy = true,
            status = "요청을 해석하고 있어요.",
            confirmationPrompt = null,
        ) }
        turnJob = viewModelScope.launch {
            var assistantMessageStarted = false
            container.engine.runTurn(text)
                .catch { error ->
                    if (error is CancellationException) throw error
                    if (generation != uiGeneration) return@catch
                    _state.update { state -> state.copy(
                        messages = state.messages + ChatMessage(MessageRole.ASSISTANT, "요청을 처리하지 못했습니다."),
                        status = null,
                        confirmationPrompt = null,
                    ) }
                }
                .collect { event ->
                    if (generation != uiGeneration) return@collect
                    when (event) {
                        is AgentEvent.TurnStarted -> Unit
                        is AgentEvent.ToolStarted -> _state.update { it.copy(status = event.messageKo, confirmationPrompt = null) }
                        is AgentEvent.ToolFinished -> _state.update { it.copy(status = event.messageKo, confirmationPrompt = null) }
                        is AgentEvent.ConfirmationRequested -> _state.update { it.copy(status = event.promptKo, confirmationPrompt = event.promptKo) }
                        is AgentEvent.PermissionRequested -> _state.update { it.copy(status = "필요한 권한: ${event.permissions.joinToString()}") }
                        is AgentEvent.Token -> {
                            _state.update { state ->
                                val next = if (!assistantMessageStarted) {
                                    assistantMessageStarted = true
                                    state.messages + ChatMessage(MessageRole.ASSISTANT, event.text)
                                } else {
                                    state.messages.dropLast(1) + state.messages.last().copy(
                                        text = state.messages.last().text + event.text)
                                }
                                state.copy(messages = next, status = "답변을 작성하고 있어요.", confirmationPrompt = null)
                            }
                        }
                        is AgentEvent.FinalMessage -> {
                            if (!assistantMessageStarted && event.text.isNotBlank()) {
                                _state.update { it.copy(messages = it.messages + ChatMessage(MessageRole.ASSISTANT, event.text)) }
                            }
                        }
                        is AgentEvent.UserError -> _state.update { state -> state.copy(
                            messages = state.messages + ChatMessage(MessageRole.ASSISTANT, event.messageKo),
                            status = null,
                            confirmationPrompt = null,
                        ) }
                    }
                }
            if (generation == uiGeneration) {
                _state.update { it.copy(busy = false, status = null, confirmationPrompt = null, modelReady = container.modelReady) }
            }
        }
    }

    fun answerConfirmation(accepted: Boolean) {
        container.answerConfirmation(accepted)
        _state.update { it.copy(confirmationPrompt = null, status = if (accepted) "작업을 실행하고 있어요." else "작업을 취소하고 있어요.") }
    }

    fun cancel() {
        container.answerConfirmation(false)
        turnJob?.cancel()
        _state.update { it.copy(busy = false, status = null, confirmationPrompt = null) }
    }

    fun selectKernel(mode: AgentKernelMode) {
        if (!container.kernelSwitchAvailable || _state.value.busy) return
        viewModelScope.launch {
            val active = container.selectKernel(mode)
            uiGeneration += 1
            _state.update { AgentUiState(
                status = "${active.name} 커널로 새 대화를 시작했습니다.",
                modelReady = container.modelReady,
                modelPath = container.modelFile.absolutePath,
                kernelMode = active,
                kernelSwitchAvailable = container.kernelSwitchAvailable,
            ) }
        }
    }

    /** `새 대화`: the screen transcript and every agent-side state go together, atomically. */
    fun resetSession() {
        container.answerConfirmation(false)
        uiGeneration += 1
        turnJob?.cancel()
        turnJob = null
        _state.update { it.copy(busy = true, status = "세션을 초기화하고 있어요.", confirmationPrompt = null) }
        viewModelScope.launch {
            try {
                container.resetSession()
                _state.update { AgentUiState(
                    status = "새 대화를 시작했습니다.",
                    confirmationPrompt = null,
                    modelReady = container.modelReady,
                    modelPath = container.modelFile.absolutePath,
                    kernelMode = container.kernelMode,
                    kernelSwitchAvailable = container.kernelSwitchAvailable,
                ) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(
                    busy = false,
                    status = "세션을 초기화하지 못했습니다.",
                    confirmationPrompt = null,
                ) }
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            onSessionUsed: () -> Unit = {},
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AgentViewModel(container, onSessionUsed) as T
        }
    }
}
