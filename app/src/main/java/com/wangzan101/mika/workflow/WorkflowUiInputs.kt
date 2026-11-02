package com.wangzan101.mika.workflow

import com.wangzan101.mika.core.workflow.WorkflowCodec
import com.wangzan101.mika.core.workflow.WorkflowException
import com.wangzan101.mika.core.workflow.WorkflowParameter
import com.wangzan101.mika.core.workflow.WorkflowValueType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The system picker may point to an untrusted or very large document. Bound reads before decoding. */
internal fun readWorkflowDocument(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > WorkflowCodec.MAX_PACKAGE_BYTES) {
            throw WorkflowException("package_too_large", "Workflow document exceeds the 1 MiB limit")
        }
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray())).toString()
}

internal fun workflowFieldValues(
    specs: List<WorkflowParameter>, values: JsonObject = JsonObject(emptyMap()),
): Map<String, String> = specs.associate { spec ->
    val value = values[spec.name] ?: spec.defaultValue
    spec.name to when {
        value == null -> ""
        spec.type == WorkflowValueType.STRING && value is JsonPrimitive -> value.content
        else -> value.toString()
    }
}

/** Empty inputs are omitted so the core applies declared defaults and checks required fields. */
internal fun workflowInputValues(specs: List<WorkflowParameter>, fields: Map<String, String>): JsonObject {
    val supplied = linkedMapOf<String, JsonElement>()
    specs.forEach { spec ->
        val text = fields[spec.name].orEmpty()
        if (text.isNotEmpty()) {
            supplied[spec.name] = if (spec.type == WorkflowValueType.STRING) JsonPrimitive(text)
            else try { WorkflowCodec.json.parseToJsonElement(text) }
            catch (_: IllegalArgumentException) {
                throw WorkflowException("invalid_parameter_type", "Invalid ${spec.type.name.lowercase()} value: ${spec.name}")
            }
        }
    }
    return WorkflowCodec.parameters(specs, JsonObject(supplied))
}

/** Copy editing creates a distinct immutable version, including when the proposed suffix already exists. */
internal fun nextWorkflowVersion(version: String, existing: Set<String>): String {
    val parts = version.split('.').toMutableList()
    val last = parts.lastOrNull()?.toIntOrNull()
    val prefix = if (last != null && last < Int.MAX_VALUE) parts.dropLast(1).joinToString(".") else version
    var number = if (last != null && last < Int.MAX_VALUE) last + 1 else 1
    while (true) {
        val candidate = if (prefix.isEmpty()) number.toString() else "$prefix.$number"
        if (candidate !in existing) return candidate
        if (number == Int.MAX_VALUE) throw WorkflowException("version_conflict", "Choose a new workflow version")
        number++
    }
}
