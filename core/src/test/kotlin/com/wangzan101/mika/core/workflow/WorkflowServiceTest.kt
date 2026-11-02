package com.wangzan101.mika.core.workflow

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowServiceTest {
    private val condition = WorkflowCondition(appPackage = "example.notes", textPresent = "Ready")

    private fun definition(
        version: String = "1",
        effect: WorkflowEffect = WorkflowEffect.LOCAL,
        wait: Boolean = false,
    ) = WorkflowDefinition(id = "notes", version = version, name = "填写文字", description = "用参数填写本机测试页面",
        parameters = listOf(WorkflowParameter("message", defaultValue = JsonPrimitive("first"))),
        steps = (if (wait) listOf(WorkflowStep("confirm", "等待用户", kind = WorkflowStepKind.WAIT_USER, prompt = "请处理后继续")) else emptyList()) +
            WorkflowStep("type", "填写", tool = "type", arguments = buildJsonObject { put("text", "{{param.message}}") },
                effect = effect, before = condition, after = condition), successCondition = condition)

    private class Port : WorkflowActionPort {
        override val supportedTools = setOf("type")
        var fingerprint = "phone-A:app-1:permission-yes"
        var matches = true
        var opens = 0
        var closes = 0
        val calls = mutableListOf<JsonObject>()
        var onCheck: (WorkflowCondition) -> Unit = {}
        var action: suspend () -> WorkflowActionResult = { WorkflowActionResult(WorkflowActionStatus.SUCCEEDED) }
        override suspend fun environment() = WorkflowEnvironment(fingerprint, "测试手机")
        override suspend fun open(run: WorkflowRun) { opens++ }
        override suspend fun close(runId: String) { closes++ }
        override suspend fun check(condition: WorkflowCondition): WorkflowCheck {
            onCheck(condition)
            return WorkflowCheck(matches, if (matches) "matched" else "page changed")
        }
        override suspend fun execute(step: WorkflowStep, arguments: JsonObject, runId: String, allowExternalEffects: Boolean): WorkflowActionResult {
            calls += arguments
            return action()
        }
    }

    private fun assertCode(code: String, action: () -> Unit) {
        try { action(); fail("expected $code") }
        catch (e: WorkflowException) { assertEquals(code, e.code) }
    }

    @Test fun importIsNotExecutionAndVersionConflictsDoNotOverwrite() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition())
        assertFalse(item.enabled)
        assertNull(item.validation)
        assertEquals(item.id, service.importPackage(service.exportPackage(item.id)).id)
        assertCode("version_conflict") { service.saveDefinition(definition().copy(name = "changed")) }
        assertEquals("填写文字", service.snapshot().installations.single().definition.name)
        advanceUntilIdle()
        assertTrue(port.calls.isEmpty())
        assertCode("validation_required") { service.setEnabled(item.id, true) }
    }

    @Test fun bindingsStayPrivateAndChangingThemInvalidatesValidation() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition().copy(bindings = listOf(WorkflowParameter("account"))))
        service.bind(item.id, buildJsonObject { put("account", "private-account-A") })
        val validation = service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        assertEquals(WorkflowRunState.SUCCEEDED, service.status(validation.id).state)
        service.setEnabled(item.id, true)
        val pack = service.exportPackage(item.id)
        assertFalse(pack.contains("private-account-A"))
        assertFalse(pack.contains("phone-A"))
        val changed = service.bind(item.id, buildJsonObject { put("account", "private-account-B") })
        assertFalse(changed.enabled)
        assertNull(changed.validation)
    }

    @Test fun parameterizedRunsAndNormalizedRetriesUseOneDurableRequest() = runTest {
        val store = MemoryWorkflowStore()
        val port = Port()
        val service = WorkflowService(store, port, this, "device")
        val item = service.saveDefinition(definition())
        service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        service.setEnabled(item.id, true)
        port.calls.clear()
        val request = WorkflowStartRequest(item.id, "request-1", workflowVersion = "1")
        val first = service.start(request)
        assertEquals(first.id, service.start(request.copy(parameters = buildJsonObject { put("message", "first") })).id)
        assertCode("request_conflict") { service.start(request.copy(parameters = buildJsonObject { put("message", "different") })) }
        advanceUntilIdle()
        assertEquals(1, port.calls.size)
        assertEquals("first", port.calls.single()["text"]!!.jsonPrimitive.content)
        assertEquals(WorkflowRunState.SUCCEEDED, service.status(first.id).state)
        service.setEnabled(item.id, false)
        assertEquals(first.id, service.start(request).id)
        val reopened = WorkflowService(store, port, this, "device")
        assertEquals(first.id, reopened.findByRequest("request-1")!!.id)
        assertEquals(first.id, reopened.start(request).id)
        assertEquals(1, port.calls.size)
    }

    @Test fun invalidParametersAreRejectedBeforeAnyAction() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition())
        assertCode("unknown_parameter") { service.startValidation(item.id, buildJsonObject { put("extra", "x") }) }
        assertCode("invalid_parameter_type") { service.startValidation(item.id, buildJsonObject { put("message", 123) }) }
        advanceUntilIdle()
        assertTrue(port.calls.isEmpty())
        assertTrue(service.snapshot().runs.isEmpty())
    }

    @Test fun dryValidationStopsBeforeBusinessEffectAndRealExecutionNeedsAuthorization() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition(effect = WorkflowEffect.EXTERNAL))
        val trial = service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        assertEquals(WorkflowRunState.SUCCEEDED, service.status(trial.id).state)
        assertTrue(port.calls.isEmpty())
        assertFalse(service.snapshot().installations.single().validation!!.externalEffectsVerified)
        service.setEnabled(item.id, true)
        assertCode("effect_authorization_required") {
            service.start(WorkflowStartRequest(item.id, "publish", workflowVersion = "1"))
        }
        val run = service.start(WorkflowStartRequest(item.id, "publish", workflowVersion = "1", allowExternalEffects = true))
        advanceUntilIdle()
        assertEquals(WorkflowRunState.SUCCEEDED, service.status(run.id).state)
        assertEquals(1, port.calls.size)
    }

    @Test fun unseenPathsAfterAnExternalStepCannotBeMarkedValidated() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val def = definition(effect = WorkflowEffect.EXTERNAL)
        val item = service.saveDefinition(def.copy(steps = def.steps + def.steps.single().copy(id = "second")))
        val trial = service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        assertEquals(WorkflowRunState.FAILED, service.status(trial.id).state)
        assertNull(service.snapshot().installations.single().validation)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun anUnknownBusinessResultIsNeverRetriedEvenAfterRestart() = runTest {
        val store = MemoryWorkflowStore()
        val port = Port()
        val service = WorkflowService(store, port, this, "device")
        val item = service.saveDefinition(definition(effect = WorkflowEffect.EXTERNAL))
        service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        service.setEnabled(item.id, true)
        port.action = { WorkflowActionResult(WorkflowActionStatus.UNKNOWN, "response lost", effectDispatched = true) }
        val request = WorkflowStartRequest(item.id, "publish", workflowVersion = "1", allowExternalEffects = true)
        val run = service.start(request)
        advanceUntilIdle()
        assertEquals(WorkflowRunState.UNKNOWN, service.status(run.id).state)
        val restarted = WorkflowService(store, port, this, "device")
        assertEquals(run.id, restarted.start(request).id)
        advanceUntilIdle()
        assertEquals(1, port.calls.size)
        val stopped = restarted.cancel(run.id)
        assertEquals(WorkflowRunState.STOPPED, stopped.state)
        assertEquals(WorkflowStepState.UNKNOWN, stopped.checkpoints.single().state)
    }

    @Test fun staleContinueCannotAnswerTheNextWait() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val def = definition(wait = true)
        val item = service.saveDefinition(def.copy(steps = listOf(def.steps[0], def.steps[0].copy(id = "confirm-again"), def.steps[1])))
        val run = service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        val first = service.status(run.id)
        service.resume(run.id, first.waiting!!.id, first.stateVersion, JsonPrimitive(true))
        advanceUntilIdle()
        val second = service.status(run.id)
        assertEquals("confirm-again", second.waiting!!.stepId)
        assertCode("stale_wait") { service.resume(run.id, first.waiting.id, first.stateVersion, JsonPrimitive(true)) }
        assertTrue(port.calls.isEmpty())
        service.resume(run.id, second.waiting.id, second.stateVersion, JsonPrimitive(true))
        advanceUntilIdle()
        assertEquals(WorkflowRunState.SUCCEEDED, service.status(run.id).state)
    }

    @Test fun cancelDoesNotIssueTheNextActionAndKeepsUncertainCheckpoint() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val def = definition()
        val item = service.saveDefinition(def.copy(steps = def.steps + def.steps.single().copy(id = "second")))
        val entered = CompletableDeferred<Unit>()
        port.action = { entered.complete(Unit); awaitCancellation() }
        val run = service.startValidation(item.id, JsonObject(emptyMap()))
        runCurrent()
        entered.await()
        assertEquals(WorkflowRunState.STOPPING, service.cancel(run.id).state)
        assertEquals(WorkflowRunState.STOPPING, service.cancel(run.id).state)
        advanceUntilIdle()
        val result = service.status(run.id)
        assertEquals(WorkflowRunState.STOPPED, result.state)
        assertEquals(1, port.calls.size)
        assertEquals(WorkflowStepState.UNKNOWN, result.checkpoints.single().state)
        assertEquals(port.opens, port.closes)
    }

    @Test fun cancellingBeforeTheWorkerStartsIsImmediatelyStopped() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition())
        val run = service.startValidation(item.id, JsonObject(emptyMap()))
        assertEquals(WorkflowRunState.STOPPED, service.cancel(run.id).state)
        advanceUntilIdle()
        assertEquals(WorkflowRunState.STOPPED, service.status(run.id).state)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun preparedActionAtProcessLossRecoversAsUnknownAndNeverRunsItself() = runTest {
        val store = MemoryWorkflowStore()
        val port = Port()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = WorkflowService(store, port, firstScope, "device")
        val item = first.saveDefinition(definition(effect = WorkflowEffect.EXTERNAL))
        first.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        first.setEnabled(item.id, true)
        val run = first.start(WorkflowStartRequest(item.id, "lost-process", workflowVersion = "1", allowExternalEffects = true))
        firstScope.cancel()
        val snapshot = store.load()
        store.save(snapshot.copy(runs = snapshot.runs.map {
            if (it.id != run.id) it else it.copy(state = WorkflowRunState.RUNNING,
                checkpoints = listOf(WorkflowCheckpoint("type", WorkflowStepState.PREPARED, WorkflowEffect.EXTERNAL, recordedAt = 1L)))
        }))
        val restarted = WorkflowService(store, port, this, "device")
        assertEquals(WorkflowRunState.UNKNOWN, restarted.status(run.id).state)
        assertNull(restarted.status(run.id).waiting)
        advanceUntilIdle()
        assertTrue(port.calls.isEmpty())
    }

    @Test fun aWriteFailureAfterDispatchBlocksAllNewActionsAndPreservesTheLastCheckpoint() = runTest {
        val durable = MemoryWorkflowStore()
        var failWrites = false
        val store = object : WorkflowStore {
            override fun load() = durable.load()
            override fun save(snapshot: WorkflowSnapshot) {
                if (failWrites) throw IllegalStateException("disk unavailable")
                durable.save(snapshot)
            }
        }
        val port = Port()
        val service = WorkflowService(store, port, this, "device")
        val item = service.saveDefinition(definition(effect = WorkflowEffect.EXTERNAL))
        service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        service.setEnabled(item.id, true)
        port.action = { failWrites = true; WorkflowActionResult(WorkflowActionStatus.SUCCEEDED, effectDispatched = true) }
        val run = service.start(WorkflowStartRequest(item.id, "write-loss", workflowVersion = "1", allowExternalEffects = true))
        advanceUntilIdle()
        assertNotNull(service.snapshot().storageIssue)
        assertCode("storage_unavailable") { service.start(WorkflowStartRequest(item.id, "again", workflowVersion = "1", allowExternalEffects = true)) }
        failWrites = false
        val restarted = WorkflowService(store, port, this, "device")
        assertEquals(WorkflowRunState.UNKNOWN, restarted.status(run.id).state)
        assertEquals(1, port.calls.size)
    }

    @Test fun environmentIsCheckedAgainAfterQueueing() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition())
        service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        service.setEnabled(item.id, true)
        port.calls.clear()
        val run = service.start(WorkflowStartRequest(item.id, "run", workflowVersion = "1"))
        port.fingerprint = "app-upgraded"
        advanceUntilIdle()
        assertEquals(WorkflowRunState.FAILED, service.status(run.id).state)
        assertFalse(service.snapshot().installations.single().enabled)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun aConditionWaitCannotSkipTheRealUserPrompt() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val def = definition(wait = true)
        val item = service.saveDefinition(def.copy(steps = listOf(def.steps[0].copy(before = condition), def.steps[1])))
        port.matches = false
        val run = service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        val blocked = service.status(run.id)
        assertFalse(blocked.waiting!!.advanceOnConfirm)
        service.resume(run.id, blocked.waiting.id, blocked.stateVersion, JsonPrimitive(true))
        advanceUntilIdle()
        assertEquals(0, service.status(run.id).nextStep)
        assertTrue(port.calls.isEmpty())
        port.matches = true
        val retry = service.status(run.id)
        service.resume(run.id, retry.waiting!!.id, retry.stateVersion, JsonPrimitive(true))
        advanceUntilIdle()
        val prompt = service.status(run.id)
        assertTrue(prompt.waiting!!.advanceOnConfirm)
        assertEquals(0, prompt.nextStep)
        service.resume(run.id, prompt.waiting.id, prompt.stateVersion, JsonPrimitive(true))
        advanceUntilIdle()
        assertEquals(WorkflowRunState.SUCCEEDED, service.status(run.id).state)
    }

    @Test fun validationCannotCombineStepsFromDifferentEnvironments() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition(wait = true))
        val run = service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        val waiting = service.status(run.id)
        port.fingerprint = "different-environment"
        service.resume(run.id, waiting.waiting!!.id, waiting.stateVersion, JsonPrimitive(true))
        advanceUntilIdle()
        assertEquals(WorkflowRunState.FAILED, service.status(run.id).state)
        assertNull(service.snapshot().installations.single().validation)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun resolvedEmptySuccessConditionIsRejectedBeforeAccepting() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition().copy(successCondition = WorkflowCondition(textPresent = "{{param.message}}")))
        assertCode("invalid_condition") { service.startValidation(item.id, buildJsonObject { put("message", "") }) }
        assertTrue(service.snapshot().runs.isEmpty())
        assertTrue(port.calls.isEmpty())
    }

    @Test fun cancellationAtThePreSubmitBoundaryCannotWriteAValidationPass() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val item = service.saveDefinition(definition(effect = WorkflowEffect.EXTERNAL))
        val run = service.startValidation(item.id, JsonObject(emptyMap()))
        port.onCheck = { service.cancel(run.id) }
        advanceUntilIdle()
        assertEquals(WorkflowRunState.STOPPED, service.status(run.id).state)
        assertNull(service.snapshot().installations.single().validation)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun requestOwnershipCannotReadOrCancelAnotherCallersRun() = runTest {
        val service = WorkflowService(MemoryWorkflowStore(), Port(), this, "device")
        val item = service.saveDefinition(definition())
        service.startValidation(item.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        service.setEnabled(item.id, true)
        val run = service.start(WorkflowStartRequest(item.id, "request", workflowVersion = "1"), "caller-A")
        assertCode("not_authorized") { service.status(run.id, "caller-B") }
        assertCode("not_authorized") { service.cancel(run.id, "caller-B") }
        assertNull(service.findByRequest("request", "caller-B"))
        advanceUntilIdle()
    }

    @Test fun repairedVersionCanClaimOnlyOneContinuationAndKeepsOldVersion() = runTest {
        val port = Port()
        val service = WorkflowService(MemoryWorkflowStore(), port, this, "device")
        val old = service.saveDefinition(definition(effect = WorkflowEffect.EXTERNAL))
        service.startValidation(old.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        service.setEnabled(old.id, true)
        port.action = { WorkflowActionResult(WorkflowActionStatus.FAILED, "known not sent") }
        val original = service.start(WorkflowStartRequest(old.id, "original", workflowVersion = "1", allowExternalEffects = true))
        advanceUntilIdle()
        val newer = service.saveDefinition(definition("2", WorkflowEffect.EXTERNAL))
        service.startValidation(newer.id, JsonObject(emptyMap()))
        advanceUntilIdle()
        service.setEnabled(newer.id, true)
        port.action = { WorkflowActionResult(WorkflowActionStatus.SUCCEEDED) }
        val request = WorkflowStartRequest(newer.id, "repair-1", workflowVersion = "2", adaptationVersion = newer.adaptationVersion,
            allowExternalEffects = true, sourceRunId = original.id, remainingStepIds = listOf("type"))
        val repair = service.start(request)
        assertCode("recovery_claimed") { service.start(request.copy(requestId = "repair-2")) }
        advanceUntilIdle()
        assertEquals("1", service.status(original.id).definition.version)
        assertEquals("2", service.status(repair.id).definition.version)
        assertEquals(original.id, service.status(repair.id).businessRunId)
        assertEquals(repair.id, service.status(original.id).successorRunId)
        assertEquals(2, port.calls.size)
    }

    @Test fun corruptedFilesArePreservedAndNotReplacedByEmptyState() = runTest {
        val directory = Files.createTempDirectory("bksop-corrupt-").toFile()
        try {
            val file = directory.resolve("workflows-v1.json")
            file.writeText("{broken")
            val service = WorkflowService(FileWorkflowStore(directory), Port(), this, "device")
            assertNotNull(service.snapshot().storageIssue)
            assertCode("storage_unavailable") { service.saveDefinition(definition()) }
            assertEquals("{broken", file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun savedDefinitionsSurviveFileStoreReopen() = runTest {
        val directory = Files.createTempDirectory("bksop-persist-").toFile()
        try {
            val first = WorkflowService(FileWorkflowStore(directory), Port(), this, "device")
            val item = first.saveDefinition(definition())
            val reopened = WorkflowService(FileWorkflowStore(directory), Port(), this, "device")
            assertEquals(item, reopened.snapshot().installations.single())
            assertEquals(first.exportPackage(item.id), reopened.exportPackage(item.id))
        } finally { directory.deleteRecursively() }
    }
}
