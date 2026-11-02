package com.wangzan101.mika.core.workflow

import kotlinx.serialization.encodeToString
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

    private fun definitionAtPackageLimit(): WorkflowDefinition {
        val base = definition().copy(description = "x")
        val descriptionBytes = WorkflowCodec.MAX_PACKAGE_BYTES - WorkflowCodec.encodePackage(base).toByteArray(Charsets.UTF_8).size + 1
        val multibyte = "界"
        val characterBytes = multibyte.toByteArray(Charsets.UTF_8).size
        return base.copy(description = multibyte.repeat(descriptionBytes / characterBytes) + "x".repeat(descriptionBytes % characterBytes))
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

    @Test fun standardPackageAtUtf8ByteLimitIsValidAndRoundTrips() {
        val def = definitionAtPackageLimit()
        WorkflowCodec.validateDefinition(def, setOf("known"))
        val encoded = WorkflowCodec.encodePackage(def)
        assertEquals(WorkflowCodec.MAX_PACKAGE_BYTES, encoded.toByteArray(Charsets.UTF_8).size)
        assertTrue(encoded.length < WorkflowCodec.MAX_PACKAGE_BYTES)
        val decoded = WorkflowCodec.decodePackage(encoded)
        assertEquals(def, decoded.definition)
        assertEquals(WorkflowCodec.digest(def), decoded.digest)
    }

    @Test fun savingAndDirectEncodingRejectOneByteOverTheCompletePackageLimit() {
        val boundary = definitionAtPackageLimit()
        val tooLarge = boundary.copy(description = boundary.description + "x")
        val raw = WorkflowCodec.json.encodeToString(WorkflowPackage(definition = tooLarge, digest = WorkflowCodec.digest(tooLarge)))
        assertEquals(WorkflowCodec.MAX_PACKAGE_BYTES + 1, raw.toByteArray(Charsets.UTF_8).size)
        assertTrue(raw.length < WorkflowCodec.MAX_PACKAGE_BYTES)
        // 定义本身仍未超限；必须把标准分享包的包头、摘要及缩进一起计入。
        assertTrue(WorkflowCodec.json.encodeToString(tooLarge).toByteArray(Charsets.UTF_8).size < WorkflowCodec.MAX_PACKAGE_BYTES)
        assertCode("package_too_large") { WorkflowCodec.validateDefinition(tooLarge, setOf("known")) }
        assertCode("package_too_large") { WorkflowCodec.encodePackage(tooLarge) }
        assertCode("package_too_large") { WorkflowCodec.decodePackage(raw) }
    }

    @Test fun compactImportStillMustFitTheStandardExportFormatBeforeInstallation() {
        val boundary = definitionAtPackageLimit()
        val tooLarge = boundary.copy(description = boundary.description + "x")
        val compact = Json { encodeDefaults = true }.encodeToString(
            WorkflowPackage(definition = tooLarge, digest = WorkflowCodec.digest(tooLarge)))
        assertTrue(compact.toByteArray(Charsets.UTF_8).size < WorkflowCodec.MAX_PACKAGE_BYTES)
        val decoded = WorkflowCodec.decodePackage(compact)
        assertCode("package_too_large") { WorkflowCodec.validateDefinition(decoded.definition, setOf("known")) }
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
