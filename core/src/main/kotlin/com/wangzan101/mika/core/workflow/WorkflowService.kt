package com.wangzan101.mika.core.workflow

import com.wangzan101.mika.core.action.DeviceExecutionGate
import com.wangzan101.mika.core.model.ModelExecutionPolicy
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Persistent fixed execution. No AgentLoop, model selection, learning, or model aftercare is used. */
class WorkflowService(
    private val store: WorkflowStore,
    private val port: WorkflowActionPort,
    private val scope: CoroutineScope,
    val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private var state: WorkflowSnapshot = try { recover(store.load()) }
    catch (e: Exception) { WorkflowSnapshot(storageIssue = e.message ?: "流程存储不可用") }

    fun snapshot(): WorkflowSnapshot = synchronized(lock) { copy(state) }

    fun importPackage(text: String): WorkflowInstallation = install(WorkflowCodec.decodePackage(text).definition)

    fun saveDefinition(definition: WorkflowDefinition): WorkflowInstallation = install(copy(definition))

    fun exportPackage(installationId: String): String = synchronized(lock) {
        ensureStorage()
        WorkflowCodec.encodePackage(installation(installationId).definition)
    }

    fun bind(installationId: String, bindings: JsonObject): WorkflowInstallation = synchronized(lock) {
        ensureStorage()
        val old = installation(installationId)
        val checked = WorkflowCodec.parameters(old.definition.bindings, bindings)
        if (checked == old.bindings) return@synchronized copy(old)
        val updated = old.copy(bindings = checked, adaptationVersion = old.adaptationVersion + 1, enabled = false, validation = null)
        persist(state.copy(installations = state.installations.map { if (it.id == old.id) updated else it }))
        copy(updated)
    }

    fun setEnabled(installationId: String, enabled: Boolean): WorkflowInstallation = synchronized(lock) {
        ensureStorage()
        val old = installation(installationId)
        if (enabled && !validated(old)) fail("validation_required", "流程尚未在本机通过验证，不能启用")
        val updated = old.copy(enabled = enabled)
        persist(state.copy(installations = state.installations.map { if (it.id == old.id) updated else it }))
        copy(updated)
    }

    fun startValidation(
        installationId: String,
        parameters: JsonObject,
        allowExternalEffects: Boolean = false,
    ): WorkflowRun {
        val run = synchronized(lock) {
            ensureStorage()
            if (state.runs.any { it.installationId == installationId && it.mode == WorkflowRunMode.VALIDATION && !terminal(it.state) })
                fail("validation_running", "此流程已有未结束验证，请处理或停止原验证")
            val item = installation(installationId)
            create(WorkflowStartRequest(installationId, "validation-${newId()}", parameters, item.definition.version,
                item.adaptationVersion, allowExternalEffects), "local", WorkflowRunMode.VALIDATION)
        }
        schedule(run.id)
        return run
    }

    fun start(request: WorkflowStartRequest, callerId: String = "local"): WorkflowRun {
        val run = synchronized(lock) {
            ensureStorage()
            if (callerId.isBlank() || request.requestId.isBlank()) fail("invalid_request", "调用身份和请求编号不能为空")
            val old = state.runs.find { it.callerId == callerId && it.deviceId == deviceId && it.requestId == request.requestId }
            if (old != null) {
                if (old.mode != WorkflowRunMode.EXECUTION || old.requestDigest != requestDigest(request, old.definition, old.adaptationVersion))
                    fail("request_conflict", "同一请求编号不能更换流程、版本或参数")
                return@synchronized copy(old)
            }
            create(request, callerId, WorkflowRunMode.EXECUTION)
        }
        if (run.state == WorkflowRunState.QUEUED) schedule(run.id)
        return run
    }

    fun status(runId: String, callerId: String = "local"): WorkflowRun = synchronized(lock) {
        ensureStorage(); copy(ownedRun(runId, callerId))
    }

    fun findByRequest(requestId: String, callerId: String = "local"): WorkflowRun? = synchronized(lock) {
        ensureStorage()
        state.runs.find { it.deviceId == deviceId && it.callerId == callerId && it.requestId == requestId }?.let { copy(it) }
    }

    fun resume(
        runId: String,
        waitId: String,
        stateVersion: Long,
        answer: JsonElement,
        callerId: String = "local",
    ): WorkflowRun {
        val run = synchronized(lock) {
            ensureStorage()
            val old = ownedRun(runId, callerId)
            val waiting = old.waiting ?: fail("not_waiting", "当前任务没有可回答的等待项")
            if (old.state !in setOf(WorkflowRunState.WAITING_USER, WorkflowRunState.PAUSED) ||
                waiting.id != waitId || old.stateVersion != stateVersion || waiting.stateVersion != stateVersion)
                fail("stale_wait", "等待项已改变或已处理，不能推进新的步骤")
            val confirmed = (answer as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: fail("invalid_answer", "当前等待项只接受明确的继续或停止")
            if (!confirmed) return@synchronized stopLocked(old)
            val step = old.definition.steps.getOrNull(old.nextStep)
            val advance = waiting.advanceOnConfirm && step?.kind == WorkflowStepKind.WAIT_USER && waiting.stepId == step.id
            update(old.id) {
                it.copy(state = WorkflowRunState.QUEUED, waiting = null, message = "已收到继续，重新核对当前环境",
                    checkpoints = if (advance) checkpointList(it, step!!, WorkflowStepState.SUCCEEDED, "用户已明确继续") else it.checkpoints)
            }
        }
        if (run.state == WorkflowRunState.QUEUED) schedule(run.id)
        return copy(run)
    }

    fun cancel(runId: String, callerId: String = "local"): WorkflowRun = synchronized(lock) {
        ensureStorage(); copy(stopLocked(ownedRun(runId, callerId)))
    }

    private fun install(definition: WorkflowDefinition): WorkflowInstallation = synchronized(lock) {
        ensureStorage()
        WorkflowCodec.validateDefinition(definition, port.supportedTools)
        val digest = WorkflowCodec.digest(definition)
        val old = state.installations.find { it.definition.id == definition.id && it.definition.version == definition.version }
        if (old != null) {
            if (old.digest != digest) fail("version_conflict", "同一流程版本已有不同内容，请使用新版本")
            return@synchronized copy(old)
        }
        val item = WorkflowInstallation(newId(), definition, digest, importedAt = now())
        persist(state.copy(installations = state.installations + item))
        copy(item)
    }

    private fun create(request: WorkflowStartRequest, callerId: String, mode: WorkflowRunMode): WorkflowRun {
        if (!scope.isActive) fail("runtime_unavailable", "手机执行服务未运行，未接受新的执行任务")
        val item = installation(request.installationId)
        WorkflowCodec.validateDefinition(item.definition, port.supportedTools)
        if (request.workflowVersion != item.definition.version ||
            request.adaptationVersion?.let { it != item.adaptationVersion } == true)
            fail("version_mismatch", "请求的流程或本机适配版本与安装记录不一致")
        if (mode == WorkflowRunMode.EXECUTION && (!item.enabled || !validated(item)))
            fail("workflow_unavailable", "流程未启用或本机验证已失效")
        if (mode == WorkflowRunMode.EXECUTION && !request.allowExternalEffects && item.definition.steps.any { it.effect == WorkflowEffect.EXTERNAL })
            fail("effect_authorization_required", "此流程包含真实业务效果，本次请求尚未明确授权")
        val parameters = WorkflowCodec.parameters(item.definition.parameters, request.parameters)
        val bindings = WorkflowCodec.parameters(item.definition.bindings, item.bindings)
        // Resolve all references before accepting, including references in later branches/conditions.
        item.definition.steps.forEach {
            WorkflowCodec.resolve(it.arguments, parameters, bindings)
            it.before?.let { c -> WorkflowCodec.resolve(c, parameters, bindings) }
            it.after?.let { c -> WorkflowCodec.resolve(c, parameters, bindings) }
        }
        WorkflowCodec.resolve(item.definition.successCondition, parameters, bindings)
        val time = now()
        val source = request.sourceRunId?.let { ownedRun(it, callerId) }
        if (source == null && request.remainingStepIds.isNotEmpty())
            fail("invalid_recovery", "补做步骤必须明确关联原任务")
        if (source != null) validateRecovery(source, request, item, parameters, bindings)
        val id = newId()
        val run = WorkflowRun(id, request.requestId, callerId, deviceId, item.id,
            item.definition, item.digest, item.adaptationVersion, copy(bindings), copy(parameters),
            requestDigest(request, item.definition, item.adaptationVersion),
            if (mode == WorkflowRunMode.EXECUTION) item.validation?.environment else null, mode, request.allowExternalEffects,
            nextStep = source?.nextStep ?: 0,
            checkpoints = source?.checkpoints?.filter { it.state == WorkflowStepState.SUCCEEDED } ?: emptyList(),
            createdAt = time, updatedAt = time, businessRunId = source?.businessRunId ?: id, sourceRunId = source?.id)
        val previous = state.runs.map { if (it.id == source?.id) it.copy(successorRunId = id, updatedAt = time,
            stateVersion = it.stateVersion + 1) else it }
        // Claim the original business run and persist its successor in the same atomic ledger replacement.
        persist(state.copy(runs = previous + run))
        return copy(run)
    }

    private fun validateRecovery(source: WorkflowRun, request: WorkflowStartRequest, item: WorkflowInstallation,
        parameters: JsonObject, bindings: JsonObject) {
        if (source.state !in setOf(WorkflowRunState.FAILED, WorkflowRunState.STOPPED) ||
            source.checkpoints.any { it.state in setOf(WorkflowStepState.UNKNOWN, WorkflowStepState.PREPARED) })
            fail("recovery_unknown", "原任务仍在执行或包含待核实效果，不能领取补做")
        if (source.successorRunId != null) fail("recovery_claimed", "原任务已有补做任务：${source.successorRunId}")
        if (source.definition.id != item.definition.id || source.parameters != parameters || source.bindings != bindings)
            fail("recovery_mismatch", "补做必须保持原流程身份、业务参数和本机资源绑定")
        val completed = source.definition.steps.take(source.nextStep)
        if (item.definition.steps.take(source.nextStep) != completed)
            fail("recovery_mismatch", "新版已完成部分发生变化，无法确定安全承接位置")
        val remaining = item.definition.steps.drop(source.nextStep).map { it.id }
        if (remaining.isEmpty() || request.remainingStepIds != remaining)
            fail("recovery_mismatch", "须明确选择新版从安全起点开始的全部未完成步骤")
        if (request.adaptationVersion == null) fail("version_required", "补做须明确指定已验证的本机适配版本")
    }

    private fun schedule(runId: String) {
        synchronized(lock) {
            if (jobs[runId]?.isActive == true || state.runs.find { it.id == runId }?.state != WorkflowRunState.QUEUED) return
            val job = scope.launch(start = CoroutineStart.LAZY) { drive(runId) }
            jobs[runId] = job
            job.start()
        }
    }

    private suspend fun drive(runId: String) {
        val policy = ModelExecutionPolicy.fixed(runId) {
            synchronized(lock) { update(runId) { run -> run.copy(blockedModelCalls = run.blockedModelCalls + 1) } }
        }
        try {
            withContext(policy) {
                DeviceExecutionGate.withExclusive {
                    currentCoroutineContext().ensureActive()
                    val run = synchronized(lock) {
                        val current = record(runId)
                        if (current.state != WorkflowRunState.QUEUED) return@withExclusive
                        update(runId) { it.copy(state = WorkflowRunState.RUNNING, message = "正在检查本机环境") }
                    }
                    val environment = port.environment()
                    if (environment.fingerprint.isBlank()) fail("environment_unavailable", "无法核实本机兼容环境")
                    if ((run.mode == WorkflowRunMode.EXECUTION && run.environment == null) ||
                        (run.environment != null && run.environment.fingerprint != environment.fingerprint)) {
                        synchronized(lock) {
                            invalidateValidation(run)
                            update(runId) { it.copy(state = WorkflowRunState.FAILED, message = "本机兼容环境已改变，请重新验证后创建新任务") }
                        }
                        return@withExclusive
                    }
                    synchronized(lock) { update(runId) { it.copy(environment = environment) } }
                    try {
                        port.open(status(runId))
                        executeSteps(runId, environment)
                    } finally {
                        withContext(NonCancellable) { port.close(runId) }
                    }
                }
            }
        } catch (_: CancellationException) {
            synchronized(lock) { if (state.storageIssue == null) settleInterrupted(runId, stopped = true, message = "任务已停止，已发出的动作保留真实或待核实结果") }
        } catch (e: Exception) {
            synchronized(lock) {
                if (state.storageIssue == null) settleInterrupted(runId, stopped = false,
                    message = if (e is WorkflowException) e.message ?: e.code else "执行中断，请核对任务记录后处理")
            }
        } finally {
            val queuedAgain = synchronized(lock) {
                if (state.storageIssue == null && state.runs.find { it.id == runId }?.state == WorkflowRunState.STOPPING)
                    settleInterrupted(runId, stopped = true, message = "任务已停止，未完成的动作结果保留待核实")
                jobs.remove(runId)
                state.storageIssue == null && state.runs.find { it.id == runId }?.state == WorkflowRunState.QUEUED
            }
            // A reply can arrive while the old session is still closing. Do not strand that accepted continuation.
            if (queuedAgain) schedule(runId)
        }
    }

    private suspend fun executeSteps(runId: String, environment: WorkflowEnvironment) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val run = synchronized(lock) { copy(record(runId)) }
            if (run.state != WorkflowRunState.RUNNING) return
            val step = run.definition.steps.getOrNull(run.nextStep)
            if (step == null) {
                val finalCheck = port.check(WorkflowCodec.resolve(run.definition.successCondition, run.parameters, run.bindings))
                synchronized(lock) {
                    if (record(runId).state != WorkflowRunState.RUNNING) return
                    if (!finalCheck.matched) {
                        update(runId) { it.copy(state = WorkflowRunState.FAILED, message = "未满足业务成功条件：${finalCheck.message}") }
                    } else {
                        if (run.mode == WorkflowRunMode.VALIDATION) recordValidation(run, environment, true)
                        update(runId) { it.copy(state = WorkflowRunState.SUCCEEDED,
                            message = if (run.mode == WorkflowRunMode.VALIDATION) "本机验证通过，已执行范围见步骤记录" else "流程已达到声明的成功条件") }
                    }
                }
                return
            }
            step.before?.let { condition ->
                val before = port.check(WorkflowCodec.resolve(condition, run.parameters, run.bindings))
                if (!before.matched) {
                    synchronized(lock) { waitFor(runId, step.id, "当前页面条件不满足：${before.message}") }
                    return
                }
            }
            if (step.kind == WorkflowStepKind.WAIT_USER) {
                if (run.checkpoints.any { it.stepId == step.id && it.state == WorkflowStepState.SUCCEEDED }) {
                    // A resumed user reply is consumed only after the current environment and before condition were rechecked.
                    synchronized(lock) {
                        if (record(runId).state != WorkflowRunState.RUNNING) return
                        update(runId) { it.copy(nextStep = it.nextStep + 1) }
                    }
                    continue
                }
                val prompt = WorkflowCodec.resolve(JsonPrimitive(step.prompt!!), run.parameters, run.bindings).jsonPrimitive.content
                synchronized(lock) { waitFor(runId, step.id, prompt, advanceOnConfirm = true) }
                return
            }
            if (step.effect == WorkflowEffect.EXTERNAL && !run.allowExternalEffects) {
                synchronized(lock) {
                    if (record(runId).state != WorkflowRunState.RUNNING) return
                    if (run.mode == WorkflowRunMode.VALIDATION && run.nextStep == run.definition.steps.lastIndex) {
                        recordValidation(run, environment, false)
                        update(runId) { it.copy(state = WorkflowRunState.SUCCEEDED,
                            message = "提交前验证通过；真实业务效果未实测，未派发此动作",
                            checkpoints = checkpointList(it, step, WorkflowStepState.SKIPPED, "未授权真实业务效果，停在派发前")) }
                    } else if (run.mode == WorkflowRunMode.VALIDATION) {
                        update(runId) { it.copy(state = WorkflowRunState.FAILED,
                            message = "已到业务动作边界，后续路径尚未验证；须在明确授权范围内完成真实验证才能启用") }
                    } else {
                        update(runId) { it.copy(state = WorkflowRunState.FAILED, message = "本次请求未授权真实业务效果，未派发动作") }
                    }
                }
                return
            }
            val arguments = WorkflowCodec.resolve(step.arguments, run.parameters, run.bindings) as JsonObject
            synchronized(lock) {
                if (record(runId).state != WorkflowRunState.RUNNING) return
                update(runId) { it.copy(checkpoints = checkpointList(it, step, WorkflowStepState.PREPARED, "已记录派发检查点")) }
            }
            currentCoroutineContext().ensureActive()
            val result = port.execute(step, arguments, runId, run.allowExternalEffects)
            when {
                result.status == WorkflowActionStatus.UNKNOWN ||
                    (result.effectDispatched && result.status != WorkflowActionStatus.SUCCEEDED) -> {
                    synchronized(lock) {
                        update(runId) { it.copy(state = if (it.state == WorkflowRunState.STOPPING) WorkflowRunState.STOPPED else WorkflowRunState.UNKNOWN,
                            message = result.message.ifBlank { "业务效果待核实，不自动重做" }, waiting = null,
                            checkpoints = checkpointList(it, step, WorkflowStepState.UNKNOWN, result.message, result.evidence)) }
                    }
                    return
                }
                result.status == WorkflowActionStatus.WAITING_USER -> {
                    synchronized(lock) { waitFor(runId, step.id, result.prompt ?: result.message.ifBlank { "请处理本机条件后继续" }) }
                    return
                }
                result.status == WorkflowActionStatus.FAILED -> {
                    synchronized(lock) {
                        update(runId) { it.copy(state = if (it.state == WorkflowRunState.STOPPING) WorkflowRunState.STOPPED else WorkflowRunState.FAILED,
                            message = result.message, waiting = null,
                            checkpoints = checkpointList(it, step, WorkflowStepState.FAILED, result.message, result.evidence)) }
                    }
                    return
                }
                else -> {
                    val after = step.after?.let { port.check(WorkflowCodec.resolve(it, run.parameters, run.bindings)) }
                    synchronized(lock) {
                        val current = record(runId)
                        if (after?.matched == false) {
                            val uncertain = step.effect == WorkflowEffect.EXTERNAL || result.effectDispatched
                            update(runId) { it.copy(state = if (it.state == WorkflowRunState.STOPPING) WorkflowRunState.STOPPED
                                else if (uncertain) WorkflowRunState.UNKNOWN else WorkflowRunState.FAILED,
                                message = "动作后条件未满足：${after.message}",
                                checkpoints = checkpointList(it, step, if (uncertain) WorkflowStepState.UNKNOWN else WorkflowStepState.FAILED,
                                    after.message, after.evidence)) }
                            return
                        }
                        update(runId) { it.copy(nextStep = it.nextStep + 1,
                            state = if (current.state == WorkflowRunState.STOPPING) WorkflowRunState.STOPPED else it.state,
                            checkpoints = checkpointList(it, step, WorkflowStepState.SUCCEEDED, result.message,
                                after?.evidence ?: result.evidence)) }
                    }
                }
            }
        }
    }

    private fun waitFor(runId: String, stepId: String?, prompt: String, advanceOnConfirm: Boolean = false) {
        val old = record(runId)
        if (old.state != WorkflowRunState.RUNNING) return
        val step = old.definition.steps.find { it.id == stepId }
        update(runId) { it.copy(state = WorkflowRunState.WAITING_USER, message = prompt,
            waiting = WorkflowWait(newId(), prompt, stepId, it.stateVersion + 1, advanceOnConfirm),
            checkpoints = if (step != null) checkpointList(it, step, WorkflowStepState.WAITING_USER, prompt) else it.checkpoints) }
    }

    private fun stopLocked(old: WorkflowRun): WorkflowRun {
        if (old.state == WorkflowRunState.STOPPING) return old
        if (old.state == WorkflowRunState.UNKNOWN) return update(old.id) {
            it.copy(state = WorkflowRunState.STOPPED, waiting = null, message = "后续操作已停止，原业务结果仍待核实")
        }
        if (terminal(old.state)) return old
        val active = jobs[old.id]
        val draining = old.state == WorkflowRunState.RUNNING && active?.isActive == true
        val updated = update(old.id) { it.copy(state = if (draining) WorkflowRunState.STOPPING else WorkflowRunState.STOPPED,
            waiting = null, message = "已接受停止，不再派发后续动作") }
        if (!draining) jobs.remove(old.id)
        active?.cancel()
        return updated
    }

    private fun settleInterrupted(runId: String, stopped: Boolean, message: String) {
        val old = record(runId)
        if (terminal(old.state)) return
        val checkpoints = old.checkpoints.map {
            if (it.state == WorkflowStepState.PREPARED) it.copy(state = WorkflowStepState.UNKNOWN,
                message = "动作派发前已记账，但结果未完整保存；须核查，不自动重做", recordedAt = now()) else it
        }
        val unknown = checkpoints.any { it.state == WorkflowStepState.UNKNOWN }
        update(runId) { it.copy(state = if (stopped || it.state == WorkflowRunState.STOPPING) WorkflowRunState.STOPPED
            else if (unknown) WorkflowRunState.UNKNOWN else WorkflowRunState.FAILED,
            checkpoints = checkpoints, waiting = null, message = message) }
    }

    private fun recordValidation(run: WorkflowRun, environment: WorkflowEnvironment, effectsVerified: Boolean) {
        val item = state.installations.find { it.id == run.installationId } ?: return
        if (item.digest != run.definitionDigest || item.adaptationVersion != run.adaptationVersion) return
        val report = WorkflowValidation(run.definitionDigest, run.adaptationVersion, environment,
            run.definition.steps.map { it.id }, effectsVerified, now(), run.id)
        val updated = item.copy(validation = report)
        persist(state.copy(installations = state.installations.map { if (it.id == item.id) updated else it }))
    }

    private fun invalidateValidation(run: WorkflowRun) {
        val item = state.installations.find { it.id == run.installationId } ?: return
        if (item.digest == run.definitionDigest && item.adaptationVersion == run.adaptationVersion)
            persist(state.copy(installations = state.installations.map { if (it.id == item.id) it.copy(enabled = false, validation = null) else it }))
    }

    private fun checkpointList(run: WorkflowRun, step: WorkflowStep, status: WorkflowStepState,
        message: String, evidence: JsonObject = JsonObject(emptyMap())): List<WorkflowCheckpoint> =
        run.checkpoints.filterNot { it.stepId == step.id } + WorkflowCheckpoint(step.id, status, step.effect, message, evidence, now())

    private fun recover(loaded: WorkflowSnapshot): WorkflowSnapshot {
        if (loaded.formatVersion != 1 || loaded.storageIssue != null) fail("storage_corrupt", "流程存储格式错误，原文件已保留")
        if (loaded.installations.map { it.id }.distinct().size != loaded.installations.size ||
            loaded.runs.map { it.id }.distinct().size != loaded.runs.size ||
            loaded.runs.map { Triple(it.callerId, it.deviceId, it.requestId) }.distinct().size != loaded.runs.size)
            fail("storage_corrupt", "流程存储存在重复标识，原文件已保留")
        loaded.installations.forEach { if (WorkflowCodec.digest(it.definition) != it.digest) fail("storage_corrupt", "保存的流程摘要不匹配，原文件已保留") }
        loaded.runs.forEach { run ->
            if (WorkflowCodec.digest(run.definition) != run.definitionDigest || run.nextStep !in 0..run.definition.steps.size ||
                run.checkpoints.map { it.stepId }.distinct().size != run.checkpoints.size ||
                run.checkpoints.any { checkpoint -> run.definition.steps.none { it.id == checkpoint.stepId } })
                fail("storage_corrupt", "任务快照或检查点不一致，原文件已保留")
        }
        val updated = loaded.copy(runs = loaded.runs.map { run ->
            if (terminal(run.state) || run.state == WorkflowRunState.WAITING_USER || run.state == WorkflowRunState.PAUSED) run
            else {
                val pending = run.checkpoints.map { if (it.state == WorkflowStepState.PREPARED)
                    it.copy(state = WorkflowStepState.UNKNOWN, message = "上次进程中断，动作结果待核实", recordedAt = now()) else it }
                val unknown = pending.any { it.state == WorkflowStepState.UNKNOWN }
                val stopping = run.state == WorkflowRunState.STOPPING
                run.copy(state = if (stopping) WorkflowRunState.STOPPED else if (unknown) WorkflowRunState.UNKNOWN else WorkflowRunState.PAUSED,
                    checkpoints = pending, stateVersion = run.stateVersion + 1, updatedAt = now(),
                    waiting = if (unknown || stopping) null else WorkflowWait(newId(), "上次运行已中断，继续前重新核对本机环境", null, run.stateVersion + 1),
                    message = if (unknown) "上次业务结果待核实，不自动重做" else "上次运行已暂停，未自动重放任何动作")
            }
        })
        if (updated != loaded) store.save(updated)
        return updated
    }

    private fun validated(item: WorkflowInstallation): Boolean = item.validation?.let {
        it.definitionDigest == item.digest && it.adaptationVersion == item.adaptationVersion &&
            it.verifiedStepIds.toSet() == item.definition.steps.map { step -> step.id }.toSet()
    } == true

    private fun update(runId: String, transform: (WorkflowRun) -> WorkflowRun): WorkflowRun {
        val old = record(runId)
        val updated = transform(old).copy(stateVersion = old.stateVersion + 1, updatedAt = now())
        persist(state.copy(runs = state.runs.map { if (it.id == runId) updated else it }))
        return updated
    }

    private fun persist(updated: WorkflowSnapshot) {
        ensureStorage()
        try { store.save(updated) }
        catch (e: Exception) {
            state = state.copy(storageIssue = "任务账本保存失败，已停止继续派发；请保留原数据并处理存储问题")
            jobs.values.forEach { it.cancel() }
            throw WorkflowException("storage_write_failed", state.storageIssue!!)
        }
        state = updated
    }
    private fun ensureStorage() { state.storageIssue?.let { fail("storage_unavailable", it) } }
    private fun installation(id: String) = state.installations.find { it.id == id } ?: fail("not_found", "没有找到此流程安装记录")
    private fun record(id: String) = state.runs.find { it.id == id } ?: fail("not_found", "没有找到此任务")
    private fun ownedRun(id: String, caller: String): WorkflowRun {
        val run = record(id)
        if (run.deviceId != deviceId || (caller != "local" && run.callerId != caller)) fail("not_authorized", "无权访问此任务")
        return run
    }

    private fun requestDigest(request: WorkflowStartRequest, definition: WorkflowDefinition, adaptationVersion: Int): String =
        WorkflowCodec.digest(WorkflowCodec.json.encodeToJsonElement(request.copy(
            parameters = WorkflowCodec.parameters(definition.parameters, request.parameters),
            adaptationVersion = request.adaptationVersion ?: adaptationVersion,
        )))
    private fun terminal(state: WorkflowRunState) = state in setOf(WorkflowRunState.SUCCEEDED, WorkflowRunState.FAILED, WorkflowRunState.STOPPED, WorkflowRunState.UNKNOWN)
    private inline fun <reified T> copy(value: T): T = WorkflowCodec.json.decodeFromString(WorkflowCodec.json.encodeToString(value))
    private fun fail(code: String, message: String): Nothing = throw WorkflowException(code, message)
}
