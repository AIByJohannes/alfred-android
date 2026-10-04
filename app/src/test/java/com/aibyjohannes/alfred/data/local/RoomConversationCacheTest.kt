package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RoomConversationCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `a new database connection reuses root-bound summaries and searches unopened messages`() = runTest {
        // Android's ContextImpl builds Unix paths; use an actual host path for disk SQLite on Windows.
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getDatabasePath(name: String): File = File(temporaryFolder.root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        val databaseName = "cache-${java.util.UUID.randomUUID()}.db"
        context.getDatabasePath(databaseName).parentFile!!.mkdirs()
        val storage = FileChatHistoryStorage(temporaryFolder.newFolder())
        val firstDatabase = Room.databaseBuilder(context, ConversationIndexDatabase::class.java, databaseName).build()
        val original = FileConversationStore(storage, ObjectMapper(), RoomConversationIndex(firstDatabase))
        val chat = original.createConversation()
        original.appendMessage(chat.id, ChatMessage.ROLE_USER, "astronomy planets")
        firstDatabase.close()

        val secondDatabase = Room.databaseBuilder(context, ConversationIndexDatabase::class.java, databaseName).build()
        try {
            val counts = ReadCountingStorage(storage)
            val index = RoomConversationIndex(secondDatabase)
            val reopened = FileConversationStore(counts, ObjectMapper(), index)
            assertEquals(chat.id, reopened.listConversations().single().id)
            assertTrue(counts.reads.isEmpty())
            assertFalse(index.readSnapshot().conversations.single().messagesLoaded)
            assertEquals(chat.id, reopened.searchSessionMessages("planets", 10).single().conversationId)
            assertTrue(counts.reads.isEmpty())
            assertEquals("astronomy planets", reopened.loadMessages(chat.id).single().content)
            assertEquals(1, counts.reads.size)
            reopened.loadMessages(chat.id)
            assertEquals(1, counts.reads.size)
        } finally { secondDatabase.close(); context.deleteDatabase(databaseName) }
    }

    @Test
    fun `targeted Room updates retain other conversations and publish delete restore changes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ConversationIndexDatabase::class.java).build()
        try {
            val index = RoomConversationIndex(database)
            val store = FileConversationStore(FileChatHistoryStorage(temporaryFolder.newFolder()), ObjectMapper(), index)
            val workspace = store.getOrCreateActiveWorkspace()
            val first = store.createConversation()
            store.appendMessage(first.id, ChatMessage.ROLE_USER, "first topic")
            val second = store.createConversation()
            store.appendMessage(second.id, ChatMessage.ROLE_USER, "second topic")
            store.appendMessage(first.id, ChatMessage.ROLE_ASSISTANT, "answer")
            assertEquals(3, index.searchableMessages().size)

            store.deleteConversation(first.id)
            assertEquals(listOf(second.id), index.observeConversations(workspace.id).first().map { it.id })
            assertEquals("second topic", index.searchableMessages().single().content)
            store.restoreConversation(first.id)
            assertEquals(setOf(first.id, second.id), index.observeConversations(workspace.id).first().map { it.id }.toSet())
            assertEquals(3, index.searchableMessages().size)
            store.renameWorkspace(workspace.id, "Renamed")
            assertEquals("Renamed", index.observeWorkspaces().first().single().name)
        } finally { database.close() }
    }

    @Test
    fun `an interrupted save is repaired from files using the persisted dirty marker`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ConversationIndexDatabase::class.java).build()
        try {
            val index = RoomConversationIndex(database)
            val storage = FileChatHistoryStorage(temporaryFolder.newFolder())
            val original = FileConversationStore(storage, ObjectMapper(), index)
            val chat = original.createConversation()
            // Model process death between a verified file write and its Room transaction.
            val stamp = index.cacheState()!!
            index.setCacheState(stamp.substringBeforeLast('\n') + "\ndirty")
            val workspacePath = storage.listChildren(listOf("workspaces")).single().name
            storage.appendLine(listOf("workspaces", workspacePath, "conversation-${chat.id}.jsonl"),
                """{"eventType":"messageAppended","eventId":"durable","id":1,"conversationId":"${chat.id}","role":"user","content":"survived","createdAtEpochMs":1}""")

            val reopened = FileConversationStore(storage, ObjectMapper(), index)
            assertEquals("survived", reopened.loadMessages(chat.id).single().content)
            assertFalse(index.cacheState()!!.endsWith("\ndirty"))
            assertEquals("survived", index.searchableMessages().single().content)
        } finally { database.close() }
    }

    private class ReadCountingStorage(private val delegate: ChatHistoryStorage) : ChatHistoryStorage by delegate {
        val reads = mutableListOf<List<String>>()
        override fun readText(path: List<String>): StorageReadResult { reads += path; return delegate.readText(path) }
    }
}
