package com.wangzan101.mika.core.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Portable template only. Device bindings, validation and task data never enter a shared package. */
@Serializable
data class WorkflowDefinition(
    val formatVersion: Int = 1,
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val parameters: List<WorkflowParameter> = emptyList(),
    val bindings: List<WorkflowParameter> = emptyList(),
    val steps: List<WorkflowStep>,
    val successCondition: WorkflowCondition,
)

@Serializable
enum class WorkflowValueType { STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY }

@Serializable
data class WorkflowParameter(
    val name: String,
    val type: WorkflowValueType = WorkflowValueType.STRING,
    val required: Boolean = true,
    val description: String = "",
    val defaultValue: JsonElement? = null,
)

@Serializable
enum class WorkflowStepKind { ACTION, WAIT_USER }

@Serializable
enum class WorkflowEffect { READ_ONLY, LOCAL, EXTERNAL }

@Serializable
data class WorkflowStep(
    val id: String,
    val title: String,
    val kind: WorkflowStepKind = WorkflowStepKind.ACTION,
    val tool: String = "",
    val arguments: JsonObject = JsonObject(emptyMap()),
    val effect: WorkflowEffect = WorkflowEffect.READ_ONLY,
    val before: WorkflowCondition? = null,
    val after: WorkflowCondition? = null,
    val prompt: String? = null,
)

/** All specified predicates must match. Conditions are observed locally, never judged by a model. */
@Serializable
data class WorkflowCondition(
    val appPackage: String? = null,
    val textPresent: String? = null,
    val textAbsent: String? = null,
)

@Serializable
data class WorkflowPackage(
    val formatVersion: Int = 1,
    val definition: WorkflowDefinition,
    val digest: String,
)

@Serializable
data class WorkflowEnvironment(
    val fingerprint: String,
    val deviceLabel: String = "",
    val appVersions: Map<String, String> = emptyMap(),
)

data class WorkflowCheck(
    val matched: Boolean,
    val message: String = "",
    val evidence: JsonObject = JsonObject(emptyMap()),
)

enum class WorkflowActionStatus { SUCCEEDED, FAILED, UNKNOWN, WAITING_USER }

data class WorkflowActionResult(
    val status: WorkflowActionStatus,
    val message: String = "",
    val evidence: JsonObject = JsonObject(emptyMap()),
    /** True when a business effect was sent, or cannot reliably be ruled out. */
    val effectDispatched: Boolean = false,
    val prompt: String? = null,
)

/** Android supplies the existing native ActionExecutor, current screen checks and environment. */
interface WorkflowActionPort {
    val supportedTools: Set<String>
    suspend fun open(run: WorkflowRun) {}
    suspend fun close(runId: String) {}
    suspend fun environment(): WorkflowEnvironment
    suspend fun check(condition: WorkflowCondition): WorkflowCheck
    suspend fun execute(
        step: WorkflowStep,
        arguments: JsonObject,
        runId: String,
        allowExternalEffects: Boolean,
    ): WorkflowActionResult
}

@Serializable
data class WorkflowValidation(
    val definitionDigest: String,
    val adaptationVersion: Int,
    val environment: WorkflowEnvironment,
    val verifiedStepIds: List<String>,
    val externalEffectsVerified: Boolean,
    val validatedAt: Long,
    val runId: String,
)

@Serializable
data class WorkflowInstallation(
    val id: String,
    val definition: WorkflowDefinition,
    val digest: String,
    val bindings: JsonObject = JsonObject(emptyMap()),
    val adaptationVersion: Int = 1,
    val enabled: Boolean = false,
    val validation: WorkflowValidation? = null,
    val importedAt: Long,
)

@Serializable
enum class WorkflowRunMode { VALIDATION, EXECUTION }

@Serializable
enum class WorkflowRunState {
    QUEUED, RUNNING, WAITING_USER, PAUSED, UNKNOWN, STOPPING, SUCCEEDED, FAILED, STOPPED,
}

@Serializable
enum class WorkflowStepState { PREPARED, SUCCEEDED, FAILED, UNKNOWN, WAITING_USER, SKIPPED }

@Serializable
data class WorkflowCheckpoint(
    val stepId: String,
    val state: WorkflowStepState,
    val effect: WorkflowEffect,
    val message: String = "",
    val evidence: JsonObject = JsonObject(emptyMap()),
    val recordedAt: Long,
)

@Serializable
data class WorkflowWait(
    val id: String,
    val prompt: String,
    val stepId: String?,
    val stateVersion: Long,
    /** Conditions and recovery prompts only retry checks; only an actually displayed user step can be consumed. */
    val advanceOnConfirm: Boolean = false,
)

@Serializable
data class WorkflowStartRequest(
    val installationId: String,
    val requestId: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
    /** Explicit immutable version; mismatches never silently run the currently selected version. */
    val workflowVersion: String,
    val adaptationVersion: Int? = null,
    val allowExternalEffects: Boolean = false,
    /** An explicit repair continuation. Completed effects remain owned by the original business run. */
    val sourceRunId: String? = null,
    val remainingStepIds: List<String> = emptyList(),
)

@Serializable
data class WorkflowRun(
    val id: String,
    val requestId: String,
    val callerId: String,
    val deviceId: String,
    val installationId: String,
    val definition: WorkflowDefinition,
    val definitionDigest: String,
    val adaptationVersion: Int,
    val bindings: JsonObject,
    val parameters: JsonObject,
    val requestDigest: String,
    val environment: WorkflowEnvironment?,
    val mode: WorkflowRunMode,
    val allowExternalEffects: Boolean,
    val state: WorkflowRunState = WorkflowRunState.QUEUED,
    val stateVersion: Long = 1,
    val nextStep: Int = 0,
    val checkpoints: List<WorkflowCheckpoint> = emptyList(),
    val waiting: WorkflowWait? = null,
    val message: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val blockedModelCalls: Int = 0,
    val actualModelCalls: Int = 0,
    val businessRunId: String = id,
    val sourceRunId: String? = null,
    val successorRunId: String? = null,
)

@Serializable
data class WorkflowSnapshot(
    val formatVersion: Int = 1,
    val installations: List<WorkflowInstallation> = emptyList(),
    val runs: List<WorkflowRun> = emptyList(),
    val storageIssue: String? = null,
)

interface WorkflowStore {
    fun load(): WorkflowSnapshot
    /** Must replace the complete snapshot atomically or fail without acknowledging it. */
    fun save(snapshot: WorkflowSnapshot)
}

class WorkflowException(val code: String, message: String) : IllegalStateException(message)

