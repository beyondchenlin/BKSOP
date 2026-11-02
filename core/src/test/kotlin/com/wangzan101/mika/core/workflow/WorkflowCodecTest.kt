package com.wangzan101.mika.core.workflow

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowCodecTest {
    private fun definition() = WorkflowDefinition(id = "example", version = "1", name = "示例", description = "参数化本机操作",
        parameters = listOf(WorkflowParameter("count", WorkflowValueType.INTEGER)),
        bindings = listOf(WorkflowParameter("resource")),
        steps = listOf(WorkflowStep("step", "操作", tool = "known", arguments = buildJsonObject {
            put("count", "{{param.count}}")
            put("label", "第 {{param.count}} 个")
            put("resource", "{{binding.resource}}")
        })), successCondition = WorkflowCondition(textPresent = "完成"))

    private fun assertCode(code: String, action: () -> Unit) {
        try { action(); fail("expected $code") } catch (e: WorkflowException) { assertEquals(code, e.code) }
    }

    @Test fun fullReferencesKeepTypesAndEmbeddedReferencesRemainText() {
        val def = definition()
        WorkflowCodec.validateDefinition(def, setOf("known"))
        val args = WorkflowCodec.resolve(def.steps.single().arguments,
            buildJsonObject { put("count", 3) }, buildJsonObject { put("resource", "content://private/file-3") }).jsonObject
        assertEquals(JsonPrimitive(3), args["count"])
        assertEquals(JsonPrimitive("第 3 个"), args["label"])
        assertEquals(JsonPrimitive("content://private/file-3"), args["resource"])
    }

    @Test fun canonicalDigestDoesNotDependOnObjectInsertionOrder() {
        val a = buildJsonObject { put("a", 1); put("b", buildJsonObject { put("x", 2); put("y", 3) }) }
        val b = buildJsonObject { put("b", buildJsonObject { put("y", 3); put("x", 2) }); put("a", 1) }
        assertEquals(WorkflowCodec.digest(a), WorkflowCodec.digest(b))
    }

    @Test fun packageDigestUnknownFieldsAndFormatAreChecked() {
        val encoded = WorkflowCodec.encodePackage(definition())
        assertEquals(definition(), WorkflowCodec.decodePackage(encoded).definition)
        assertCode("digest_mismatch") { WorkflowCodec.decodePackage(encoded.replace("参数化本机操作", "changed")) }
        assertCode("invalid_package") { WorkflowCodec.decodePackage(encoded.replaceFirst("{", "{\"extra\":1,")) }
        assertCode("unsupported_format") { WorkflowCodec.decodePackage(encoded.replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 9")) }
    }

    @Test fun importsRejectUnknownActionsAndAuthorDeviceReferences() {
        assertCode("unsupported_tool") { WorkflowCodec.validateDefinition(definition(), setOf("different")) }
        val step = definition().steps.single()
        assertCode("private_literal") {
            WorkflowCodec.validateDefinition(definition().copy(steps = listOf(step.copy(arguments = buildJsonObject {
                put("resource", "content://author-private/file")
            }))), setOf("known"))
        }
        assertCode("private_binding_default") {
            WorkflowCodec.validateDefinition(definition().copy(bindings = listOf(WorkflowParameter("resource", defaultValue = JsonPrimitive("private")))), setOf("known"))
        }
        assertCode("unknown_reference") {
            WorkflowCodec.validateDefinition(definition().copy(steps = listOf(step.copy(arguments = buildJsonObject {
                put("text", "{{param.missing}}")
            }))), setOf("known"))
        }
    }

    @Test fun effectAndSuccessConditionsCannotBeEmpty() {
        assertCode("invalid_condition") { WorkflowCodec.validateDefinition(definition().copy(successCondition = WorkflowCondition()), setOf("known")) }
        val step = definition().steps.single().copy(effect = WorkflowEffect.EXTERNAL)
        assertCode("missing_effect_checks") { WorkflowCodec.validateDefinition(definition().copy(steps = listOf(step)), setOf("known")) }
    }
}
