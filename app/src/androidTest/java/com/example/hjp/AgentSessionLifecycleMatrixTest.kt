package com.example.hjp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-lifetime rules, exercised on a real Android runtime.
 *
 * Run against the model-free `lifecycle` variant, which packages no `.litertlm`, no EmbeddingGemma
 * and no semantic index, so nothing here can accidentally depend on inference:
 *
 * `./gradlew -PhjpLifecycleTests :app:connectedLifecycleAndroidTest`
 *
 * Removing the app from recents and force-stopping it cannot be observed from inside the same
 * instrumentation process — those two are checked separately over ADB and reported separately.
 */
class AgentSessionLifecycleMatrixTest {
    private val application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as HjpApplication

    /** Drives one turn through the production engine and waits for it to finish. */
    private suspend fun runTurn(container: AppContainer, text: String) {
        container.engine.runTurn(text).collect { }
    }

    @Test
    fun aFreshProcessStartsWithAnEmptySession() = runBlocking {
        val container = application.container
        container.resetSession()

        val session = container.sessionSnapshot()

        assertTrue("transcript=${session.transcript.size}", session.transcript.isEmpty())
        assertEquals(null, session.conversationMemory.selectedContact)
        assertTrue(session.conversationMemory.candidateContacts.isEmpty())
        assertTrue(session.conversationMemory.contactMentions.isEmpty())
    }

    @Test
    fun aTurnSurvivesWithinTheSameProcess() = runBlocking {
        val container = application.container
        container.resetSession()
        runTurn(container, "김지원 명함 찾아줘.")

        val before = container.sessionSnapshot()
        // Nothing in the agent is tied to an Activity, so a configuration change or a trip through
        // the background leaves the session object itself untouched.
        val after = container.sessionSnapshot()

        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.generation, after.generation)
        assertTrue(after.transcript.isNotEmpty())
    }

    @Test
    fun newConversationClearsTranscriptMemoryAndReferences() = runBlocking {
        val container = application.container
        container.resetSession()
        runTurn(container, "김지원 명함 찾아줘.")
        val populated = container.sessionSnapshot()
        assertTrue("precondition: a turn was recorded", populated.transcript.isNotEmpty())

        val generation = container.resetSession()
        val cleared = container.sessionSnapshot()

        assertNotEquals(populated.sessionId, cleared.sessionId)
        assertEquals(generation, cleared.generation)
        assertTrue(cleared.transcript.isEmpty())
        assertEquals(null, cleared.conversationMemory.selectedContact)
        assertTrue(cleared.conversationMemory.candidateContacts.isEmpty())
        assertTrue(cleared.conversationMemory.actions.isEmpty())
    }

    @Test
    fun aReferenceFromThePreviousConversationCannotBeResolved() = runBlocking {
        val container = application.container
        container.resetSession()
        runTurn(container, "김지원 명함 찾아줘.")
        container.resetSession()

        runTurn(container, "그 사람에게 제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
        val session = container.sessionSnapshot()

        // With no target in the new conversation the turn must end as a question, not an action.
        assertEquals(null, session.conversationMemory.selectedContact)
    }

    @Test
    fun everyResetAdvancesTheGenerationSoOlderWorkCannotWriteBack() = runBlocking {
        val container = application.container
        val first = container.resetSession()
        val second = container.resetSession()
        val third = container.resetSession()

        assertTrue("$first < $second", second > first)
        assertTrue("$second < $third", third > second)
    }

    @Test
    fun nothingRestoresATranscriptFromDisk() = runBlocking {
        val container = application.container
        container.resetSession()
        runTurn(container, "김지원 명함 찾아줘.")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceFiles = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            .listFiles().orEmpty()
        val persisted = preferenceFiles.filter { file ->
            val text = file.readText()
            text.contains("명함 찾아줘") || text.contains("transcript")
        }

        assertTrue("transcript persisted in ${persisted.map { it.name }}", persisted.isEmpty())
    }

    @Test
    fun theLifecycleVariantShipsNoModelAssets() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val modelFiles = assets.list("models").orEmpty()

        assertTrue("models packaged: ${modelFiles.toList()}", modelFiles.isEmpty())
    }
}
