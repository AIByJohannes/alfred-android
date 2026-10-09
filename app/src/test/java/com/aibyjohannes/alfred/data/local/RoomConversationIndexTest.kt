package com.aibyjohannes.alfred.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomConversationIndexTest {
    private lateinit var database: ConversationIndexDatabase
    private lateinit var index: RoomConversationIndex

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ConversationIndexDatabase::class.java
        ).allowMainThreadQueries().build()
        index = RoomConversationIndex(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert replaces complete target messages without duplicates or changing unrelated rows`() = runTest {
        val target = conversation("target", listOf(message("old", "target", 1)))
        val unrelated = conversation("unrelated", listOf(message("unrelated-event", "unrelated", 1)))
        index.replaceFrom(ConversationIndexSnapshot(
            listOf(IndexedWorkspace("workspace", "Personal", "personal", 1, 1, false)),
            listOf(target, unrelated)
        ))
        val unrelatedBefore = rows("unrelated")
        val replacement = listOf(message("new-one", "target", 2), message("new-two", "target", 3))
        index.upsertConversation(target.copy(messages = replacement, updatedAtEpochMs = 3))
        index.upsertConversation(target.copy(messages = replacement, updatedAtEpochMs = 3))

        assertEquals(replacement.map { it.toEntity() }, rows("target"))
        assertEquals(unrelatedBefore, rows("unrelated"))

        index.upsertConversation(target.copy(messages = emptyList()))
        assertTrue(rows("target").isEmpty())
        assertEquals(unrelatedBefore, rows("unrelated"))
    }

    @Test
    fun `delete and restore change visible flow membership while retaining messages`() = runTest {
        val target = conversation("target", listOf(message("event", "target", 1)))
        index.replaceFrom(ConversationIndexSnapshot(
            listOf(IndexedWorkspace("workspace", "Personal", "personal", 1, 1, false)), listOf(target)
        ))
        val flow = index.observeConversations("workspace")
        assertEquals(listOf("target"), flow.first().map { it.id })
        val before = rows("target")
        index.upsertConversation(target.copy(deleted = true))
        assertFalse(flow.first().any { it.id == "target" })
        assertEquals(before, rows("target"))
        index.upsertConversation(target.copy(deleted = false))
        assertEquals(listOf("target"), flow.first().map { it.id })
        assertEquals(before, rows("target"))
    }

    private fun conversation(id: String, messages: List<IndexedMessage>) = IndexedConversation(
        id, "workspace", listOf("workspaces", "personal", "$id.jsonl"), "Title $id", 1, 2, false, messages
    )

    private fun message(event: String, conversation: String, ordinal: Long) = IndexedMessage(
        event, ordinal + 10, conversation, ordinal, "tool", "Content $event", "tool_result", "turn",
        "call", "search", "{\"q\":\"needle\"}", true, "reasoning", "summary", "encrypted", false, false, 123
    )

    private fun IndexedMessage.toEntity() = MessageCacheEntity(
        eventId, id, conversationId, ordinal, role, content, kind, turnId, toolCallId, toolName,
        toolArgumentsJson, isError, reasoningText, reasoningSummary, encryptedReasoning,
        includeInPrompt, searchable, createdAtEpochMs
    )

    private fun rows(conversationId: String): List<MessageCacheEntity> {
        database.openHelper.readableDatabase.query(
            "SELECT * FROM messages WHERE conversationId = ? ORDER BY ordinal", arrayOf(conversationId)
        ).use { cursor ->
            val result = mutableListOf<MessageCacheEntity>()
            fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            fun flag(column: String) = number(column) != 0L
            while (cursor.moveToNext()) {
                result += MessageCacheEntity(
                    text("eventId"), number("messageId"), text("conversationId"), number("ordinal"),
                    text("role"), text("content"), text("kind"), text("turnId"), text("toolCallId"),
                    text("toolName"), text("toolArgumentsJson"), flag("isError"), text("reasoningText"),
                    text("reasoningSummary"), text("encryptedReasoning"), flag("includeInPrompt"),
                    flag("searchable"), number("createdAtEpochMs")
                )
            }
            return result
        }
    }
}
