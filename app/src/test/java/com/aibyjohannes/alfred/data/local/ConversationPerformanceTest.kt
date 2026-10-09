package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPerformanceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `reopening and New Chat do not read unrelated logs or rebuild the index`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val store = FileConversationStore(storage, ObjectMapper(), index)
        val originals = (1..120).map {
            store.createConversation().also { chat -> store.appendMessage(chat.id, ChatMessage.ROLE_USER, "history $it") }
        }
        storage.reset(); index.fullReplacements = 0

        val reopened = FileConversationStore(storage, ObjectMapper(), index)
        reopened.getOrCreateActiveWorkspace()
        val fresh = reopened.createConversation()
        assertTrue(reopened.loadMessages(fresh.id).isEmpty())

        assertEquals(0, storage.listings)
        assertEquals(0, index.fullReplacements)
        assertTrue(storage.reads.none { path -> originals.any { path.last() == "conversation-${it.id}.jsonl" } })
        assertEquals(121, reopened.listConversations().size)
    }

    @Test
    fun `an uncached switch reads one log and a repeated switch reads none`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val original = FileConversationStore(storage, ObjectMapper(), index)
        val first = original.createConversation()
        original.appendMessage(first.id, ChatMessage.ROLE_USER, "first")
        val second = original.createConversation()
        original.appendMessage(second.id, ChatMessage.ROLE_USER, "second")
        val reopened = FileConversationStore(storage, ObjectMapper(), index)
        reopened.listConversations()
        storage.reset()

        reopened.switchActiveConversation(first.id)
        assertEquals("first", reopened.loadMessages(first.id).single().content)
        assertEquals(listOf("conversation-${first.id}.jsonl"), storage.reads.map { it.last() })
        reopened.loadMessages(second.id)
        storage.reset()
        reopened.switchActiveConversation(first.id)
        assertEquals("first", reopened.loadMessages(first.id).single().content)
        assertTrue(storage.reads.isEmpty())
    }

    @Test
    fun `save delete restore and workspace changes update only their own files`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val store = FileConversationStore(storage, ObjectMapper(), index)
        val chat = store.createConversation()
        val unrelated = store.createConversation()
        val workspace = store.getOrCreateActiveWorkspace()
        storage.reset(); index.fullReplacements = 0

        store.appendMessages(chat.id, listOf(
            ConversationMessageDraft(ChatMessage.ROLE_USER, "hello"),
            ConversationMessageDraft("tool", "payload", turnId = "turn", toolName = "weather", searchable = false)
        ))
        store.deleteConversation(chat.id)
        store.restoreConversation(chat.id)
        store.renameWorkspace(workspace.id, "Renamed")

        assertEquals(0, storage.listings)
        assertEquals(0, index.fullReplacements)
        assertTrue(storage.reads.none { it.last() == "conversation-${unrelated.id}.jsonl" })
        val restored = FileConversationStore(storage, ObjectMapper(), index).loadMessages(chat.id)
        assertEquals(listOf("hello", "payload"), restored.map { it.content })
        assertEquals("weather", restored.last().toolName)
        assertFalse(restored.last().searchable)
    }

    @Test
    fun `partial append failure leaves a dirty cache and same-instance recovery reads the durable event`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val store = FileConversationStore(storage, ObjectMapper(), index)
        val chat = store.createConversation()
        storage.failAfterAppend = true

        assertTrue(runCatching { store.appendMessage(chat.id, ChatMessage.ROLE_USER, "durable") }.isFailure)
        assertTrue(index.cacheState()!!.endsWith("\ndirty"))
        assertEquals("durable", store.loadMessages(chat.id).single().content)
        assertFalse(index.cacheState()!!.endsWith("\ndirty"))
    }

    @Test
    fun `index failure after a successful append recovers across process restart`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val store = FileConversationStore(storage, ObjectMapper(), index)
        val chat = store.createConversation()
        index.failNextUpdate = true

        assertTrue(runCatching { store.appendMessage(chat.id, ChatMessage.ROLE_USER, "file wins") }.isFailure)
        assertTrue(index.cacheState()!!.endsWith("\ndirty"))
        assertEquals("file wins", FileConversationStore(storage, ObjectMapper(), index).loadMessages(chat.id).single().content)
    }

    @Test
    fun `another store instance invalidates warm messages without a full scan`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val first = FileConversationStore(storage, ObjectMapper(), index)
        val chat = first.createConversation()
        first.appendMessage(chat.id, ChatMessage.ROLE_USER, "before")
        first.loadMessages(chat.id)
        FileConversationStore(storage, ObjectMapper(), index).appendMessage(chat.id, ChatMessage.ROLE_ASSISTANT, "after")
        storage.reset(); index.fullReplacements = 0

        assertEquals(listOf("before", "after"), first.loadMessages(chat.id).map { it.content })
        assertEquals(0, storage.listings)
        assertEquals(0, index.fullReplacements)
    }

    @Test
    fun `folder changes and an emptied cache rebuild from the correct files`() = runTest {
        val firstRoot = temporaryFolder.newFolder()
        val secondRoot = temporaryFolder.newFolder()
        val firstStorage = FileChatHistoryStorage(firstRoot)
        val secondStorage = FileChatHistoryStorage(secondRoot)
        val index = CountingIndex()
        val first = FileConversationStore(firstStorage, ObjectMapper(), index)
        val firstChat = first.createConversation()
        first.appendMessage(firstChat.id, ChatMessage.ROLE_USER, "first root")
        val seededSecond = FileConversationStore(secondRoot)
        val secondChat = seededSecond.createConversation()
        seededSecond.appendMessage(secondChat.id, ChatMessage.ROLE_USER, "second root")

        val second = FileConversationStore(secondStorage, ObjectMapper(), index)
        assertEquals(secondChat.id, second.getOrCreateActiveConversation().id)
        assertEquals("second root", second.loadMessages(secondChat.id).single().content)
        index.replaceFrom(ConversationIndexSnapshot(emptyList(), emptyList()))
        assertEquals(secondChat.id, FileConversationStore(secondStorage, ObjectMapper(), index).getOrCreateActiveConversation().id)
        assertEquals("first root", first.loadMessages(firstChat.id).single().content)
    }

    @Test
    fun `session search includes indexed messages that have never been opened`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val original = FileConversationStore(storage, ObjectMapper(), index)
        val chat = original.createConversation()
        original.appendMessage(chat.id, ChatMessage.ROLE_USER, "unique astronomy topic")
        original.createConversation()
        val reopened = FileConversationStore(storage, ObjectMapper(), index)
        storage.reset()

        assertEquals(chat.id, reopened.searchSessionMessages("astronomy", 10).single().conversationId)
        assertTrue(storage.reads.isEmpty())
    }

    @Test
    fun `cached search keeps unopened history isolated by workspace`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val original = FileConversationStore(storage, ObjectMapper(), index)
        val firstWorkspace = original.getOrCreateActiveWorkspace()
        val first = original.createConversation()
        original.appendMessage(first.id, ChatMessage.ROLE_USER, "astronomy in personal")
        val secondWorkspace = original.createWorkspace("Research")
        val second = original.createConversation()
        original.appendMessage(second.id, ChatMessage.ROLE_USER, "astronomy in research")
        val reopened = FileConversationStore(storage, ObjectMapper(), index)
        storage.reset()

        assertEquals(second.id, reopened.searchSessionMessages("astronomy", 10).single().conversationId)
        assertEquals(first.id, reopened.searchSessionMessages(firstWorkspace.id, "astronomy", 10).single().conversationId)
        assertEquals(second.id, reopened.searchSessionMessages(secondWorkspace.id, "astronomy", 10).single().conversationId)
        assertTrue(storage.reads.isEmpty())
    }

    @Test
    fun `reconciliation removes missing files from the cache without changing surviving history`() = runTest {
        val storage = FileChatHistoryStorage(temporaryFolder.newFolder())
        val index = CountingIndex()
        val store = FileConversationStore(storage, ObjectMapper(), index)
        val missing = store.createConversation()
        store.appendMessage(missing.id, ChatMessage.ROLE_USER, "removed topic")
        val survivor = store.createConversation()
        store.appendMessage(survivor.id, ChatMessage.ROLE_USER, "surviving topic")
        val folder = storage.listChildren(listOf("workspaces")).single().name
        storage.delete(listOf("workspaces", folder, "conversation-${missing.id}.jsonl"))

        val reopened = FileConversationStore(storage, ObjectMapper(), index)
        reopened.reconcileHistory()

        assertEquals(listOf(survivor.id), reopened.listConversations().map { it.id })
        assertTrue(reopened.searchSessionMessages("removed", 10).isEmpty())
        assertEquals("surviving topic", reopened.loadMessages(survivor.id).single().content)
    }

    @Test
    fun `background reconciliation releases the lock between conversations and preserves an intervening save`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val original = FileConversationStore(storage, ObjectMapper(), index, dispatcher)
        val chats = (1..3).map { original.createConversation() }
        val reopened = FileConversationStore(storage, ObjectMapper(), index, dispatcher)
        val firstBatch = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val updateOrder = mutableListOf<String>()
        index.onUpdate = { chat ->
            updateOrder += chat.id
            if (updateOrder.size == 1) { firstBatch.complete(Unit); release.await() }
        }
        val reconciliation = launch { reopened.reconcileHistory() }
        firstBatch.await()
        val save = launch { reopened.appendMessage(chats.last().id, ChatMessage.ROLE_USER, "intervening save") }
        runCurrent()
        release.complete(Unit)
        save.join()
        reconciliation.join()

        assertEquals(chats.last().id, updateOrder[1])
        assertEquals("intervening save", reopened.loadMessages(chats.last().id).single().content)
    }

    @Test
    fun `a slow background read does not block New Chat and cannot overwrite a newer save`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val original = FileConversationStore(storage, ObjectMapper(), index)
        val chat = original.createConversation()
        original.appendMessage(chat.id, ChatMessage.ROLE_USER, "before")
        val reopened = FileConversationStore(storage, ObjectMapper(), index)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val intercepted = AtomicBoolean(false)
        storage.onRead = { path ->
            if (path.last() == "conversation-${chat.id}.jsonl" && intercepted.compareAndSet(false, true)) {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release background read" }
            }
        }
        val reconciliation = launch { reopened.reconcileHistory() }
        try {
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    reopened.createConversation()
                    reopened.appendMessage(chat.id, ChatMessage.ROLE_ASSISTANT, "after")
                }
            }
        } finally { release.countDown() }
        reconciliation.join()

        assertEquals(listOf("before", "after"), reopened.loadMessages(chat.id).map { it.content })
        assertEquals(2, reopened.listConversations().size)
        assertEquals(listOf("before", "after"), index.searchableMessages().map { it.content })
    }

    @Test
    fun `an old background read cannot publish into a replacement root cache`() = runTest {
        val storage = CountingStorage(FileChatHistoryStorage(temporaryFolder.newFolder()))
        val index = CountingIndex()
        val original = FileConversationStore(storage, ObjectMapper(), index)
        val oldChat = original.createConversation()
        original.appendMessage(oldChat.id, ChatMessage.ROLE_USER, "old root history")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val intercepted = AtomicBoolean(false)
        storage.onRead = { path ->
            if (path.last() == "conversation-${oldChat.id}.jsonl" && intercepted.compareAndSet(false, true)) {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release background read" }
            }
        }
        val reopened = FileConversationStore(storage, ObjectMapper(), index)
        val reconciliation = launch { reopened.reconcileHistory() }
        val replacement = FileConversationStore(
            FileChatHistoryStorage(temporaryFolder.newFolder()), ObjectMapper(), index
        )
        val replacementChat = try {
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    replacement.createConversation().also {
                        replacement.appendMessage(it.id, ChatMessage.ROLE_USER, "replacement root history")
                    }
                }
            }
        } finally {
            release.countDown()
        }
        reconciliation.join()

        assertEquals(listOf(replacementChat.id), replacement.listConversations().map { it.id })
        assertEquals(listOf(replacementChat.id), index.readSnapshot().conversations.map { it.id })
        assertEquals(listOf("replacement root history"), index.searchableMessages().map { it.content })
    }

    private class CountingStorage(private val delegate: ChatHistoryStorage) : ChatHistoryStorage by delegate {
        val reads: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        var listings = 0
        var failAfterAppend = false
        var onRead: ((List<String>) -> Unit)? = null
        fun reset() { reads.clear(); listings = 0 }
        override fun readText(path: List<String>): StorageReadResult {
            reads += path
            val result = delegate.readText(path)
            onRead?.invoke(path)
            return result
        }
        override fun listChildren(path: List<String>): List<StorageEntry> { listings++; return delegate.listChildren(path) }
        override fun appendLine(path: List<String>, line: String, mimeType: String) {
            delegate.appendLine(path, line, mimeType)
            if (failAfterAppend) { failAfterAppend = false; throw IllegalStateException("Injected append failure") }
        }
    }

    private class CountingIndex(private val delegate: ConversationIndex = MemoryConversationIndex()) : ConversationIndex by delegate {
        var fullReplacements = 0
        var failNextUpdate = false
        var onUpdate: (suspend (IndexedConversation) -> Unit)? = null
        override suspend fun replaceFrom(snapshot: ConversationIndexSnapshot) { fullReplacements++; delegate.replaceFrom(snapshot) }
        override suspend fun upsertConversation(conversation: IndexedConversation) {
            if (failNextUpdate) { failNextUpdate = false; throw IllegalStateException("Injected index failure") }
            delegate.upsertConversation(conversation)
            onUpdate?.invoke(conversation)
        }
    }
}
