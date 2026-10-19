package com.aibyjohannes.alfred.ui.home

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.core.engine.AskUserAnswer
import com.aibyjohannes.alfred.core.engine.AskUserHandler
import com.aibyjohannes.alfred.core.engine.AskUserRequest
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.local.ConversationStore
import com.aibyjohannes.alfred.data.local.ConversationSummary
import com.aibyjohannes.alfred.data.local.WorkspaceSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeFragmentAskUserTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private val apiKeyStore: ApiKeyStore = mockk(relaxed = true)
    private val repository: ChatRepository = mockk(relaxed = true)
    private val conversationStore: ConversationStore = mockk(relaxed = true)
    private lateinit var controller: ActivityController<QuestionHostActivity>
    private lateinit var activity: QuestionHostActivity
    private lateinit var fragment: HomeFragment
    private lateinit var viewModel: HomeViewModel
    private val answers = mutableListOf<AskUserAnswer>()
    private val releaseCompletion = CompletableDeferred<Unit>()
    private var waiterCancelled = false
    private var completed = false

    class QuestionHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Alfred_NoActionBar)
            super.onCreate(savedInstanceState)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { apiKeyStore.hasApiKey() } returns true
        every { conversationStore.storageIdentity } returns null
        val workspace = WorkspaceSummary("workspace", "Personal")
        val conversation = ConversationSummary("conversation", "Clarification", 1L)
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns workspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(workspace)
        coEvery { conversationStore.observeConversations(any()) } returns flowOf(listOf(conversation))
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        coEvery { conversationStore.loadMessages("conversation") } returns emptyList()

        controller = Robolectric.buildActivity(QuestionHostActivity::class.java).setup()
        activity = controller.get()
        ApiKeyStore(activity).saveCostConfirmation(false)
        viewModel = ViewModelProvider(activity, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(dispatcher) as T
        })[HomeViewModel::class.java]
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        settle()
        fragment = HomeFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        settle()
    }

    @After
    fun tearDown() {
        try {
            controller.pause().stop().destroy()
            dispatcher.scheduler.advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `message viewport remains usable when clarification and loop panels are hidden`() {
        val root = fragment.requireView()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 1800)
        val messages = control<View>(R.id.messages_recycler_view)
        val input = control<View>(R.id.input_layout)
        assertEquals(View.GONE, control<View>(R.id.user_question_panel).visibility)
        assertEquals(View.GONE, control<View>(R.id.loop_limit_continuation_layout).visibility)
        assertTrue("Chat history must occupy the available viewport", messages.height > 900)
        assertTrue("Chat history must not overlap input", messages.bottom <= input.top)
    }

    @Test
    fun `choice and text are exclusive and explicit Submit resumes the current run`() {
        startQuestion()
        assertBusy()
        assertFalse(control<View>(R.id.user_question_submit).isEnabled)
        assertEquals("Which project?", control<TextView>(R.id.user_question_text).text.toString())
        coVerify(exactly = 0) { conversationStore.appendMessages(any(), any()) }

        choice("Work").performClick()
        settle()
        assertEquals("Work", viewModel.pendingUserQuestion.value!!.selectedOption)
        assertTrue(control<View>(R.id.user_question_submit).isEnabled)
        assertTrue(answers.isEmpty())

        customAnswer().setText("Household")
        settle()
        assertEquals(-1, options().checkedRadioButtonId)
        assertNull(viewModel.pendingUserQuestion.value!!.selectedOption)
        assertEquals("Household", viewModel.pendingUserQuestion.value!!.customText)

        choice("Personal").performClick()
        settle()
        assertEquals("", customAnswer().text.toString())
        assertEquals("Personal", viewModel.pendingUserQuestion.value!!.selectedOption)
        control<View>(R.id.user_question_submit).performClick()
        settle()

        assertEquals(listOf(AskUserAnswer.Option("Personal")), answers)
        assertPanelRemoved()
        assertBusy()
        completeRun()
        assertEquals("Continued after clarification", viewModel.messages.value!!.last().content)
        verify(exactly = 1) { repository.streamMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `blank custom text disables Submit and multiline Unicode answer survives actual controls`() {
        startQuestion()
        customAnswer().setText(" \n\t ")
        settle()
        assertFalse(control<View>(R.id.user_question_submit).isEnabled)
        assertTrue(answers.isEmpty())

        val text = "Work \"urgent\"\nHushåll 東京 ☕"
        customAnswer().setText(text)
        settle()
        assertTrue(control<View>(R.id.user_question_submit).isEnabled)
        assertEquals(-1, options().checkedRadioButtonId)
        control<View>(R.id.user_question_submit).performClick()
        settle()

        assertEquals(listOf(AskUserAnswer.Text(text)), answers)
        assertPanelRemoved()
        completeRun()
    }

    @Test
    fun `Cancel question is a normal answer and leaves Stop available until completion`() {
        startQuestion()
        customAnswer().setText("An abandoned draft")
        control<View>(R.id.user_question_cancel).performClick()
        settle()

        assertEquals(listOf(AskUserAnswer.Cancelled), answers)
        assertFalse(waiterCancelled)
        assertPanelRemoved()
        assertBusy()
        assertFalse(completed)
        completeRun()
        assertTrue(completed)
        assertEquals("Continued after clarification", viewModel.messages.value!!.last().content)
    }

    @Test
    fun `Stop cancels the suspended waiter without submitting its draft or persisting a turn`() {
        startQuestion()
        customAnswer().setText("Do not submit me")
        control<View>(R.id.send_button).performClick()
        settle()

        assertTrue(waiterCancelled)
        assertTrue(answers.isEmpty())
        assertFalse(completed)
        assertPanelRemoved()
        assertEquals(false, viewModel.isLoading.value)
        assertTrue(control<View>(R.id.message_input).isEnabled)
        assertTrue(control<View>(R.id.btn_add).isEnabled)
        assertTrue(control<View>(R.id.btn_mic).isEnabled)
        releaseCompletion.complete(Unit)
        settle()
        assertFalse(completed)
        coVerify(exactly = 0) { conversationStore.appendMessages(any(), any()) }
        verify(exactly = 1) { repository.streamMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `question and draft survive same-root initialization and actual view destruction`() {
        startQuestion()
        val id = viewModel.pendingUserQuestion.value!!.id
        val draft = "Keep this\nquoted \"draft\" 日本語"
        customAnswer().setText(draft)
        settle()
        val oldRoot = fragment.requireView()

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        settle()
        assertNull(fragment.view)
        assertEquals(draft, viewModel.pendingUserQuestion.value!!.customText)
        assertFalse(waiterCancelled)
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        settle()

        assertNotSame(oldRoot, fragment.requireView())
        assertEquals(id, viewModel.pendingUserQuestion.value!!.id)
        assertEquals(draft, customAnswer().text.toString())
        assertEquals(-1, options().checkedRadioButtonId)
        assertTrue(control<View>(R.id.user_question_submit).isEnabled)
        assertBusy()
        assertTrue(answers.isEmpty())
        control<View>(R.id.user_question_submit).performClick()
        settle()
        assertEquals(listOf(AskUserAnswer.Text(draft)), answers)
        completeRun()
        verify(exactly = 1) { repository.streamMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `selected choice survives view recreation without submitting automatically`() {
        startQuestion()
        choice("Work").performClick()
        settle()
        val id = viewModel.pendingUserQuestion.value!!.id
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        settle()
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        settle()

        assertEquals(id, viewModel.pendingUserQuestion.value!!.id)
        assertTrue(choice("Work").isChecked)
        assertEquals("", customAnswer().text.toString())
        assertTrue(control<View>(R.id.user_question_submit).isEnabled)
        assertTrue(answers.isEmpty())
        control<View>(R.id.user_question_submit).performClick()
        settle()
        assertEquals(listOf(AskUserAnswer.Option("Work")), answers)
        completeRun()
    }

    @Test
    fun `text-only request hides choices and supports a submitted custom answer`() {
        startQuestion(AskUserRequest("What should I call it?"))
        assertEquals(View.GONE, options().visibility)
        assertTrue(customAnswer().isEnabled)
        assertFalse(control<View>(R.id.user_question_submit).isEnabled)
        customAnswer().setText("Household")
        settle()
        assertTrue(control<View>(R.id.user_question_submit).isEnabled)
        control<View>(R.id.user_question_submit).performClick()
        settle()

        assertEquals(listOf(AskUserAnswer.Text("Household")), answers)
        assertPanelRemoved()
        completeRun()
    }

    @Test
    fun `long question stays scrollable with actions outside the height cap after keyboard resize`() {
        startQuestion(AskUserRequest(
            question = "Which project should I use? ".repeat(60),
            options = (1..30).map { "Project $it" }
        ))
        val root = fragment.requireView()
        val panel = control<ViewGroup>(R.id.user_question_panel)
        fun descendants(group: ViewGroup): Sequence<View> = sequence {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                yield(child)
                if (child is ViewGroup) yieldAll(descendants(child))
            }
        }
        val scroll = descendants(panel).filterIsInstance<NestedScrollView>().single()
        for (height in listOf(1800, 900)) {
            repeat(2) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, 1080, height)
                settle()
            }
            assertTrue("Question body must have a usable viewport", scroll.height > 0)
            assertTrue("Question body must fit the resized Home view", scroll.height <= height * 0.4f)
            assertTrue("Long content must scroll rather than expand the panel",
                scroll.getChildAt(0).height > scroll.height)
            assertFalse(descendants(scroll).any { it.id == R.id.user_question_submit })
            assertFalse(descendants(scroll).any { it.id == R.id.user_question_cancel })
            assertTrue(control<View>(R.id.user_question_cancel).isShown)
            assertBusy()
        }
        control<View>(R.id.user_question_cancel).performClick()
        settle()
        assertEquals(listOf(AskUserAnswer.Cancelled), answers)
        completeRun()
    }

    private fun startQuestion(
        request: AskUserRequest = AskUserRequest("Which project?", listOf("Personal", "Work"))
    ) {
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } answers {
            val handler = requireNotNull(arg<AskUserHandler?>(5))
            flow {
                emit(ChatStreamEvent.PassStarted(0))
                try {
                    this@HomeFragmentAskUserTest.answers += handler.askUser(request)
                } catch (error: CancellationException) {
                    waiterCancelled = true
                    throw error
                }
                releaseCompletion.await()
                completed = true
                emit(ChatStreamEvent.Completed(ChatTurnResult(
                    content = "Continued after clarification",
                    toolCalls = emptyList()
                )))
            }
        }
        control<EditText>(R.id.message_input).setText("Ask me which project")
        control<View>(R.id.send_button).performClick()
        settle()
        assertTrue(viewModel.pendingUserQuestion.value != null)
        assertEquals(View.VISIBLE, control<View>(R.id.user_question_panel).visibility)
    }

    private fun completeRun() {
        releaseCompletion.complete(Unit)
        settle()
        assertEquals(false, viewModel.isLoading.value)
        assertPanelRemoved()
        assertTrue(control<View>(R.id.message_input).isEnabled)
        coVerify(exactly = 1) { conversationStore.appendMessages("conversation", any()) }
    }

    private fun assertBusy() {
        assertEquals(true, viewModel.isLoading.value)
        assertFalse(control<View>(R.id.message_input).isEnabled)
        assertFalse(control<View>(R.id.btn_add).isEnabled)
        assertFalse(control<View>(R.id.btn_mic).isEnabled)
        assertTrue(control<View>(R.id.send_button).isEnabled)
    }

    private fun assertPanelRemoved() {
        assertNull(viewModel.pendingUserQuestion.value)
        assertEquals(View.GONE, control<View>(R.id.user_question_panel).visibility)
    }

    private fun options(): RadioGroup = control(R.id.user_question_options)
    private fun customAnswer(): EditText = control(R.id.user_question_custom_answer)
    private fun choice(label: String): RadioButton = (0 until options().childCount)
        .map { options().getChildAt(it) }
        .filterIsInstance<RadioButton>()
        .single { it.text.toString() == label }

    private fun <T : View> control(id: Int): T = fragment.requireView().findViewById(id)

    private fun settle() {
        dispatcher.scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        dispatcher.scheduler.advanceUntilIdle()
    }
}
