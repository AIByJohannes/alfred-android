package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.data.api.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.LinkedHashMap

class FileConversationStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `create append load list switch and delete conversations`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())

        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "Remember that I prefer Kotlin.")
        store.appendMessage(first.id, ChatMessage.ROLE_ASSISTANT, "Understood.")

        val second = store.createConversation()
        assertNotEquals(first.id, second.id)
        store.appendMessage(second.id, ChatMessage.ROLE_USER, "This is about Android.")

        val conversations = store.listConversations()
        assertEquals(2, conversations.size)
        assertEquals(second.id, conversations.first().id)

        val firstMessages = store.loadMessages(first.id)
        assertEquals(2, firstMessages.size)
        assertEquals("Remember that I prefer Kotlin.", firstMessages.first().content)

        val selected = store.switchActiveConversation(first.id)
        assertEquals(first.id, selected.id)

        store.deleteConversation(first.id)
        assertEquals(1, store.listConversations().size)
        assertTrue(store.loadMessages(first.id).isEmpty())
    }

    @Test
    fun `searchSessionMessages returns scored limited matches`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())
        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "Kotlin coroutines are useful for Android work.")
        store.appendMessage(first.id, ChatMessage.ROLE_ASSISTANT, "Kotlin can keep async code tidy.")

        val second = store.createConversation()
        store.appendMessage(second.id, ChatMessage.ROLE_USER, "Groceries and calendar reminders.")

        val hits = store.searchSessionMessages("Kotlin Android", limit = 1)

        assertEquals(1, hits.size)
        assertEquals(first.id, hits.single().conversationId)
        assertTrue(hits.single().snippet.contains("Kotlin"))
    }

    @Test
    fun `stores chat history in stable workspace jsonl files`() = runTest {
        val root = temporaryFolder.newFolder("Alfred")
        val store = FileConversationStore(root)

        val personal = store.getOrCreateActiveConversation()
        store.appendMessage(personal.id, ChatMessage.ROLE_USER, "Hello from personal.")

        val work = store.createWorkspace("Work Stuff")
        val workConversation = store.getOrCreateActiveConversation()
        store.appendMessage(workConversation.id, ChatMessage.ROLE_USER, "Hello from work.")
        store.renameWorkspace(work.id, "Renamed Work")

        assertTrue(root.resolve("metadata.json").exists())
        assertTrue(root.resolve("workspace-1-personal/conversation-${personal.id}.jsonl").exists())
        assertTrue(root.resolve("workspace-${work.id}-work-stuff/conversation-${workConversation.id}.jsonl").exists())
        assertFalse(root.resolve("workspace-${work.id}-renamed-work").exists())
    }

    @Test
    fun `partial trailing jsonl record does not hide intact messages`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        val conversation = store.getOrCreateActiveConversation()
        store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Keep the durable message")
        val jsonl = root.resolve("workspace-1-personal/conversation-${conversation.id}.jsonl")

        jsonl.appendText("{\"id\":")

        val messages = store.loadMessages(conversation.id)
        assertEquals(listOf("Keep the durable message"), messages.map { it.content })
    }

    @Test
    fun `active conversation is restored per workspace`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())

        val personalFirst = store.getOrCreateActiveConversation()
        store.appendMessage(personalFirst.id, ChatMessage.ROLE_USER, "First personal chat.")
        val personalSecond = store.createConversation()
        store.appendMessage(personalSecond.id, ChatMessage.ROLE_USER, "Second personal chat.")
        store.switchActiveConversation(personalFirst.id)

        val work = store.createWorkspace("Work")
        val workConversation = store.getOrCreateActiveConversation()
        store.appendMessage(workConversation.id, ChatMessage.ROLE_USER, "Work chat.")

        store.switchActiveWorkspace(1L)
        assertEquals(personalFirst.id, store.getOrCreateActiveConversation().id)

        store.switchActiveWorkspace(work.id)
        assertEquals(workConversation.id, store.getOrCreateActiveConversation().id)
    }

    @Test
    fun `created workspace chat survives store recreation`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)
        store.getOrCreateActiveConversation()
        val workspace = store.createWorkspace("Work")
        val conversation = store.getOrCreateActiveConversation()
        store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Persist outside Personal")

        val reopened = FileConversationStore(root)

        assertEquals(workspace.id, reopened.getOrCreateActiveWorkspace().id)
        assertEquals(conversation.id, reopened.getOrCreateActiveConversation().id)
        assertEquals("Persist outside Personal", reopened.loadMessages(conversation.id).single().content)
    }

    @Test
    fun `switchActiveConversation rejects conversations from other workspaces`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())

        val personal = store.getOrCreateActiveConversation()
        store.appendMessage(personal.id, ChatMessage.ROLE_USER, "Personal chat.")

        store.createWorkspace("Work")
        val work = store.getOrCreateActiveConversation()

        val result = runCatching {
            store.switchActiveConversation(personal.id)
        }

        assertTrue(result.isFailure)
        assertEquals(work.id, store.getOrCreateActiveConversation().id)
    }

    @Test
    fun `deleteConversation removes jsonl file`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)

        val conversation = store.getOrCreateActiveConversation()
        store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Temporary chat.")
        val jsonl = root.resolve("workspace-1-personal/conversation-${conversation.id}.jsonl")
        assertTrue(jsonl.exists())

        store.deleteConversation(conversation.id)

        assertFalse(jsonl.exists())
        assertTrue(store.loadMessages(conversation.id).isEmpty())
    }

    @Test
    fun `structured trace metadata round trips and is excluded from search`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())
        val conversation = store.getOrCreateActiveConversation()

        store.appendMessages(
            conversation.id,
            listOf(
                ConversationMessageDraft(
                    role = ChatMessage.ROLE_USER,
                    content = "Find Kotlin release notes",
                    searchable = true
                ),
                ConversationMessageDraft(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "Searching",
                    kind = ChatMessage.KIND_TOOL_CALL,
                    turnId = "turn-1",
                    toolCallId = "call-1",
                    toolName = "WebSearchTool",
                    toolArgumentsJson = """{"query":"Kotlin release notes"}""",
                    searchable = false
                ),
                ConversationMessageDraft(
                    role = ChatMessage.ROLE_TOOL,
                    content = "Hidden trace result should not be searchable",
                    kind = ChatMessage.KIND_TOOL_RESULT,
                    turnId = "turn-1",
                    toolCallId = "call-1",
                    toolName = "WebSearchTool",
                    searchable = false
                ),
                ConversationMessageDraft(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "Kotlin release notes are available.",
                    turnId = "turn-1",
                    searchable = true
                )
            )
        )

        val messages = store.loadMessages(conversation.id)
        assertEquals(4, messages.size)
        assertEquals(ChatMessage.KIND_TOOL_CALL, messages[1].kind)
        assertEquals("call-1", messages[1].toolCallId)
        assertEquals("""{"query":"Kotlin release notes"}""", messages[1].toolArgumentsJson)
        assertFalse(messages[1].searchable)

        val traceHits = store.searchSessionMessages("Hidden trace result", limit = 10)
        assertTrue(traceHits.isEmpty())

        val visibleHits = store.searchSessionMessages("Kotlin release", limit = 10)
        assertTrue(visibleHits.isNotEmpty())
    }

    @Test
    fun `transient metadata read failure never overwrites the index or chat files`() = runTest {
        val storage = FaultInjectingStorage()
        val store = FileConversationStore(storage)
        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "First chat")
        val second = store.createConversation()
        store.appendMessage(second.id, ChatMessage.ROLE_USER, "Second chat")
        val filesBeforeFailure = storage.files.toMap()

        storage.failNextMetadataRead = true
        val failure = runCatching { store.getOrCreateActiveWorkspace() }

        assertTrue("A transient read must fail closed", failure.isFailure)
        assertEquals(filesBeforeFailure, storage.files)
        assertEquals(2, FileConversationStore(storage).listConversations().size)
    }

    @Test
    fun `existing orphan file is never truncated when metadata is missing`() = runTest {
        val storage = FaultInjectingStorage()
        storage.files["workspace-1-personal/conversation-1.jsonl"] =
            """{"id":1,"conversationId":1,"role":"user","content":"Keep me","createdAtEpochMs":123}""" + "\n"

        val result = runCatching { FileConversationStore(storage).getOrCreateActiveConversation() }

        assertTrue("The existing chat should be recovered", result.isSuccess)
        assertEquals("Keep me", FileConversationStore(storage).loadMessages(1L).single().content)
        assertTrue(storage.files.getValue("workspace-1-personal/conversation-1.jsonl").isNotBlank())
    }

    @Test
    fun `surviving orphan is recovered from an already reset index`() = runTest {
        val original = FaultInjectingStorage()
        val originalStore = FileConversationStore(original)
        val first = originalStore.getOrCreateActiveConversation()
        originalStore.appendMessage(first.id, ChatMessage.ROLE_USER, "First chat")
        val second = originalStore.createConversation()
        originalStore.appendMessage(second.id, ChatMessage.ROLE_USER, "Second chat survives")
        val survivingSecondChat = original.files.getValue("workspace-1-personal/conversation-2.jsonl")

        val reset = FaultInjectingStorage()
        FileConversationStore(reset).getOrCreateActiveConversation()
        reset.files["workspace-1-personal/conversation-2.jsonl"] = survivingSecondChat

        val recoveredStore = FileConversationStore(reset)

        assertEquals(2, recoveredStore.listConversations().size)
        assertEquals("Second chat survives", recoveredStore.loadMessages(2L).single().content)
        assertTrue(reset.files.getValue("workspace-1-personal/conversation-2.jsonl").isNotBlank())
    }

    @Test
    fun `blank metadata is treated as corruption and is not overwritten`() = runTest {
        val storage = FaultInjectingStorage().apply {
            files["metadata.json"] = ""
        }

        val failure = runCatching { FileConversationStore(storage).listConversations() }

        assertTrue(failure.isFailure)
        assertEquals("", storage.files.getValue("metadata.json"))
    }

    @Test
    fun `failed chat file deletion keeps metadata indexed`() = runTest {
        val storage = FaultInjectingStorage()
        val store = FileConversationStore(storage)
        val conversation = store.getOrCreateActiveConversation()
        store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Do not lose me")
        val metadataBeforeDelete = storage.files.getValue("metadata.json")
        storage.failDelete = true

        val failure = runCatching { store.deleteConversation(conversation.id) }

        assertTrue(failure.isFailure)
        assertEquals(metadataBeforeDelete, storage.files.getValue("metadata.json"))
        assertEquals(1, FileConversationStore(storage).listConversations().size)
    }

    @Test
    fun `failed metadata write during new chat preserves the existing index`() = runTest {
        val storage = FaultInjectingStorage()
        val store = FileConversationStore(storage)
        val existing = store.getOrCreateActiveConversation()
        store.appendMessage(existing.id, ChatMessage.ROLE_USER, "Keep this chat")
        val metadataBeforeCreate = storage.files.getValue("metadata.json")
        storage.failNextMetadataWriteAfterTruncate = true

        val failure = runCatching { store.createConversation() }

        assertTrue(failure.isFailure)
        assertEquals(metadataBeforeCreate, storage.files.getValue("metadata.json"))
        assertEquals(listOf(existing.id), FileConversationStore(storage).listConversations().map { it.id })
        assertEquals("Keep this chat", FileConversationStore(storage).loadMessages(existing.id).single().content)
    }

    @Test
    fun `valid metadata backup repairs a truncated primary index`() = runTest {
        val storage = FaultInjectingStorage()
        val store = FileConversationStore(storage)
        val existing = store.getOrCreateActiveConversation()
        store.appendMessage(existing.id, ChatMessage.ROLE_USER, "Recover from backup")
        val validMetadata = storage.files.getValue("metadata.json")
        storage.files["metadata.backup.json"] = validMetadata
        storage.files["metadata.json"] = ""

        val recovered = FileConversationStore(storage)

        assertEquals(listOf(existing.id), recovered.listConversations().map { it.id })
        assertEquals("Recover from backup", recovered.loadMessages(existing.id).single().content)
        assertTrue(storage.files.getValue("metadata.json").isNotBlank())
    }

    @Test
    fun `orphan conversation is recovered when multiple conversations remain indexed`() = runTest {
        val storage = FaultInjectingStorage()
        val store = FileConversationStore(storage)
        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "First")
        val second = store.createConversation()
        store.appendMessage(second.id, ChatMessage.ROLE_USER, "Second")
        val metadataWithTwoChats = storage.files.getValue("metadata.json")
        val third = store.createConversation()
        store.appendMessage(third.id, ChatMessage.ROLE_USER, "Recover me")

        storage.files["metadata.json"] = metadataWithTwoChats

        val recovered = FileConversationStore(storage)
        assertEquals(3, recovered.listConversations().size)
        assertEquals("Recover me", recovered.loadMessages(third.id).single().content)
    }

    private class FaultInjectingStorage : ChatHistoryStorage {
        val files = LinkedHashMap<String, String>()
        var failNextMetadataRead = false
        var failNextMetadataWriteAfterTruncate = false
        var failDelete = false

        override fun ensureReady() = Unit

        override fun ensureDirectory(path: List<String>) = Unit

        override fun readText(path: List<String>): StorageReadResult {
            val key = path.joinToString("/")
            if (key == "metadata.json" && failNextMetadataRead) {
                failNextMetadataRead = false
                throw IllegalStateException("Transient provider failure")
            }
            return files[key]?.let(StorageReadResult::Found) ?: StorageReadResult.Missing
        }

        override fun listChildren(path: List<String>): List<StorageEntry> {
            val prefix = path.joinToString("/").let { if (it.isBlank()) "" else "$it/" }
            return files.keys
                .asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .filter { it.isNotBlank() }
                .map { it.substringBefore('/') }
                .distinct()
                .map { name ->
                    val key = "$prefix$name"
                    val isDirectory = files.keys.any { it.startsWith("$key/") }
                    StorageEntry(name, isDirectory, if (isDirectory) 0L else files[key]?.length?.toLong() ?: 0L)
                }
                .toList()
        }

        override fun createFileExclusive(path: List<String>, mimeType: String) {
            val key = path.joinToString("/")
            check(!files.containsKey(key)) { "File already exists: $key" }
            files[key] = ""
        }

        override fun writeText(path: List<String>, text: String, mimeType: String) {
            val key = path.joinToString("/")
            if (key == "metadata.json" && failNextMetadataWriteAfterTruncate) {
                failNextMetadataWriteAfterTruncate = false
                files[key] = ""
                throw IllegalStateException("Metadata write failed after truncation")
            }
            files[key] = text
        }

        override fun appendLine(path: List<String>, line: String, mimeType: String) {
            files.merge(path.joinToString("/"), "$line\n", String::plus)
        }

        override fun delete(path: List<String>) {
            if (failDelete) {
                failDelete = false
                throw IllegalStateException("Delete failed")
            }
            val key = path.joinToString("/")
            files.keys.removeAll { it == key || it.startsWith("$key/") }
        }
    }
}
