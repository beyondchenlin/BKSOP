package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.workflow.*
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.StartOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real fixed service through the MCP adapter; phone actions remain an explicit fake port. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpWorkflowToolsTest {
    private class Port : WorkflowActionPort {
        override val supportedTools = setOf("type")
        var fingerprint = "phone:app-1:permission-yes"
        val calls = mutableListOf<JsonObject>()
        var result = WorkflowActionResult(WorkflowActionStatus.SUCCEEDED)
        override suspend fun environment() = WorkflowEnvironment(fingerprint)
        override suspend fun check(condition: WorkflowCondition) = WorkflowCheck(true)
        override suspend fun execute(step: WorkflowStep, arguments: JsonObject, runId: String, allowExternalEffects: Boolean): WorkflowActionResult {
            calls += arguments
            return result
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val port = Port()
        val allowed = mutableSetOf<String>()
        val service = WorkflowService(MemoryWorkflowStore(), port, scope, "phone-device")
        val tools = McpWorkflowTools({ service }, { it in allowed }, { port.environment() }, "authenticated-client")
    }

    private fun definition(id: String, effect: WorkflowEffect = WorkflowEffect.LOCAL, waits: Int = 0): WorkflowDefinition {
        val condition = WorkflowCondition(appPackage = "example.notes", textPresent = "Ready")
        return WorkflowDefinition(id = id, version = "1", name = "可分享流程", description = "在已验证页面填写运行参数",
            parameters = listOf(WorkflowParameter("title")),
            bindings = listOf(WorkflowParameter("account")),
            steps = (0 until waits).map { WorkflowStep("wait-$it", "等待用户", kind = WorkflowStepKind.WAIT_USER, prompt = "请在手机上处理后继续") } +
                WorkflowStep("type", "填写", tool = "type", arguments = buildJsonObject {
                    put("text", "{{param.title}}"); put("account", "{{binding.account}}")
                }, effect = effect, before = condition, after = condition), successCondition = condition)
    }

    private suspend fun TestScope.ready(fixture: Fixture, definition: WorkflowDefinition): WorkflowInstallation {
        val item = fixture.service.saveDefinition(definition)
        fixture.service.bind(item.id, buildJsonObject { put("account", "PRIVATE_ACCOUNT_VALUE") })
        val validation = fixture.service.startValidation(item.id, parameters())
        advanceUntilIdle()
        repeat(definition.steps.size) {
            val current = fixture.service.status(validation.id)
            current.waiting?.let { wait ->
                fixture.service.resume(current.id, wait.id, current.stateVersion, JsonPrimitive(true))
                advanceUntilIdle()
            }
        }
        assertEquals(WorkflowRunState.SUCCEEDED, fixture.service.status(validation.id).state)
        fixture.allowed += item.id
        return fixture.service.setEnabled(item.id, true)
    }

    private fun parameters() = buildJsonObject { put("title", "PRIVATE_PARAMETER_VALUE") }
    private fun runArgs(item: WorkflowInstallation, requestId: String = "stable-request", effects: Boolean = false) = buildJsonObject {
        put("device_id", "phone-device"); put("installation_id", item.id)
        put("workflow_version", item.definition.version); put("request_id", requestId)
        put("parameters", parameters()); put("allow_external_effects", effects)
    }
    private fun runLookup(runId: String) = buildJsonObject { put("device_id", "phone-device"); put("run_id", runId) }
    private fun requestLookup(requestId: String) = buildJsonObject { put("device_id", "phone-device"); put("request_id", requestId) }
    private fun with(args: JsonObject, key: String, value: JsonElement) = JsonObject(args + (key to value))

    private suspend fun error(code: String, action: suspend () -> Unit): McpTaskTools.ToolError {
        try { action(); fail("Expected tool error: $code") }
        catch (e: McpTaskTools.ToolError) { assertTrue(e.message, e.message.orEmpty().startsWith("$code:")); return e }
        throw AssertionError("Tool accepted an invalid request")
    }

    @Test fun explicitDeviceAndWorkflowAuthorizationAreRequiredBeforeAcceptance() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("authorized"))
        f.port.calls.clear()
        error("missing_parameter") { f.tools.call(McpWorkflowTools.RUN, JsonObject(runArgs(item).filterKeys { it != "device_id" })) }
        error("wrong_device") { f.tools.call(McpWorkflowTools.RUN, with(runArgs(item), "device_id", JsonPrimitive("another-phone"))) }
        f.allowed.clear()
        error("not_authorized") { f.tools.call(McpWorkflowTools.RUN, runArgs(item)) }
        advanceUntilIdle()
        assertTrue(f.port.calls.isEmpty())
        assertNull(f.service.findByRequest("stable-request", "authenticated-client"))
    }

    @Test fun optionalWorkflowExtensionKeepsLegacyToolsAndDoesNotRouteFixedRequestsIntoTheAgent() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("extension"))
        f.port.calls.clear()
        var legacyLists = 0
        val legacy = object : RemoteTasks {
            override fun start(goal: String, conversation: String?): StartOutcome = throw AssertionError("Fixed requests must never enter the agent")
            override fun get(id: String): RemoteTask? = null
            override fun list(limit: Int): List<RemoteTask> { legacyLists++; return emptyList() }
            override fun answer(id: String, text: String) = false
            override fun steer(id: String, text: String): String? = null
            override fun cancel(id: String) = false
        }
        val baseline = McpTaskTools(RemoteTaskService(legacy))
        val extended = McpTaskTools(RemoteTaskService(legacy), workflowTools = f.tools)
        val oldNames = baseline.definitions().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val newNames = extended.definitions().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(newNames.containsAll(oldNames))
        assertEquals(oldNames.size + 5, newNames.size)
        assertFalse(baseline.has(McpWorkflowTools.RUN))
        assertTrue(extended.call(McpTaskTools.LIST, JsonObject(emptyMap()))["tasks"]!!.jsonArray.isEmpty())
        assertEquals(1, legacyLists)
        val run = extended.call(McpWorkflowTools.RUN, runArgs(item))
        advanceUntilIdle()
        assertEquals("succeeded", extended.call(McpWorkflowTools.STATUS,
            runLookup(run["run_id"]!!.jsonPrimitive.content))["status"]!!.jsonPrimitive.content)
        assertEquals(1, f.port.calls.size)
    }

    @Test fun clientsCannotSupplyIdentityOrOverrideFixedActions() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("identity"))
        f.port.calls.clear()
        for (key in listOf("caller_id", "user_id", "owner_id", "goal", "actions", "tool")) {
            error("unknown_parameter") { f.tools.call(McpWorkflowTools.RUN, with(runArgs(item), key, JsonPrimitive("local"))) }
        }
        error("unknown_parameter") { f.tools.call(McpWorkflowTools.LIST, buildJsonObject { put("caller_id", "local") }) }
        advanceUntilIdle()
        assertTrue(f.port.calls.isEmpty())
        try {
            McpWorkflowTools({ f.service }, { true }, { f.port.environment() }, "local")
            fail("Remote tools must not borrow the privileged local-owner identity")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun listIncludesOnlyEnabledValidatedCurrentlyCompatibleRemoteAllowedFlows() = runTest {
        val f = Fixture(this)
        val expected = ready(f, definition("available"))
        val disabled = ready(f, definition("disabled"))
        f.service.setEnabled(disabled.id, false)
        val denied = ready(f, definition("not-allowed"))
        f.allowed.remove(denied.id)
        val pending = f.service.saveDefinition(definition("not-validated"))
        f.allowed += pending.id
        f.port.fingerprint = "old-app-version"
        val stale = ready(f, definition("old-environment"))
        f.port.fingerprint = "phone:app-1:permission-yes"
        val callsBefore = f.port.calls.size
        val listed = f.tools.call(McpWorkflowTools.LIST, JsonObject(emptyMap()))
        assertEquals("phone-device", listed["device_id"]!!.jsonPrimitive.content)
        val items = listed["workflows"]!!.jsonArray
        assertEquals(listOf(expected.id), items.map { it.jsonObject["installation_id"]!!.jsonPrimitive.content })
        assertFalse(listed.toString().contains(stale.id))
        assertFalse(listed.toString().contains("PRIVATE_ACCOUNT_VALUE"))
        val schema = items.single().jsonObject["parameters"]!!.jsonObject
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(JsonPrimitive("string"), schema["properties"]!!.jsonObject["title"]!!.jsonObject["type"])
        assertEquals(listOf(JsonPrimitive("title")), schema["required"]!!.jsonArray.toList())
        assertEquals(callsBefore, f.port.calls.size)
    }

    @Test fun duplicateStartsAndLostStartResponsesFindTheSameRunWithoutReexecuting() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("dedup"))
        f.port.calls.clear()
        val args = runArgs(item)
        val accepted = f.tools.call(McpWorkflowTools.RUN, args)
        assertEquals("queued", accepted["status"]!!.jsonPrimitive.content)
        val repeated = f.tools.call(McpWorkflowTools.RUN, args)
        assertEquals(accepted["run_id"], repeated["run_id"])
        val before = f.service.snapshot().runs.size
        val recoveredReply = f.tools.call(McpWorkflowTools.STATUS, requestLookup("stable-request"))
        assertEquals(accepted["run_id"], recoveredReply["run_id"])
        assertEquals(before, f.service.snapshot().runs.size)
        advanceUntilIdle()
        assertEquals(1, f.port.calls.size)
        val result = f.tools.call(McpWorkflowTools.STATUS, runLookup(accepted["run_id"]!!.jsonPrimitive.content))
        assertEquals("succeeded", result["status"]!!.jsonPrimitive.content)
        assertEquals(accepted["run_id"], f.tools.call(McpWorkflowTools.RUN, args)["run_id"])
        error("request_conflict") {
            f.tools.call(McpWorkflowTools.RUN, with(args, "parameters", buildJsonObject { put("title", "changed") }))
        }
        assertEquals(1, f.port.calls.size)
    }

    @Test fun requestLookupIsStrictlyReadOnlyAndCannotMixLookupKeys() = runTest {
        val f = Fixture(this)
        val before = f.service.snapshot().runs.size
        val missing = f.tools.call(McpWorkflowTools.STATUS, requestLookup("never-accepted"))
        assertEquals(JsonPrimitive(false), missing["found"])
        assertEquals(JsonPrimitive(true), missing["authoritative"])
        assertEquals(before, f.service.snapshot().runs.size)
        error("invalid_lookup") {
            f.tools.call(McpWorkflowTools.STATUS, with(requestLookup("never-accepted"), "run_id", JsonPrimitive("unknown")))
        }
        error("invalid_lookup") { f.tools.call(McpWorkflowTools.STATUS, buildJsonObject { put("device_id", "phone-device") }) }
        assertTrue(f.port.calls.isEmpty())
    }

    @Test fun differentAuthenticatedCallersCannotReadOrCancelEachOthersRun() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("ownership"))
        val accepted = f.tools.call(McpWorkflowTools.RUN, runArgs(item))
        val id = accepted["run_id"]!!.jsonPrimitive.content
        val other = McpWorkflowTools({ f.service }, { true }, { f.port.environment() }, "other-authenticated-client")
        error("not_authorized") { other.call(McpWorkflowTools.STATUS, runLookup(id)) }
        error("not_authorized") { other.call(McpWorkflowTools.CANCEL, runLookup(id)) }
        assertEquals(JsonPrimitive(false), other.call(McpWorkflowTools.STATUS, requestLookup("stable-request"))["found"])
        advanceUntilIdle()
        assertEquals(WorkflowRunState.SUCCEEDED, f.service.status(id).state)
    }

    @Test fun remoteResultsDoNotSerializeBindingsArgumentsDefinitionsOrScreenEvidence() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("projection"))
        f.port.result = WorkflowActionResult(WorkflowActionStatus.FAILED,
            message = "PRIVATE_ACCOUNT_VALUE at content://phone/private-media/item-42 was blocked",
            evidence = buildJsonObject {
                put("screen_text", "RAW_SCREEN_PRIVATE_TEXT")
                put("image", "PRIVATE_IMAGE_BASE64")
                put("path", "/storage/emulated/0/private-video.mp4")
            })
        val accepted = f.tools.call(McpWorkflowTools.RUN, runArgs(item))
        val id = accepted["run_id"]!!.jsonPrimitive.content
        advanceUntilIdle()
        val result = f.tools.call(McpWorkflowTools.STATUS, runLookup(id))
        assertEquals("failed", result["status"]!!.jsonPrimitive.content)
        val text = result.toString()
        for (privateValue in listOf("PRIVATE_ACCOUNT_VALUE", "PRIVATE_PARAMETER_VALUE", "RAW_SCREEN_PRIVATE_TEXT",
            "PRIVATE_IMAGE_BASE64", "content://phone", "/storage/emulated", "\"bindings\"", "\"parameters\"", "\"definition\"", "\"arguments\"")) {
            assertFalse("Leaked: $privateValue", text.contains(privateValue))
        }
        val checkpoint = result["checkpoints"]!!.jsonArray.single().jsonObject
        assertEquals("failed", checkpoint["status"]!!.jsonPrimitive.content)
        assertTrue(checkpoint["evidence_ref"]!!.jsonPrimitive.content.startsWith("local:$id:"))
        assertFalse(checkpoint.containsKey("evidence"))
    }

    @Test fun revokedWorkflowIsHiddenAndAllControlAndResultAccessAreRejected() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("revocation", waits = 1))
        val accepted = f.tools.call(McpWorkflowTools.RUN, runArgs(item))
        val id = accepted["run_id"]!!.jsonPrimitive.content
        advanceUntilIdle()
        val waiting = f.service.status(id).waiting!!
        f.allowed.remove(item.id)
        val count = f.port.calls.size
        assertTrue(f.tools.call(McpWorkflowTools.LIST, JsonObject(emptyMap()))["workflows"]!!.jsonArray.isEmpty())
        error("not_authorized") { f.tools.call(McpWorkflowTools.RUN, runArgs(item, "new-after-revocation")) }
        error("not_authorized") { f.tools.call(McpWorkflowTools.STATUS, runLookup(id)) }
        error("not_authorized") { f.tools.call(McpWorkflowTools.STATUS, requestLookup("stable-request")) }
        error("not_authorized") { f.tools.call(McpWorkflowTools.CANCEL, runLookup(id)) }
        error("not_authorized") {
            f.tools.call(McpWorkflowTools.RESUME, buildJsonObject {
                put("device_id", "phone-device"); put("run_id", id); put("wait_id", waiting.id)
                put("state_version", waiting.stateVersion); put("answer", true)
            })
        }
        advanceUntilIdle()
        assertEquals(count, f.port.calls.size)
        // Revocation's host-side cancellation is a separate Android integration rule; the local owner can always stop.
        f.service.cancel(id)
    }

    @Test fun unknownBusinessResultCannotBeConfirmedAwayOrResubmitted() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("unknown-result", WorkflowEffect.EXTERNAL))
        f.port.calls.clear()
        f.port.result = WorkflowActionResult(WorkflowActionStatus.UNKNOWN, "sent but response unavailable", effectDispatched = true)
        val args = runArgs(item, effects = true)
        val accepted = f.tools.call(McpWorkflowTools.RUN, args)
        val id = accepted["run_id"]!!.jsonPrimitive.content
        advanceUntilIdle()
        val unknown = f.tools.call(McpWorkflowTools.STATUS, requestLookup("stable-request"))
        assertEquals("unknown", unknown["status"]!!.jsonPrimitive.content)
        error("not_waiting") {
            f.tools.call(McpWorkflowTools.RESUME, buildJsonObject {
                put("device_id", "phone-device"); put("run_id", id); put("wait_id", "made-up")
                put("state_version", unknown["state_version"]!!); put("answer", true)
            })
        }
        val stopped = f.tools.call(McpWorkflowTools.CANCEL, runLookup(id))
        assertEquals("stopped", stopped["status"]!!.jsonPrimitive.content)
        assertEquals("unknown", stopped["checkpoints"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(accepted["run_id"], f.tools.call(McpWorkflowTools.RUN, args)["run_id"])
        advanceUntilIdle()
        assertEquals(1, f.port.calls.size)
    }

    @Test fun booleanResumeConsumesOnlyTheCurrentWaitAndQueuedCancelRunsNothing() = runTest {
        val f = Fixture(this)
        val item = ready(f, definition("waits", waits = 2))
        f.port.calls.clear()
        val accepted = f.tools.call(McpWorkflowTools.RUN, runArgs(item))
        val id = accepted["run_id"]!!.jsonPrimitive.content
        advanceUntilIdle()
        val status = f.tools.call(McpWorkflowTools.STATUS, runLookup(id))
        val wait = status["waiting"]!!.jsonObject
        val answer = buildJsonObject {
            put("device_id", "phone-device"); put("run_id", id); put("wait_id", wait["wait_id"]!!)
            put("state_version", wait["state_version"]!!); put("answer", true)
        }
        error("invalid_parameter") { f.tools.call(McpWorkflowTools.RESUME, with(answer, "answer", JsonPrimitive("true"))) }
        f.tools.call(McpWorkflowTools.RESUME, answer)
        advanceUntilIdle()
        error("stale_wait") { f.tools.call(McpWorkflowTools.RESUME, answer) }
        f.tools.call(McpWorkflowTools.CANCEL, runLookup(id))
        assertTrue(f.port.calls.isEmpty())
        val queued = f.tools.call(McpWorkflowTools.RUN, runArgs(item, "cancel-while-queued"))
        val cancelled = f.tools.call(McpWorkflowTools.CANCEL, runLookup(queued["run_id"]!!.jsonPrimitive.content))
        assertEquals("stopped", cancelled["status"]!!.jsonPrimitive.content)
        advanceUntilIdle()
        assertTrue(f.port.calls.isEmpty())
    }
}
