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
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal interface LocalGemmaEngineHandle : AutoCloseable {
    fun initialize()
    fun isInitialized(): Boolean
}

internal fun <T : LocalGemmaEngineHandle> initializeWithCpuFallback(
    createGpuEngine: () -> T,
    createCpuEngine: () -> T
): T {
    val gpuEngine = createGpuEngine()
    return try {
        gpuEngine.initialize()
        gpuEngine
    } catch (gpuError: Throwable) {
        closeAfterFailedInitialization(gpuEngine, gpuError)
        val cpuEngine = createCpuEngine()
        try {
            cpuEngine.initialize()
            cpuEngine
        } catch (cpuError: Throwable) {
            closeAfterFailedInitialization(cpuEngine, cpuError)
            cpuError.addSuppressed(gpuError)
            throw cpuError
        }
    }
}

private fun closeAfterFailedInitialization(
    engine: LocalGemmaEngineHandle,
    initializationError: Throwable
) {
    if (!engine.isInitialized()) return
    runCatching { engine.close() }
        .exceptionOrNull()
        ?.let(initializationError::addSuppressed)
}

private class LiteRtEngineHandle(val engine: Engine) : LocalGemmaEngineHandle {
    override fun initialize() = engine.initialize()
    override fun isInitialized(): Boolean = engine.isInitialized()
    override fun close() = engine.close()
}

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
                    conversation.compatibleMessageFlow(userMessage).collect { message ->
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
        return initializeWithCpuFallback(
            createGpuEngine = { LiteRtEngineHandle(Engine(engineConfig(Backend.GPU()))) },
            createCpuEngine = { LiteRtEngineHandle(Engine(engineConfig(Backend.CPU()))) }
        ).engine
    }

    private fun engineConfig(backend: Backend): EngineConfig = EngineConfig(
        modelPath = modelPath,
        backend = backend,
        cacheDir = cacheDirectory.absolutePath
    )

    private fun com.google.ai.edge.litertlm.Conversation.compatibleMessageFlow(
        userMessage: String
    ): Flow<Message> = callbackFlow {
        val terminated = AtomicBoolean(false)
        sendMessageAsync(
            userMessage,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    trySend(message)
                }

                override fun onDone() {
                    terminated.set(true)
                    close()
                }

                override fun onError(throwable: Throwable) {
                    terminated.set(true)
                    close(throwable)
                }
            }
        )
        awaitClose {
            // LiteRT-LM 0.14.0's Flow wrapper is binary-incompatible with coroutines 1.10.x.
            if (!terminated.get() && isAlive) {
                runCatching { cancelProcess() }
            }
        }
    }

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
