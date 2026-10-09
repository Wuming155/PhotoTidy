package com.phototidy.core.media

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOpCoordinatorTest {

    @Test
    fun `success path invokes onSuccess`() = runTest {
        val ops = MediaOpCoordinator()
        var success = false
        ops.run(successMessage = "done", onSuccess = { success = true }) { MediaOpResult.Success }
        assertTrue(success)
    }

    @Test
    fun `failure path invokes onFailure with message`() = runTest {
        val ops = MediaOpCoordinator()
        var failureMsg: String? = null
        ops.run(
            successMessage = "done",
            onFailure = { failureMsg = it },
        ) { MediaOpResult.Failed("boom") }
        assertEquals("boom", failureMsg)
    }
}
