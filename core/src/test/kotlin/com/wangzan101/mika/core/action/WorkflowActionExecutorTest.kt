package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.mcp.McpAnnotations
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpResult
import com.wangzan101.mika.core.cap.mcp.McpToolDescriptor
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.workflow.WorkflowEffect
import com.wangzan101.mika.core.workflow.WorkflowStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowActionExecutorTest {
    private class Events : RunSink {
        val values = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { values += e }
    }

    /** 返回真的改变下一屏文字，避免测试被「没有进展」守卫提前截断。 */
    private class Screen(private val label: String = "普通按钮") : ScreenPort by FakeScreen() {
        val actions = mutableListOf<String>()
        private var page = 0
        override suspend fun snapshot() = ScreenSnapshot("s$page", "com.chat", listOf(
            UiElement("@e1", "button", label, 900, 2300, listOf(800, 940, 999, 999), null, true, false, false),
            UiElement("@e2", "text", "页面 $page", 100, 100, listOf(0, 0, 200, 50), null, false, false, false),
        ), 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun back(): ActionResult {
            actions += "back"
            page++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            actions += ref
            page++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun tapXY(x: Int, y: Int): ActionResult {
            actions += "$x,$y"
            page++
            return ActionResult.Ok(changed = true)
        }
    }

    @Test(timeout = 10_000) fun ordinaryExecutorWaitsForTheFixedRunLease() = runTest {
        val screen = Screen()
        val executor = ActionExecutor(toolDispatcher(screen), OperationGuards(), null, {}, ActionRecorder(Events()))
        val release = CompletableDeferred<Unit>()
        val fixedRun = launch { DeviceExecutionGate.withExclusive { release.await() } }
        runCurrent()
        val ordinary = async { executor.run(Part.ToolCall("normal", "phone_back", "{}"), Origin.Model(1)) }
        runCurrent()
        try {
            assertTrue(screen.actions.isEmpty())
            assertFalse(ordinary.isCompleted)
        } finally {
            release.complete(Unit)
        }
        fixedRun.join()
        assertTrue(ordinary.await() is ActionOutcome.Ran)
        assertEquals(listOf("back"), screen.actions)
    }

    @Test(timeout = 10_000) fun offScreenToolsContinueWhileTheDeviceIsReserved() = runTest {
        val endpoint = object : McpEndpoint {
            override val serverId = "gate"
            var calls = 0
            override fun tools() = listOf(McpToolDescriptor("read", "Read", buildJsonObject { put("type", "object") },
                McpAnnotations(readOnlyHint = true)))
            override suspend fun call(name: String, arguments: JsonObject): McpResult {
                calls++
                return McpResult("read locally")
            }
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val executor = ActionExecutor(tools, OperationGuards(), null, {}, ActionRecorder(Events()))
        val release = CompletableDeferred<Unit>()
        val fixedRun = launch { DeviceExecutionGate.withExclusive { release.await() } }
        runCurrent()
        val read = async { executor.run(Part.ToolCall("read", "mcp_gate__read", "{}"), Origin.Model(1)) }
        runCurrent()
        try {
            assertTrue(read.isCompleted)
            assertEquals(1, endpoint.calls)
            val out = read.await() as ActionOutcome.Ran
            assertEquals(JsonPrimitive(true), out.exec.content["ok"])
        } finally {
            release.complete(Unit)
        }
        fixedRun.join()
    }

    @Test(timeout = 10_000) fun phoneRunReentersTheSameExecutorAndKeepsWorkflowPolicyForSubActions() = runTest {
        val screen = Screen()
        val events = Events()
        var lockScreenChecks = 0
        var savedFrames = 0
        val tools = toolDispatcher(screen, recordFrames = true).apply { captureTargets = true }
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = { lockScreenChecks++ },
            recorder = ActionRecorder(events, saveScreen = { _, _ -> savedFrames++ }), autonomous = true)
        val out = DeviceExecutionGate.withExclusive {
            executor.run(Part.ToolCall("fixed", "phone_run", """{"commands":[{"op":"back"}]}"""), Origin.Workflow(7))
        } as ActionOutcome.Ran
        assertEquals(JsonPrimitive(true), out.exec.content["ok"])
        assertEquals(JsonPrimitive(1), out.exec.content["completed"])
        assertEquals(listOf("back"), screen.actions)
        assertEquals(1, lockScreenChecks)
        val results = events.values.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(results.any { it.tool == "phone_back" })
        assertTrue(results.any { it.tool == "phone_list_elements" })
        assertTrue(results.any { it.tool == "phone_run" })
        assertTrue(results.all { it.source == "workflow" && it.step == 7 && it.screenshotId == null })
        assertEquals(0, savedFrames)
        assertTrue(events.values.none { it is RunEvent.Media || it is RunEvent.ActionTarget })
    }

    @Test(timeout = 10_000) fun workflowDoesNotInheritAutonomyOrConsumeAnExistingGrant() = runTest {
        // 两种自主入口都验证：构造时开启，以及运行中设置开启。
        for (constructorAutonomy in listOf(false, true)) {
            val screen = Screen("提交订单")
            val tools = toolDispatcher(screen)
            tools.executeRead(Part.ToolCall("observe", "phone_observe", "{}"))
            val grant = ConfirmationGate().apply {
                declare(ConfirmationRequest(IrrevAction.order, "提交订单", "", "1", "com.chat"), "com.chat")
                decide(true, 1)
            }
            assertTrue(grant.hasGrant)
            val executor = ActionExecutor(tools, OperationGuards(), grant, {}, ActionRecorder(Events()),
                approvals = ApprovalSettings { !constructorAutonomy }, autonomous = constructorAutonomy)
            val call = Part.ToolCall("pay", "phone_tap", """{"ref":"@e1","snapshotId":"s0"}""")
            val fixed = executor.run(call, Origin.Workflow(1, allowExternalEffects = true)) as ActionOutcome.Refused
            assertEquals("confirmation_required", fixed.code)
            assertTrue(screen.actions.isEmpty())
            assertTrue("fixed run must not consume another task's grant", grant.hasGrant)
            // 相同执行器的原有自主来源仍按原行为运行。
            val normal = executor.run(call.copy(id = "normal"), Origin.Model(1))
            assertTrue(normal is ActionOutcome.Ran)
            assertEquals(listOf("@e1"), screen.actions)
        }
    }

    @Test(timeout = 10_000) fun workflowRefusesAnUnresolvedTapEvenOnAnAutonomousExecutor() = runTest {
        val screen = Screen()
        val tools = toolDispatcher(screen)
        val executor = ActionExecutor(tools, OperationGuards(), null, {}, ActionRecorder(Events()), autonomous = true)
        val out = executor.run(Part.ToolCall("blind", "phone_tap", """{"x":500,"y":500}"""), Origin.Workflow(1))
        assertTrue(out is ActionOutcome.Refused)
        assertEquals("target_unresolved", (out as ActionOutcome.Refused).code)
        assertTrue(screen.actions.isEmpty())
    }

    @Test(timeout = 10_000) fun localDeclarationCannotAuthorizeSendOrDelete() = runTest {
        for (label in listOf("发送", "删除")) {
            val screen = Screen(label)
            val tools = toolDispatcher(screen)
            tools.executeRead(Part.ToolCall("observe", "phone_observe", "{}"))
            val events = Events()
            val executor = ActionExecutor(tools, OperationGuards(), null, {}, ActionRecorder(events),
                approvals = ApprovalSettings { true }, autonomous = true)
            val step = WorkflowStep("wrong-local", "误标的本地步骤", tool = "phone_tap", effect = WorkflowEffect.LOCAL)
            val runAllowsEffects = true
            val origin = Origin.Workflow(1, allowExternalEffects = runAllowsEffects && step.effect == WorkflowEffect.EXTERNAL)
            val call = Part.ToolCall("local", step.tool, """{"ref":"@e1","snapshotId":"s0"}""")
            val out = executor.run(call, origin) as ActionOutcome.Refused
            assertEquals(label, "approval_unavailable", out.code)
            assertTrue(label, screen.actions.isEmpty())
            val audit = events.values.filterIsInstance<RunEvent.Approval>().single()
            assertEquals("workflow", audit.source)
            assertEquals("denied", audit.decision)

            val authorized = executor.run(call.copy(id = "external"), Origin.Workflow(1, allowExternalEffects = true))
            assertTrue("$label $authorized", authorized is ActionOutcome.Ran)
            assertEquals(listOf("@e1"), screen.actions)
        }
    }

    @Test(timeout = 10_000) fun declaredExternalAndDestructiveToolsNeedWorkflowEffectAuthorization() = runTest {
        for (annotations in listOf(McpAnnotations(openWorldHint = true), McpAnnotations(destructiveHint = true))) {
            val endpoint = object : McpEndpoint {
                override val serverId = "outward"
                var calls = 0
                override fun tools() = listOf(McpToolDescriptor("act", "Act", buildJsonObject { put("type", "object") }, annotations))
                override suspend fun call(name: String, arguments: JsonObject): McpResult {
                    calls++
                    return McpResult("done")
                }
            }
            val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
            val executor = ActionExecutor(tools, OperationGuards(), null, {}, ActionRecorder(Events()), autonomous = true)
            val call = Part.ToolCall("declared", "mcp_outward__act", "{}")
            val blocked = executor.run(call, Origin.Workflow(1)) as ActionOutcome.Refused
            assertEquals("approval_unavailable", blocked.code)
            assertEquals(0, endpoint.calls)
            val allowed = executor.run(call.copy(id = "allowed"), Origin.Workflow(1, allowExternalEffects = true))
            assertTrue(allowed is ActionOutcome.Ran)
            assertEquals(1, endpoint.calls)
        }
    }

    @Test(timeout = 10_000) fun phoneRunSubActionsCannotDropTheEffectAuthorizationFlag() = runTest {
        for (authorized in listOf(false, true)) {
            val screen = Screen("发送")
            val events = Events()
            val executor = ActionExecutor(toolDispatcher(screen), OperationGuards(), null, {}, ActionRecorder(events), autonomous = true)
            val out = DeviceExecutionGate.withExclusive {
                executor.run(Part.ToolCall("nested", "phone_run", """{"commands":[{"op":"tap","targetText":"发送"}]}"""),
                    Origin.Workflow(2, allowExternalEffects = authorized))
            } as ActionOutcome.Ran
            assertEquals(JsonPrimitive(authorized), out.exec.content["ok"])
            assertEquals(if (authorized) listOf("@e1") else emptyList<String>(), screen.actions)
            assertTrue(events.values.filterIsInstance<RunEvent.ToolResult>().all { it.source == "workflow" })
        }
    }

    @Test(timeout = 10_000) fun cancellationAtTheGateCannotStartANonCancellableScriptAction() = runTest {
        val screen = Screen()
        val executor = ActionExecutor(toolDispatcher(screen), OperationGuards(), null,
            gate = { currentCoroutineContext().cancel() }, recorder = ActionRecorder(Events()))
        val attempt = async { executor.run(Part.ToolCall("cancelled", "phone_back", "{}"), Origin.Script(1)) }
        runCurrent()
        assertTrue(attempt.isCancelled)
        assertTrue(screen.actions.isEmpty())
        assertEquals("released", DeviceExecutionGate.withExclusive { "released" })
    }
}
