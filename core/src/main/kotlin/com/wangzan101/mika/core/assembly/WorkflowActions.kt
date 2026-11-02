package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.DeviceExecutionGate
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.PressKeys
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.lastSnapshot
import com.wangzan101.mika.core.workflow.WorkflowActionResult
import com.wangzan101.mika.core.workflow.WorkflowActionStatus
import com.wangzan101.mika.core.workflow.WorkflowCheck
import com.wangzan101.mika.core.workflow.WorkflowCondition
import com.wangzan101.mika.core.workflow.WorkflowEffect
import com.wangzan101.mika.core.workflow.WorkflowStep
import com.wangzan101.mika.core.workflow.WorkflowStepKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 固定流程的纯原生手机动作适配。每次运行一份；宿主管理 ScreenPort 的生命周期。
 * 点击/输入/清空必须用 selector:{text?,resourceId?,role?} 精确定位唯一控件，不能导入 ref 或猜坐标。
 * swipe 用 selector+direction 从当前滚动容器计算纵向轨迹；scroll 只操作当前唯一的可滚容器。
 * 不组装视觉、模型、沙箱、技能或任务收尾。业务成功仍由 WorkflowService 核对声明的后置/最终条件。
 */
class WorkflowActions(
    private val screen: ScreenPort,
    private val runId: String,
    selfPackage: String? = null,
    private val beforeAction: suspend () -> Unit = {},
) {
    private class NativeFailure(val code: String) : IllegalStateException(code)
    private data class Selector(val text: String?, val resourceId: String?, val role: String?)
    private data class Selection(val selector: Selector?, val kind: String, val element: UiElement, val app: String?)

    private var sequence = 0
    private var callNumber = 0
    private var issued = false
    private var selection: Selection? = null
    private var blockedCode: String? = null

    /** 所有真实写入前再次核对宿主授权和刚选中的控件；发出以后失败不能冒充「未执行」。 */
    private suspend fun dispatch(block: suspend () -> ActionResult): ActionResult {
        try {
            currentCoroutineContext().ensureActive()
            beforeAction()
            currentCoroutineContext().ensureActive()
            selection?.let { selected ->
                val fresh = screen.peek()
                usableSnapshot(fresh)
                if (fresh.packageName != selected.app) throw NativeFailure("target_changed")
                if (selected.kind == "native_scroll") find(fresh, null, "scroll")
                val target = find(fresh, selected.selector, selected.kind)
                val old = selected.element
                if (target.name != old.name || target.resourceId != old.resourceId || target.role != old.role ||
                    target.bounds != old.bounds || target.centerX != old.centerX || target.centerY != old.centerY)
                    throw NativeFailure("target_changed")
            }
            // peek 可能挂起；读回期间锁屏/权限/连接再变，也不能据过期授权派发。
            beforeAction()
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            blockedCode = (e as? NativeFailure)?.code ?: "execution_gate_failed"
            throw NativeFailure(blockedCode!!)
        }
        currentCoroutineContext().ensureActive()
        issued = true
        return block()
    }

    private val guardedScreen = object : ScreenPort by screen {
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun readVisibleText() = null
        override suspend fun frameGrid() = null
        override suspend fun tapRef(ref: String, snapshotId: String) = dispatch { screen.tapRef(ref, snapshotId) }
        override suspend fun tapXY(x: Int, y: Int): ActionResult = throw NativeFailure("coordinates_not_allowed")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = dispatch { screen.typeText(ref, snapshotId, text) }
        override suspend fun clearText(ref: String?, snapshotId: String?) = dispatch { screen.clearText(ref, snapshotId) }
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = dispatch { screen.scroll(ref, snapshotId, down) }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
            dispatch { screen.swipe(x1, y1, x2, y2, durationMs) }
        override suspend fun back() = dispatch { screen.back() }
        override suspend fun pressKey(keyCode: Int) = dispatch { screen.pressKey(keyCode) }
        override suspend fun openApp(packageName: String, cold: Boolean) = dispatch { screen.openApp(packageName, cold) }
    }
    private val tools = toolDispatcher(guardedScreen, visionEnabled = false, recordFrames = false,
        inlineScreen = false, adaptiveVision = false)
    private val executor = ActionExecutor(tools, OperationGuards(), null, gate = beforeAction,
        recorder = ActionRecorder(object : RunSink { override fun event(e: RunEvent) = Unit }), selfPackage = selfPackage)

    init { require(runId.isNotBlank()) }

    suspend fun check(condition: WorkflowCondition): WorkflowCheck = DeviceExecutionGate.withExclusive {
        try {
            val predicates = listOfNotNull(condition.appPackage, condition.textPresent, condition.textAbsent)
            if (predicates.isEmpty() || predicates.any(String::isBlank)) throw NativeFailure("invalid_condition")
            val snap = readSnapshot()
            val labels = visibleLabels(snap)
            if ((condition.textPresent != null || condition.textAbsent != null) && labels.isEmpty())
                throw NativeFailure("text_not_verifiable")
            if (condition.textAbsent != null && containsSecret(snap)) throw NativeFailure("private_text_not_verifiable")
            val appMatches = condition.appPackage?.let { snap.packageName == it } ?: true
            val presentMatches = condition.textPresent?.let { text -> labels.any { text in it } } ?: true
            val absentMatches = condition.textAbsent?.let { text -> labels.none { text in it } } ?: true
            val matched = appMatches && presentMatches && absentMatches
            WorkflowCheck(matched, if (matched) "本机条件已核对" else "当前页面不满足声明条件", buildJsonObject {
                put("matched", matched)
                condition.appPackage?.let { put("appMatched", appMatches) }
                condition.textPresent?.let { put("textPresentMatched", presentMatches) }
                condition.textAbsent?.let { put("textAbsentMatched", absentMatches) }
            })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            WorkflowCheck(false, "无法核实当前页面条件", evidence((e as? NativeFailure)?.code ?: "screen_check_failed"))
        }
    }

    suspend fun execute(step: WorkflowStep, arguments: JsonObject, allowExternalEffects: Boolean): WorkflowActionResult =
        DeviceExecutionGate.withExclusive {
            issued = false
            blockedCode = null
            selection = null
            sequence++
            try {
                currentCoroutineContext().ensureActive()
                if (step.kind != WorkflowStepKind.ACTION || step.tool !in SUPPORTED_TOOLS)
                    throw NativeFailure("unsupported_tool")
                if (tools.offScreen(step.tool) || tools.composite(step.tool) || tools.surfaceOf(step.tool) != "phone")
                    throw NativeFailure("unsupported_tool")
                val writing = tools.writesScreen(step.tool)
                if (step.effect == WorkflowEffect.READ_ONLY && writing) throw NativeFailure("effect_declaration_mismatch")
                val external = step.effect == WorkflowEffect.EXTERNAL
                if (external && !allowExternalEffects) throw NativeFailure("effect_authorization_required")
                if (external && (step.before == null || step.after == null)) throw NativeFailure("missing_effect_checks")
                val allowed = ARGUMENTS.getValue(step.tool)
                if ((arguments.keys - allowed).isNotEmpty()) throw NativeFailure("unsupported_arguments")
                val resolved = prepare(step.tool, arguments, external && allowExternalEffects)
                beforeAction()
                currentCoroutineContext().ensureActive()
                val outcome = executor.run(call(step.tool, resolved), Origin.Workflow(sequence, external && allowExternalEffects))
                val ran = requireRan(outcome)
                if (!writing) usableSnapshot(tools.ctx.lastSnapshot ?: throw NativeFailure("screen_unavailable"))
                val content = ran.exec.content
                if (content["app_switched"] != null || content["opened"] != null || content["chooser"] != null ||
                    content["coveredBy"] != null || content["settled"] == JsonPrimitive(false) ||
                    content["readable"] == JsonPrimitive(false) || content["requestedCopy"] == JsonPrimitive(false) ||
                    content["enterSkipped"] == JsonPrimitive(true) ||
                    (content["verification"] as? JsonPrimitive)?.content in setOf("unknown", "mismatch") ||
                    (content["effect"] as? JsonObject)?.get("kind") == JsonPrimitive("no_effect"))
                    throw NativeFailure("action_result_unverified")
                WorkflowActionResult(WorkflowActionStatus.SUCCEEDED,
                    "原生步骤完成；业务结果由流程后置条件继续核验", buildJsonObject {
                        put("code", "native_step_completed"); put("tool", step.tool); put("nativeOk", true)
                        put("actionDispatched", issued); put("businessVerified", false)
                        (content["total"] as? JsonPrimitive)?.intOrNull?.let { put("matchCount", it) }
                    }, effectDispatched = external && issued)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val code = blockedCode ?: (e as? NativeFailure)?.code ?: "native_action_failed"
                WorkflowActionResult(if (issued) WorkflowActionStatus.UNKNOWN else WorkflowActionStatus.FAILED,
                    if (issued) "动作已派发但结果未核实，请核对后处理，不能自动重做" else "原生步骤未派发，请检查流程或本机条件",
                    evidence(code, issued), effectDispatched = step.effect == WorkflowEffect.EXTERNAL && issued)
            } finally { selection = null }
        }

    private suspend fun prepare(tool: String, args: JsonObject, external: Boolean): JsonObject {
        if (tool in READS || tool == "phone_launch_app") return args
        val snap = readSnapshot()
        if (tool in TARGETED) {
            if (tool == "phone_type_text" && args["pressEnter"] != null && args["pressEnter"] != JsonPrimitive(false))
                throw NativeFailure("combined_submit_not_allowed")
            val query = selector(args["selector"] as? JsonObject ?: throw NativeFailure("selector_required"))
            val kind = if (tool == "phone_tap") "tap" else "field"
            val target = find(snap, query, kind)
            selection = Selection(query, kind, target, snap.packageName)
            return JsonObject(args - "selector" + mapOf("ref" to JsonPrimitive(target.ref), "snapshotId" to JsonPrimitive(snap.snapshotId)))
        }
        if (tool == "phone_scroll" || tool == "phone_swipe") {
            val direction = (args["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (direction !in setOf("up", "down")) throw NativeFailure("unsupported_scroll_direction")
            val query = args["selector"]?.let { selector(it as? JsonObject ?: throw NativeFailure("invalid_selector")) }
            if (tool == "phone_swipe" && query == null) throw NativeFailure("selector_required")
            // 原生 scroll 不接收目标 ref；只在整页恰好一个可滚容器时使用该原语。
            if (tool == "phone_scroll") find(snap, null, "scroll")
            val kind = if (tool == "phone_scroll") "native_scroll" else "scroll"
            val target = find(snap, query, kind)
            selection = Selection(query, kind, target, snap.packageName)
            if (tool == "phone_scroll") return buildJsonObject { put("direction", direction); put("amount", 3) }
            val bounds = target.bounds
            if (bounds.size != 4 || bounds.any { it !in 0..999 } || bounds[2] - bounds[0] < 20 || bounds[3] - bounds[1] < 40)
                throw NativeFailure("target_bounds_unavailable")
            val x = (bounds[0] + bounds[2]) / 2
            val top = bounds[1] + (bounds[3] - bounds[1]) / 4
            val bottom = bounds[3] - (bounds[3] - bounds[1]) / 4
            return buildJsonObject {
                put("startX", x); put("endX", x)
                put("startY", if (direction == "down") bottom else top)
                put("endY", if (direction == "down") top else bottom)
                args["durationMs"]?.let { put("durationMs", it) }
            }
        }
        if (tool == "phone_press_key") {
            if (snap.elements.any { it.focused && it.secret }) throw NativeFailure("private_target")
            val key = (args["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            val code = key?.let(PressKeys::resolve) ?: throw NativeFailure("invalid_arguments")
            if (code in SUBMIT_KEYS && !external) throw NativeFailure("effect_authorization_required")
        }
        return args
    }

    private suspend fun readSnapshot(): ScreenSnapshot {
        currentCoroutineContext().ensureActive()
        beforeAction()
        requireRan(executor.run(call("phone_list_elements", JsonObject(emptyMap())), Origin.Workflow(sequence)))
        return (tools.ctx.lastSnapshot ?: throw NativeFailure("screen_unavailable")).also(::usableSnapshot)
    }

    private fun requireRan(outcome: ActionOutcome): ActionOutcome.Ran = when (outcome) {
        is ActionOutcome.Refused -> throw NativeFailure(safeCode(outcome.code))
        is ActionOutcome.Cancelled -> throw CancellationException("workflow action cancelled")
        is ActionOutcome.Ran -> {
            if (outcome.before.isNotEmpty() || outcome.after.isNotEmpty()) throw NativeFailure("operation_guard")
            if (outcome.exec.content["ok"] != JsonPrimitive(true))
                throw NativeFailure(safeCode((outcome.exec.content["error"] as? JsonPrimitive)?.content))
            outcome
        }
    }

    private fun usableSnapshot(snap: ScreenSnapshot) {
        if (snap.changedDuringRead) throw NativeFailure("screen_unstable")
        if (snap.protected) throw NativeFailure("screen_protected")
        if (!snap.hasWindow || snap.packageName.isNullOrBlank() || snap.unreadableApp != null || snap.opened?.onUserScreen == true)
            throw NativeFailure("screen_unavailable")
    }

    private fun selector(value: JsonObject): Selector {
        if (value.isEmpty() || (value.keys - setOf("text", "resourceId", "role")).isNotEmpty()) throw NativeFailure("invalid_selector")
        fun field(key: String): String? = value[key]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() && s.length <= 500 }
                ?: throw NativeFailure("invalid_selector")
        }
        return Selector(field("text"), field("resourceId"), field("role"))
    }

    private fun find(snap: ScreenSnapshot, selector: Selector?, kind: String): UiElement {
        val matches = snap.elements.filter { element ->
            !element.secret && UiStates.DISABLED !in element.states &&
                (when (kind) { "field" -> element.editable; "scroll", "native_scroll" -> element.scrollable; else -> element.clickable || element.editable }) &&
                (selector?.text == null || element.name.trim().equals(selector.text.trim(), true) ||
                    element.placeholder?.trim()?.equals(selector.text.trim(), true) == true) &&
                (selector?.resourceId == null || element.resourceId == selector.resourceId) &&
                (selector?.role == null || element.role.equals(selector.role, true))
        }
        if (matches.isEmpty()) throw NativeFailure("target_not_found")
        if (matches.size != 1) throw NativeFailure("ambiguous_target")
        return matches.single()
    }

    private fun visibleLabels(snap: ScreenSnapshot): List<String> {
        val labels = snap.elements.filter { !it.secret && !it.editable && !it.hint }.map { it.name }.toMutableList()
        fun visit(node: UiTreeNode) {
            if (node.secret || node.editable) return
            node.text?.let(labels::add)
            node.description?.let(labels::add)
            node.children.forEach(::visit)
        }
        snap.windows.forEach { window -> window.nodes.forEach(::visit) }
        return labels.filter(String::isNotBlank)
    }

    private fun containsSecret(snap: ScreenSnapshot): Boolean {
        fun secret(node: UiTreeNode): Boolean = node.secret || node.children.any(::secret)
        return snap.elements.any { it.secret } || snap.windows.any { window -> window.nodes.any(::secret) }
    }

    private fun call(tool: String, arguments: JsonObject) = Part.ToolCall("$runId.${++callNumber}", tool, arguments.toString())
    private fun safeCode(code: String?) = code?.takeIf { Regex("[a-z][a-z0-9_]{0,80}").matches(it) } ?: "native_action_failed"
    private fun evidence(code: String, dispatched: Boolean = false) = buildJsonObject {
        put("code", code); put("actionDispatched", dispatched)
    }

    companion object {
        private val READS = setOf("phone_observe", "phone_list_elements", "phone_query")
        private val TARGETED = setOf("phone_tap", "phone_type_text", "phone_clear_text")
        private val SUBMIT_KEYS = setOf(66, 160, 23, 62)
        private val ARGUMENTS = mapOf(
            "phone_launch_app" to setOf("package", "cold"),
            "phone_observe" to emptySet(), "phone_list_elements" to emptySet(),
            "phone_query" to setOf("text", "resourceId", "role", "limit"),
            "phone_tap" to setOf("selector"), "phone_type_text" to setOf("selector", "text", "pressEnter"),
            "phone_clear_text" to setOf("selector"),
            "phone_scroll" to setOf("selector", "direction"),
            "phone_swipe" to setOf("selector", "direction", "durationMs"),
            "phone_press_key" to setOf("key"), "phone_back" to emptySet(),
        )
        val SUPPORTED_TOOLS: Set<String> = ARGUMENTS.keys.toSet()
    }
}
