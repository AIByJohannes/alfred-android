package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.core.skills.SkillClient
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test

class ChatRepositorySkillTest {
    @Test
    fun `repository exposes configured skill client for engine wiring`() {
        val skillClient = mockk<SkillClient>()
        val repository = ChatRepository(
            apiKeyStore = mockk(),
            skillClient = skillClient
        )

        assertSame(skillClient, repository.resolveSkillClient())
    }
}
