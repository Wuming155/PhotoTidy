package com.phototidy.core.media

import com.phototidy.core.StringProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOpCoordinatorTest {

    private val strings = object : StringProvider {
        override fun get(resId: Int, vararg args: Any) = "res:$resId"
    }

    @Test
    fun `success path invokes onSuccess`() = runTest {
        val ops = MediaOpCoordinator(strings)
        var success = false
        ops.run(successMessage = "done", onSuccess = { success = true }) { MediaOpResult.Success }
        assertTrue(success)
    }

    @Test
    fun `failure path invokes onFailure with message`() = runTest {
        val ops = MediaOpCoordinator(strings)
        var failureMsg: String? = null
        ops.run(
            successMessage = "done",
            onFailure = { failureMsg = it },
        ) { MediaOpResult.Failed("boom") }
        assertEquals("boom", failureMsg)
    }
}
