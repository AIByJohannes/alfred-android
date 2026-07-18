package com.aibyjohannes.alfred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingNavigationTest {

    @Test
    fun `completion removes onboarding before opening home`() {
        val navOptions = onboardingCompletionNavOptions()

        assertEquals(R.id.nav_onboarding, navOptions.popUpToId)
        assertTrue(navOptions.isPopUpToInclusive())
    }
}
