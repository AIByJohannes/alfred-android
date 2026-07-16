package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.core.engine.ChatEngine
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File

class LocalGemmaChatEngine(
    private val modelPath: String,
    private val cacheDirectory: File,
    private val systemPrompt: String
) : ChatEngine {

    override suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Result<ChatTurnResult> = runCatching {
        withContext(Dispatchers.IO) {
            createInitializedEngine().use { engine ->
                engine.createConversation(conversationConfig(conversationHistory)).use { conversation ->
                    val response = conversation.sendMessage(userMessage)
                    ChatTurnResult(content = response.toString(), toolCalls = emptyList())
                }
            }
        }
    }

    override fun streamMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Flow<ChatStreamEvent> = channelFlow {
        withContext(Dispatchers.IO) {
            createInitializedEngine().use { engine ->
                engine.createConversation(conversationConfig(conversationHistory)).use { conversation ->
                    val completeText = StringBuilder()
                    send(ChatStreamEvent.PassStarted(passIndex = 0))
                    conversation.sendMessageAsync(userMessage).collect { message ->
                        val chunk = message.toString()
                        if (chunk.isNotEmpty()) {
                            completeText.append(chunk)
                            send(ChatStreamEvent.TextDelta(passIndex = 0, textChunk = chunk))
                        }
                    }
                    val result = ChatTurnResult(completeText.toString(), emptyList())
                    send(ChatStreamEvent.PassCompleted(passIndex = 0))
                    send(ChatStreamEvent.Completed(result))
                }
            }
        }
    }

    private suspend fun createInitializedEngine(): Engine {
        val gpuEngine = Engine(engineConfig(Backend.GPU()))
        return try {
            gpuEngine.initialize()
            gpuEngine
        } catch (gpuError: Throwable) {
            gpuEngine.close()
            val cpuEngine = Engine(engineConfig(Backend.CPU()))
            try {
                cpuEngine.initialize()
                cpuEngine
            } catch (cpuError: Throwable) {
                cpuEngine.close()
                cpuError.addSuppressed(gpuError)
                throw cpuError
            }
        }
    }

    private fun engineConfig(backend: Backend): EngineConfig = EngineConfig(
        modelPath = modelPath,
        backend = backend,
        cacheDir = cacheDirectory.absolutePath
    )

    private fun conversationConfig(history: List<CoreChatMessage>): ConversationConfig {
        val initialMessages = history
            .asSequence()
            .filter { it.includeInPrompt && it.kind == CoreChatMessageKind.MESSAGE }
            .mapNotNull { message ->
                when (message.role) {
                    "user" -> Message.user(message.content)
                    "assistant" -> Message.model(message.content)
                    else -> null
                }
            }
            .toList()
        return ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = initialMessages
        )
    }
}
