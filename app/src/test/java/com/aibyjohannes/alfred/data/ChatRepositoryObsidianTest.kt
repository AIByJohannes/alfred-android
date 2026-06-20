package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.core.search.ObsidianClient
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatRepositoryObsidianTest {

    @Test
    fun `resolves vault client from current connection state for every chat turn`() {
        val connectedClient = mockk<ObsidianClient>()
        var currentClient: ObsidianClient? = null
        val repository = ChatRepository(
            apiKeyStore = mockk(relaxed = true),
            obsidianClientProvider = { currentClient }
        )

        assertNull(repository.resolveObsidianClient())

        currentClient = connectedClient
        assertSame(connectedClient, repository.resolveObsidianClient())

        currentClient = null
        assertNull(repository.resolveObsidianClient())
    }

    @Test
    fun `uses fixed vault client when no dynamic provider is configured`() {
        val connectedClient = mockk<ObsidianClient>()
        val repository = ChatRepository(
            apiKeyStore = mockk(relaxed = true),
            obsidianClient = connectedClient
        )

        assertSame(connectedClient, repository.resolveObsidianClient())
    }
}
