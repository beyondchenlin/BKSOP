package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.cap.phone.TypingPacing
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** Real typing/verification loops, with a fake field standing in for the native Android input. */
class CheckedTextDispatchTest {
    private class Revoked : IllegalStateException("The local execution permit was revoked")

    @Test fun verificationDoesNotWriteAgainAfterPermissionChangesDuringSettle() = runTest {
        val denied = Revoked()
        var allowed = true
        val writes = mutableListOf<String>()
        val field = object : TextField {
            override fun read() = "unchanged"
            override fun write(text: String): Boolean { writes += text; return true }
            override suspend fun writeChecked(text: String): Boolean {
                if (!allowed) throw denied
                return write(text)
            }
        }
        try {
            typeAndVerify(field, "new text", attempts = 3, settle = { allowed = false })
            fail("A revoked permit must end verification before another native write")
        } catch (failure: Revoked) {
            assertSame(denied, failure)
        }
        assertEquals(listOf("new text"), writes)
    }

    @Test fun pacedTypingRechecksAfterEveryDelay() = runTest {
        val denied = Revoked()
        var allowed = true
        var delays = 0
        var actual = ""
        val writes = mutableListOf<String>()
        val field = object : TextField {
            override fun read() = actual
            override fun write(text: String): Boolean { writes += text; actual = text; return true }
            override suspend fun writeChecked(text: String): Boolean {
                if (!allowed) throw denied
                return write(text)
            }
        }
        try {
            TypingPacing.human(Random(7)).leadIn(field, "abcdef", sleep = {
                delays++
                if (delays == 2) allowed = false
            })
            fail("A revoked permit must prevent the next typed prefix")
        } catch (failure: Revoked) {
            assertSame(denied, failure)
        }
        assertEquals(2, delays)
        assertEquals(listOf("a"), writes)
    }

    @Test fun cancellationAtDispatchIsNotAnOrdinaryRetryableWriteFailure() = runTest {
        val stopped = CancellationException("User stopped the fixed run")
        var checks = 0
        var writes = 0
        val field = object : TextField {
            override fun read() = ""
            override fun write(text: String): Boolean { writes++; return false }
            override suspend fun writeChecked(text: String): Boolean { checks++; throw stopped }
        }
        try {
            typeAndVerify(field, "never type", attempts = 5)
            fail("Dispatch cancellation must propagate")
        } catch (failure: CancellationException) {
            assertSame(stopped, failure)
        }
        assertEquals(1, checks)
        assertEquals(0, writes)
    }

    @Test fun existingSynchronousFieldsKeepTheirTypingAndVerificationBehavior() = runTest {
        var actual = ""
        val writes = mutableListOf<String>()
        val field = object : TextField {
            override fun read() = actual
            override fun write(text: String): Boolean { writes += text; actual = text; return true }
        }
        assertEquals(Verification.MATCH, typeAndVerify(field, "first"))
        assertEquals(listOf("first"), writes)

        writes.clear()
        TypingPacing.human(Random(7)).leadIn(field, "abcdef", sleep = {})
        assertEquals(listOf("a", "ab", "abc", "abcd", "abcde"), writes)
        assertEquals(Verification.MATCH, typeAndVerify(field, "abcdef"))
        assertEquals("abcdef", actual)
    }
}
