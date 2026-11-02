package com.wangzan101.mika.workflow

import com.wangzan101.mika.core.workflow.WorkflowCodec
import com.wangzan101.mika.core.workflow.WorkflowException
import com.wangzan101.mika.core.workflow.WorkflowParameter
import com.wangzan101.mika.core.workflow.WorkflowValueType
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkflowUiInputsTest {
    @Test
    fun documentReaderRejectsOversizedPickerContentBeforeParsing() {
        val bytes = ByteArray(WorkflowCodec.MAX_PACKAGE_BYTES + 1) { 'x'.code.toByte() }
        try {
            readWorkflowDocument(ByteArrayInputStream(bytes))
            fail("An oversized document must not be loaded")
        } catch (expected: WorkflowException) {
            assertEquals("package_too_large", expected.code)
        }
    }

    @Test
    fun documentReaderPreservesValidUtf8AndRejectsMalformedBytes() {
        val text = "{\"name\":\"流程与文字\"}"
        assertEquals(text, readWorkflowDocument(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        try {
            readWorkflowDocument(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
            fail("Malformed UTF-8 must not be silently replaced")
        } catch (_: CharacterCodingException) { }
    }

    @Test
    fun documentLimitAcceptsExactlyTheDeclaredMaximum() {
        val bytes = ByteArray(WorkflowCodec.MAX_PACKAGE_BYTES) { 'x'.code.toByte() }
        assertEquals(WorkflowCodec.MAX_PACKAGE_BYTES, readWorkflowDocument(ByteArrayInputStream(bytes)).length)
    }

    @Test
    fun fieldsKeepTextLiteralAndUseDeclaredTypesAndDefaults() {
        val fields = listOf(
            WorkflowParameter("title"),
            WorkflowParameter("count", WorkflowValueType.INTEGER),
            WorkflowParameter("enabled", WorkflowValueType.BOOLEAN, defaultValue = JsonPrimitive(false)),
            WorkflowParameter("optional", required = false),
        )
        val result = workflowInputValues(fields, mapOf("title" to " true ", "count" to "3"))
        assertEquals(JsonPrimitive(" true "), result["title"])
        assertEquals(JsonPrimitive(3), result["count"])
        assertEquals(JsonPrimitive(false), result["enabled"])
        assertFalse("optional" in result)
    }

    @Test
    fun malformedAndWrongTypeInputsFailWithoutStartingAnything() {
        val field = listOf(WorkflowParameter("count", WorkflowValueType.INTEGER))
        for (input in listOf("not a number", "\"3\"", "2.4")) {
            try {
                workflowInputValues(field, mapOf("count" to input))
                fail("Wrong field type must be rejected: " + input)
            } catch (expected: WorkflowException) {
                assertEquals("invalid_parameter_type", expected.code)
            }
        }
    }

    @Test
    fun emptyRequiredInputIsNotConvertedToAValidLookingValue() {
        try {
            workflowInputValues(listOf(WorkflowParameter("title")), mapOf("title" to ""))
            fail("An empty required field must be reported")
        } catch (expected: WorkflowException) {
            assertEquals("missing_parameter", expected.code)
        }
    }

    @Test
    fun editingCopiesNeverReuseAnExistingVersion() {
        assertEquals("1.0.2", nextWorkflowVersion("1.0.0", setOf("1.0.0", "1.0.1")))
        assertEquals("release.2", nextWorkflowVersion("release", setOf("release", "release.1")))
        val next = nextWorkflowVersion("2147483647", setOf("2147483647"))
        assertTrue(next.isNotEmpty())
        assertFalse(next == "2147483647")
    }
}
