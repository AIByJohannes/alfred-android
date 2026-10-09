package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileConversationStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `new chat always creates a distinct persisted conversation`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val first = store.getOrCreateActiveConversation()

        val second = store.createConversation()

        assertNotEquals(first.id, second.id)
        assertEquals(2, store.listConversations().size)
        assertEquals(2, conversationFiles(root).size)
    }

    @Test
    fun `creation preserves existing file and publishes new visible row`() = runTest {
        val root = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(root)
        val index = MemoryConversationIndex()
        val store = FileConversationStore(storage, ObjectMapper(), index)
        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "Existing history")
        val firstFile = conversationFiles(root).single()
        val originalBytes = firstFile.readBytes()
        val workspaceId = store.listWorkspaces().single().id
        val observed = async { store.observeConversations(workspaceId).first() }
        testScheduler.runCurrent()

        val second = store.createConversation()

        assertNotEquals(first.id, second.id)
        assertTrue(originalBytes.contentEquals(firstFile.readBytes()))
        assertTrue(conversationFiles(root).single { it.name.contains(second.id) }.readText().contains("conversationCreated"))
        assertTrue(observed.await().any { it.id == first.id })
        assertTrue(store.observeConversations(store.listWorkspaces().single().id).first().any { it.id == second.id })
    }

    @Test
    fun `workspace switch and new chat never modify previous conversation file`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "This chat must survive")
        val firstFile = conversationFiles(root).single()
        val bytesBefore = firstFile.readBytes()

        store.createWorkspace("Work")
        val second = store.getOrCreateActiveConversation()

        assertNotEquals(first.id, second.id)
        assertTrue(bytesBefore.contentEquals(firstFile.readBytes()))
        store.switchActiveWorkspace(store.listWorkspaces().first { it.name == "Personal" }.id)
        assertEquals("This chat must survive", store.loadMessages(first.id).single().content)
    }

    @Test
    fun `reopening store restores workspaces chats titles and messages from files`() = runTest {
        val root = temporaryFolder.newFolder()
        val original = FileConversationStore(root)
        val personal = original.getOrCreateActiveConversation()
        original.appendMessage(personal.id, ChatMessage.ROLE_USER, "Persistent title and message")
        val work = original.createWorkspace("Work")
        val workChat = original.getOrCreateActiveConversation()
        original.appendMessage(workChat.id, ChatMessage.ROLE_USER, "Work survives")

        val reopened = FileConversationStore(root)
        reopened.rebuildIndexFromFiles()

        assertEquals(setOf("Personal", "Work"), reopened.listWorkspaces().map { it.name }.toSet())
        reopened.switchActiveWorkspace(work.id)
        assertEquals(workChat.id, reopened.listConversations().single().id)
        assertEquals("Work survives", reopened.loadMessages(workChat.id).single().content)
        assertTrue(conversationFiles(root).all { it.readLines().first().contains("conversationCreated") })
    }

    @Test
    fun `conversation deletion is a tombstone and preserves the event log`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Keep recoverable bytes")
        val file = conversationFiles(root).single()

        store.deleteConversation(conversation.id)

        assertTrue(file.exists())
        assertTrue(file.readText().contains("conversationDeleted"))
        assertTrue(store.listConversations().isEmpty())
        assertTrue(store.loadMessages(conversation.id).isEmpty())
    }

    @Test
    fun `legacy metadata and headerless chat migrate idempotently`() = runTest {
        val root = temporaryFolder.newFolder()
        val workspace = root.resolve("workspace-1-personal").apply { mkdirs() }
        val chat = workspace.resolve("conversation-1.jsonl")
        chat.writeText(
            """{"id":1,"conversationId":1,"role":"user","content":"Legacy survives","createdAtEpochMs":123}""" + "\n"
        )
        root.resolve("metadata.json").writeText(
            """
            {
              "nextWorkspaceId": 2,
              "nextConversationId": 2,
              "workspaces": [{"id":1,"name":"Personal","folderName":"workspace-1-personal","createdAtEpochMs":100}],
              "conversations": [{"id":1,"title":"Legacy survives","createdAtEpochMs":100,"updatedAtEpochMs":123,"workspaceId":1,"workspaceFolderName":"workspace-1-personal","fileName":"conversation-1.jsonl"}]
            }
            """.trimIndent()
        )

        val migrated = FileConversationStore(root)
        val conversation = migrated.getOrCreateActiveConversation()

        assertEquals("legacy-1", conversation.id)
        assertEquals("Legacy survives", migrated.loadMessages(conversation.id).single().content)
        assertFalse(root.resolve("metadata.json").exists())
        val newWorkspace = root.resolve("workspaces/1-personal")
        val newChat = newWorkspace.resolve("conversation-1.jsonl")
        assertTrue(newWorkspace.resolve("workspace.jsonl").readText().contains("workspaceCreated"))
        assertTrue(newChat.readLines().first().contains("conversationCreated"))
        val once = newChat.readText()
        FileConversationStore(root).rebuildIndexFromFiles()
        assertEquals(once, newChat.readText())
    }

    @Test
    fun `failed index rebuild keeps legacy metadata for a safe retry`() = runTest {
        val root = temporaryFolder.newFolder()
        val workspace = root.resolve("workspace-1-personal").apply { mkdirs() }
        workspace.resolve("conversation-1.jsonl").writeText(
            """{"id":1,"conversationId":1,"role":"user","content":"Retry survives","createdAtEpochMs":123}""" + "\n"
        )
        root.resolve("metadata.json").writeText(
            """{"workspaces":[{"id":1,"name":"Personal","folderName":"workspace-1-personal","createdAtEpochMs":100}],"conversations":[{"id":1,"workspaceId":1,"workspaceFolderName":"workspace-1-personal","fileName":"conversation-1.jsonl","createdAtEpochMs":100}]}"""
        )
        val failingIndex = FailingConversationIndex().apply { failNextReplace = true }

        val failure = runCatching {
            FileConversationStore(FileChatHistoryStorage(root), ObjectMapper(), failingIndex)
                .getOrCreateActiveConversation()
        }

        assertTrue(failure.isFailure)
        assertTrue(root.resolve("metadata.json").exists())
        val recovered = FileConversationStore(root)
        assertEquals("Retry survives", recovered.loadMessages("legacy-1").single().content)
        assertFalse(root.resolve("metadata.json").exists())
    }

    @Test
    fun `concurrent appends and new chats preserve all target files`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val original = store.getOrCreateActiveConversation()

        val jobs = (1..20).map { number ->
            async { store.appendMessage(original.id, ChatMessage.ROLE_USER, "message-$number") }
        } + (1..8).map { async { store.createConversation() } }
        jobs.awaitAll()

        assertEquals(20, store.loadMessages(original.id).size)
        assertEquals(9, store.listConversations().size)
        assertEquals(9, conversationFiles(root).size)
        assertTrue(conversationFiles(root).all { it.readLines().first().contains("conversationCreated") })
    }

    @Test
    fun `file success followed by index failure is repaired from files`() = runTest {
        val root = temporaryFolder.newFolder()
        val index = FailingConversationIndex()
        val store = FileConversationStore(FileChatHistoryStorage(root), ObjectMapper(), index)
        val conversation = store.getOrCreateActiveConversation()
        index.failNextUpsert = true

        val failure = runCatching {
            store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "File wins")
        }

        assertTrue(failure.isFailure)
        assertEquals("File wins", store.loadMessages(conversation.id).single().content)
        assertEquals(1, store.searchSessionMessages("File wins", 10).size)
    }

    @Test
    fun `partial final JSONL record does not hide earlier durable events`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Durable")
        conversationFiles(root).single().appendText("{partial")

        assertEquals("Durable", FileConversationStore(root).loadMessages(conversation.id).single().content)
    }

    @Test
    fun `create delete then restore reconciles to deleted 0 and appears in list Flow`() = runTest {
        // membership = latest delete/restore event by append order
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        
        store.deleteConversation(conversation.id)
        assertTrue(store.listConversations().isEmpty())
        
        store.restoreConversation(conversation.id)
        assertEquals(conversation.id, store.listConversations().single().id)
    }

    @Test
    fun `create restore then delete reconciles to deleted 1`() = runTest {
        // membership = latest delete/restore event by append order
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        
        store.restoreConversation(conversation.id)
        store.deleteConversation(conversation.id)
        
        assertTrue(store.listConversations().isEmpty())
    }

    @Test
    fun `restore on an already-active conversation is a no-op`() = runTest {
        // membership = latest delete/restore event by append order
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        val file = conversationFiles(root).single()
        val originalText = file.readText()
        
        store.restoreConversation(conversation.id)
        assertEquals(originalText, file.readText())
    }

    @Test
    fun `in-session delete-then-restore leaves the row present in the list Flow`() = runTest {
        // membership = latest delete/restore event by append order
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        
        store.deleteConversation(conversation.id)
        assertTrue(store.listConversations().isEmpty())
        
        store.restoreConversation(conversation.id)
        assertEquals(conversation.id, store.listConversations().single().id)
    }

    @Test
    fun `new workspaces are stored inside workspaces directory`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        
        val workspacesDir = root.resolve("workspaces")
        assertTrue("workspaces directory should exist", workspacesDir.exists() && workspacesDir.isDirectory)
        
        val workspaceFolders = workspacesDir.listFiles()
        assertTrue("Should contain personal workspace folder", workspaceFolders != null && workspaceFolders.size == 1)
        val personalFolder = workspaceFolders!![0]
        assertFalse("Workspace folder name should not start with workspace-", personalFolder.name.startsWith("workspace-"))
        assertTrue("Workspace folder name should end with -personal", personalFolder.name.endsWith("-personal"))
        
        val manifest = personalFolder.resolve("workspace.jsonl")
        assertTrue("Workspace manifest should exist", manifest.exists())
    }

    @Test
    fun `old workspace- folders are migrated into workspaces directory on open`() = runTest {
        val root = temporaryFolder.newFolder()
        // Create an old workspace folder at root
        val oldFolder = root.resolve("workspace-12345-my-workspace").apply { mkdirs() }
        oldFolder.resolve("workspace.jsonl").writeText(
            """{"schemaVersion":1,"eventType":"workspaceCreated","workspaceId":"12345","name":"My Workspace","createdAtEpochMs":100}""" + "\n"
        )
        oldFolder.resolve("conversation-abc.jsonl").writeText(
            """{"schemaVersion":1,"eventType":"conversationCreated","conversationId":"abc","workspaceId":"12345","title":"Test Chat","createdAtEpochMs":120}""" + "\n"
        )

        val store = FileConversationStore(root)
        val activeWorkspace = store.switchActiveWorkspace("12345")
        assertEquals("My Workspace", activeWorkspace.name)
        
        // Verify old folder is gone
        assertFalse("Old folder should be deleted", oldFolder.exists())
        
        // Verify new folder exists in workspaces/
        val newFolder = root.resolve("workspaces/12345-my-workspace")
        assertTrue("New folder should exist", newFolder.exists())
        assertTrue("Workspace manifest should exist in new folder", newFolder.resolve("workspace.jsonl").exists())
        assertTrue("Conversation file should exist in new folder", newFolder.resolve("conversation-abc.jsonl").exists())
        
        // Verify data is readable
        val conversations = store.listConversations()
        assertEquals(1, conversations.size)
        assertEquals("abc", conversations[0].id)
    }


    @Test
    fun `partial and post write failures recover durable drafts once on same warmed store`() = runTest {
        for (postWrite in listOf(false, true)) {
            val root = temporaryFolder.newFolder()
            val storage = FaultStorage(FileChatHistoryStorage(root))
            val store = FileConversationStore(storage, ObjectMapper(), MemoryConversationIndex())
            val chat = store.getOrCreateActiveConversation()
            store.loadMessages(chat.id)
            storage.failBefore = if (postWrite) null else 2
            storage.failAfter = postWrite
            assertTrue(runCatching {
                store.appendMessages(chat.id, listOf(
                    ConversationMessageDraft("user", "durable first"),
                    ConversationMessageDraft("assistant", "not written")
                ))
            }.isFailure)
            assertEquals(listOf("durable first"), store.loadMessages(chat.id).map { it.content })
            assertEquals(1, store.searchSessionMessages("durable", 10).size)
            assertEquals(1, conversationFiles(root).single().readLines().count { it.contains("durable first") })
        }
    }

    @Test
    fun `creation upsert and selection failures recover durable observable chats`() = runTest {
        for (selectionFailure in listOf(false, true)) {
            val root = temporaryFolder.newFolder()
            val index = FailingConversationIndex()
            val store = FileConversationStore(FileChatHistoryStorage(root), ObjectMapper(), index)
            val first = store.getOrCreateActiveConversation()
            val flow = store.observeConversations(store.getOrCreateActiveWorkspace().id)
            assertEquals(listOf(first.id), flow.first().map { it.id })
            if (selectionFailure) index.failNextSelection = true else index.failNextUpsert = true
            assertTrue(runCatching { store.createConversation() }.isFailure)
            assertTrue(index.cacheState()!!.endsWith("\ndirty"))
            val durableIds = conversationFiles(root).map {
                ObjectMapper().readTree(it.readLines().first()).path("conversationId").asText()
            }.toSet()
            assertEquals(2, durableIds.size)
            assertEquals(durableIds, store.listConversations().map { it.id }.toSet())
            assertEquals(durableIds, flow.first().map { it.id }.toSet())
            assertFalse(index.cacheState()!!.endsWith("\ndirty"))
        }
    }

    @Test
    fun `target append delete and restore preserve metadata without reading unrelated log`() = runTest {
        val root = temporaryFolder.newFolder()
        val storage = FaultStorage(FileChatHistoryStorage(root))
        val store = FileConversationStore(storage, ObjectMapper(), MemoryConversationIndex())
        val target = store.getOrCreateActiveConversation()
        val unrelated = store.createConversation()
        store.appendMessage(unrelated.id, "user", "unrelated history")
        val file = conversationFiles(root).single { it.name.contains(unrelated.id) }
        val bytes = file.readBytes()
        val flow = store.observeConversations(store.getOrCreateActiveWorkspace().id)
        store.loadMessages(target.id)
        storage.poisoned = file.name
        val drafts = listOf(
            ConversationMessageDraft("user", "Target title", turnId = "turn"),
            ConversationMessageDraft("assistant", "thinking", kind = "reasoning", turnId = "turn",
                reasoningText = "full", reasoningSummary = "summary", encryptedReasoning = "encrypted",
                includeInPrompt = false, searchable = false),
            ConversationMessageDraft("assistant", "call", kind = "tool_call", turnId = "turn",
                toolCallId = "call-id", toolName = "search", toolArgumentsJson = """{"q":"needle"}"""),
            ConversationMessageDraft("tool", "result", kind = "tool_result", turnId = "turn",
                toolCallId = "call-id", toolName = "search", isError = true)
        )
        store.appendMessages(target.id, drafts)
        assertEquals(drafts, store.loadMessages(target.id).map { it.toDraft() })
        assertEquals("Target title", store.listConversations().single { it.id == target.id }.title)
        store.deleteConversation(target.id)
        assertFalse(flow.first().any { it.id == target.id })
        assertTrue(store.loadMessages(target.id).isEmpty())
        store.restoreConversation(target.id)
        assertTrue(flow.first().any { it.id == target.id })
        assertEquals(drafts, store.loadMessages(target.id).map { it.toDraft() })
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun `warmed siblings observe durable append create delete and post write failure`() = runTest {
        val root = temporaryFolder.newFolder()
        val first = FileConversationStore(root)
        val chat = first.getOrCreateActiveConversation()
        val storage = FaultStorage(FileChatHistoryStorage(root))
        val sibling = FileConversationStore(storage, ObjectMapper(), MemoryConversationIndex())
        sibling.loadMessages(chat.id)
        first.loadMessages(chat.id)
        sibling.appendMessage(chat.id, "user", "sibling append")
        assertEquals(listOf("sibling append"), first.loadMessages(chat.id).map { it.content })
        val created = sibling.createConversation()
        assertTrue(first.listConversations().any { it.id == created.id })
        sibling.deleteConversation(created.id)
        assertFalse(first.listConversations().any { it.id == created.id })
        storage.failAfter = true
        assertTrue(runCatching { sibling.appendMessage(chat.id, "assistant", "failed durable append") }.isFailure)
        assertEquals(listOf("sibling append", "failed durable append"), first.loadMessages(chat.id).map { it.content })
        assertEquals(first.loadMessages(chat.id), sibling.loadMessages(chat.id))
    }

    @Test
    fun `distinct root writes do not invalidate other warmed snapshot`() = runTest {
        val root = temporaryFolder.newFolder()
        val storage = FaultStorage(FileChatHistoryStorage(root))
        val first = FileConversationStore(storage, ObjectMapper(), MemoryConversationIndex())
        val chat = first.getOrCreateActiveConversation()
        first.appendMessage(chat.id, "user", "independent")
        first.loadMessages(chat.id)
        storage.poisoned = conversationFiles(root).single().name
        val other = FileConversationStore(temporaryFolder.newFolder())
        other.appendMessage(other.getOrCreateActiveConversation().id, "user", "other root")
        assertEquals("independent", first.loadMessages(chat.id).single().content)
    }

    @Test
    fun `full replacement empties previously observed absent workspace`() = runTest {
        val index = MemoryConversationIndex()
        index.replaceFrom(ConversationIndexSnapshot(
            listOf(IndexedWorkspace("old", "Old", "old", 1, 1, false)),
            listOf(IndexedConversation("chat", "old", listOf("chat.jsonl"), null, 1, 1, false, emptyList()))
        ))
        val flow = index.observeConversations("old")
        assertEquals(listOf("chat"), flow.first().map { it.id })
        index.replaceFrom(ConversationIndexSnapshot(emptyList(), emptyList()))
        assertTrue(flow.first().isEmpty())
    }

    private fun StoredChatMessage.toDraft() = ConversationMessageDraft(
        role, content, kind, turnId, toolCallId, toolName, toolArgumentsJson, isError,
        reasoningText, reasoningSummary, encryptedReasoning, includeInPrompt, searchable
    )

    private class FaultStorage(private val delegate: ChatHistoryStorage) : ChatHistoryStorage by delegate {
        var failBefore: Int? = null
        var failAfter = false
        var poisoned: String? = null
        override fun readText(path: List<String>): StorageReadResult {
            check(poisoned == null || path.lastOrNull() != poisoned) { "Unrelated log read" }
            return delegate.readText(path)
        }
        override fun appendLine(path: List<String>, line: String, mimeType: String) {
            failBefore?.let {
                if (it == 1) {
                    failBefore = null
                    error("Injected pre-write failure")
                }
                failBefore = it - 1
            }
            delegate.appendLine(path, line, mimeType)
            if (failAfter) {
                failAfter = false
                error("Injected post-write failure")
            }
        }
    }

    @Test
    fun `failed full rebuild can recover on same warmed store`() = runTest {
        val root = temporaryFolder.newFolder()
        val index = FailingConversationIndex()
        val store = FileConversationStore(FileChatHistoryStorage(root), ObjectMapper(), index)
        val chat = store.getOrCreateActiveConversation()
        store.appendMessage(chat.id, "user", "before rebuild")
        val file = conversationFiles(root).single()
        val updatedLines = file.readLines().map { line ->
            val event = ObjectMapper().readTree(line) as com.fasterxml.jackson.databind.node.ObjectNode
            if (event.has("title")) event.put("title", "External title")
            event.toString()
        }
        file.writeText(updatedLines.joinToString("\n", postfix = "\n"))
        index.failNextReplace = true
        assertTrue(runCatching { store.rebuildIndexFromFiles() }.isFailure)
        assertEquals("External title", store.listConversations().single().title)
        assertEquals("External title", store.observeConversations(store.getOrCreateActiveWorkspace().id).first().single().title)
        assertEquals("before rebuild", store.loadMessages(chat.id).single().content)
    }

    private fun conversationFiles(root: File): List<File> = root.walkTopDown()
        .filter { it.isFile && it.name.startsWith("conversation-") && it.extension == "jsonl" }
        .toList()

    private class FailingConversationIndex(
        private val delegate: ConversationIndex = MemoryConversationIndex()
    ) : ConversationIndex by delegate {
        var failNextUpsert = false
        var failNextReplace = false
        var failNextSelection = false

        override suspend fun upsertConversation(conversation: IndexedConversation) {
            if (failNextUpsert) {
                failNextUpsert = false
                error("Injected upsert failure")
            }
            delegate.upsertConversation(conversation)
        }

        override suspend fun replaceFrom(snapshot: ConversationIndexSnapshot) {
            if (failNextReplace) {
                failNextReplace = false
                error("Injected replacement failure")
            }
            delegate.replaceFrom(snapshot)
        }

        override suspend fun setActiveConversationId(workspaceId: String, id: String?) {
            if (failNextSelection) {
                failNextSelection = false
                error("Injected selection failure")
            }
            delegate.setActiveConversationId(workspaceId, id)
        }
    }
}
