package com.wangzan101.mika.core.workflow

import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Strict, versioned wire format. No reflection, scripts, paths or mutable device state are imported. */
object WorkflowCodec {
    const val MAX_PACKAGE_BYTES = 1024 * 1024
    val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val reference = Regex("\\{\\{(param|binding)\\.([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\}\\}")

    fun digest(definition: WorkflowDefinition): String = digest(json.encodeToJsonElement(definition))

    fun digest(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    fun encodePackage(definition: WorkflowDefinition): String =
        serializePackage(definition).also(::checkPackageSize)

    fun decodePackage(text: String): WorkflowPackage {
        checkPackageSize(text)
        val pack = try { json.decodeFromString<WorkflowPackage>(text) }
        catch (_: Exception) { fail("invalid_package", "流程包格式错误或包含未知字段") }
        if (pack.formatVersion != 1 || pack.definition.formatVersion != 1)
            fail("unsupported_format", "不支持此流程格式版本")
        if (pack.digest != digest(pack.definition)) fail("digest_mismatch", "流程内容摘要不匹配")
        return pack
    }

    fun validateDefinition(definition: WorkflowDefinition, supportedTools: Set<String>) {
        if (definition.formatVersion != 1) fail("unsupported_format", "不支持此流程格式版本")
        checkId(definition.id, "流程标识")
        checkId(definition.version, "流程版本")
        if (definition.name.isBlank() || definition.description.isBlank())
            fail("invalid_definition", "流程名称和说明不能为空")
        validateParameters(definition.parameters, false)
        validateParameters(definition.bindings, true)
        if (definition.steps.isEmpty()) fail("invalid_definition", "流程至少需要一个步骤")
        if (definition.steps.map { it.id }.distinct().size != definition.steps.size)
            fail("duplicate_step", "步骤标识不能重复")
        val params = definition.parameters.map { it.name }.toSet()
        val bindings = definition.bindings.map { it.name }.toSet()
        definition.steps.forEach { step ->
            checkId(step.id, "步骤标识")
            if (step.title.isBlank()) fail("invalid_step", "步骤说明不能为空")
            when (step.kind) {
                WorkflowStepKind.ACTION -> {
                    if (step.tool !in supportedTools) fail("unsupported_tool", "当前执行器不支持工具：${step.tool}")
                    if (step.prompt != null) fail("invalid_step", "动作步骤不能带等待回答")
                    if (step.effect == WorkflowEffect.EXTERNAL && (step.before == null || step.after == null))
                        fail("missing_effect_checks", "对外业务动作必须声明派发前条件和结果条件")
                }
                WorkflowStepKind.WAIT_USER -> {
                    if (step.prompt.isNullOrBlank() || step.tool.isNotBlank() || step.arguments.isNotEmpty() || step.after != null ||
                        step.effect != WorkflowEffect.READ_ONLY)
                        fail("invalid_wait", "等待步骤仅能包含说明、提示和只读条件")
                }
            }
            step.before?.let(::validateCondition)
            step.after?.let(::validateCondition)
            validateReferences(step.arguments, params, bindings)
            step.before?.let { validateReferences(json.encodeToJsonElement(it), params, bindings) }
            step.after?.let { validateReferences(json.encodeToJsonElement(it), params, bindings) }
            step.prompt?.let { validateReferences(JsonPrimitive(it), params, bindings) }
            rejectDeviceLiterals(step.arguments)
        }
        validateCondition(definition.successCondition)
        validateReferences(json.encodeToJsonElement(definition.successCondition), params, bindings)
        // 保存/安装的定义必须能按同一标准格式导出并重新导入；包头、摘要和 UTF-8 字节都计入上限。
        checkPackageSize(serializePackage(definition))
    }

    // 只负责序列化，不能调用 validateDefinition，避免保存校验与导出入口相互递归。
    private fun serializePackage(definition: WorkflowDefinition): String =
        json.encodeToString(WorkflowPackage(definition = definition, digest = digest(definition)))

    private fun checkPackageSize(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_PACKAGE_BYTES)
            fail("package_too_large", "流程包超过 1 MiB 限制，请减少流程内容")
    }

    fun parameters(specs: List<WorkflowParameter>, supplied: JsonObject): JsonObject {
        val known = specs.map { it.name }.toSet()
        val extra = supplied.keys - known
        if (extra.isNotEmpty()) fail("unknown_parameter", "包含未声明字段：${extra.sorted().joinToString()}")
        val result = linkedMapOf<String, JsonElement>()
        specs.forEach { spec ->
            val value = supplied[spec.name] ?: spec.defaultValue
            if (value == null || value == JsonNull) {
                if (spec.required) fail("missing_parameter", "缺少必填字段：${spec.name}")
            } else {
                if (!matches(spec.type, value)) fail("invalid_parameter_type", "字段类型错误：${spec.name}")
                result[spec.name] = value
            }
        }
        return canonical(JsonObject(result)) as JsonObject
    }

    fun resolve(value: JsonElement, parameters: JsonObject, bindings: JsonObject): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { resolve(it.value, parameters, bindings) })
        is JsonArray -> JsonArray(value.map { resolve(it, parameters, bindings) })
        is JsonPrimitive -> if (!value.isString) value else {
            val whole = reference.matchEntire(value.content)
            if (whole != null) lookup(whole, parameters, bindings)
            else JsonPrimitive(reference.replace(value.content) { match ->
                val replacement = lookup(match, parameters, bindings)
                if (replacement !is JsonPrimitive || replacement == JsonNull)
                    fail("invalid_template_type", "嵌入文本的参数必须是标量")
                replacement.content
            })
        }
    }

    fun resolve(condition: WorkflowCondition, parameters: JsonObject, bindings: JsonObject): WorkflowCondition {
        fun field(value: String?): String? = value?.let {
            val result = resolve(JsonPrimitive(it), parameters, bindings)
            if (result !is JsonPrimitive || !result.isString)
                fail("invalid_condition_type", "页面条件必须解析为文字")
            result.content
        }
        return WorkflowCondition(field(condition.appPackage), field(condition.textPresent), field(condition.textAbsent)).also(::validateCondition)
    }

    fun checkId(value: String, label: String) {
        if (!identifier.matches(value)) fail("invalid_identifier", "$label 只能含英文字母、数字、点、下划线或短横线，长度为 1—128")
    }

    private fun validateParameters(specs: List<WorkflowParameter>, binding: Boolean) {
        if (specs.map { it.name }.distinct().size != specs.size) fail("duplicate_parameter", "参数名称不能重复")
        specs.forEach {
            checkId(it.name, "参数名称")
            if (binding && it.defaultValue != null) fail("private_binding_default", "本机绑定不能带作者默认值")
            it.defaultValue?.let { value ->
                if (value == JsonNull || !matches(it.type, value)) fail("invalid_default", "默认值类型错误：${it.name}")
                rejectDeviceLiterals(value)
            }
        }
    }

    private fun validateCondition(condition: WorkflowCondition) {
        val values = listOfNotNull(condition.appPackage, condition.textPresent, condition.textAbsent)
        if (values.isEmpty() || values.any(String::isBlank)) fail("invalid_condition", "页面条件须包含至少一个非空判断")
    }

    private fun validateReferences(value: JsonElement, parameters: Set<String>, bindings: Set<String>) {
        when (value) {
            is JsonObject -> value.values.forEach { validateReferences(it, parameters, bindings) }
            is JsonArray -> value.forEach { validateReferences(it, parameters, bindings) }
            is JsonPrimitive -> if (value.isString) {
                reference.findAll(value.content).forEach {
                    val names = if (it.groupValues[1] == "param") parameters else bindings
                    if (it.groupValues[2] !in names) fail("unknown_reference", "模板引用了未声明字段：${it.value}")
                }
                val remaining = reference.replace(value.content, "")
                if ("{{" in remaining || "}}" in remaining) fail("invalid_template", "无法识别的参数占位符")
            }
        }
    }

    private fun rejectDeviceLiterals(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                if (key.lowercase() in setOf("password", "token", "api_key", "authorization", "cookie") &&
                    item is JsonPrimitive && item.isString && reference.matchEntire(item.content) == null)
                    fail("private_literal", "共享流程中的敏感字段必须使用本机绑定或运行参数")
                rejectDeviceLiterals(item)
            }
            is JsonArray -> value.forEach(::rejectDeviceLiterals)
            is JsonPrimitive -> if (value.isString &&
                listOf("content://", "file://", "/storage/", "/sdcard/", "/data/user/").any(value.content::startsWith))
                fail("private_literal", "共享流程不能包含作者的本机文件引用，请改用本机绑定")
        }
    }

    private fun lookup(match: MatchResult, parameters: JsonObject, bindings: JsonObject): JsonElement {
        val values = if (match.groupValues[1] == "param") parameters else bindings
        return values[match.groupValues[2]] ?: fail("missing_reference", "本次运行缺少模板引用：${match.value}")
    }

    private fun matches(type: WorkflowValueType, value: JsonElement): Boolean = when (type) {
        WorkflowValueType.STRING -> value is JsonPrimitive && value.isString
        WorkflowValueType.INTEGER -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        WorkflowValueType.NUMBER -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
        WorkflowValueType.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        WorkflowValueType.OBJECT -> value is JsonObject
        WorkflowValueType.ARRAY -> value is JsonArray
    }

    private fun fail(code: String, message: String): Nothing = throw WorkflowException(code, message)
}
