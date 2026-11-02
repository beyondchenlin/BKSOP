package com.wangzan101.mika.workflow

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.workflow.WorkflowCodec
import com.wangzan101.mika.core.workflow.WorkflowCondition
import com.wangzan101.mika.core.workflow.WorkflowDefinition
import com.wangzan101.mika.core.workflow.WorkflowEffect
import com.wangzan101.mika.core.workflow.WorkflowException
import com.wangzan101.mika.core.workflow.WorkflowInstallation
import com.wangzan101.mika.core.workflow.WorkflowParameter
import com.wangzan101.mika.core.workflow.WorkflowRun
import com.wangzan101.mika.core.workflow.WorkflowRunMode
import com.wangzan101.mika.core.workflow.WorkflowRunState
import com.wangzan101.mika.core.workflow.WorkflowSnapshot
import com.wangzan101.mika.core.workflow.WorkflowStartRequest
import com.wangzan101.mika.core.workflow.WorkflowStep
import com.wangzan101.mika.core.workflow.WorkflowStepState
import com.wangzan101.mika.core.workflow.WorkflowValueType
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phone-local management for the same durable service exposed to MCP.
 * Import never executes; validation, enabling and execution are distinct actions.
 */
@Composable
fun WorkflowScreen(host: AndroidWorkflows, onBack: () -> Unit) {
    val context = LocalContext.current
    val service = host.service
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(WorkflowSnapshot()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    // A picker rotation must not manufacture a fresh business request.
    var submittedRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<String?>(null) }
    var editorInitial by remember { mutableStateOf<String?>(null) }
    var discardEditor by remember { mutableStateOf(false) }
    val formStates = rememberSaveableStateHolder()

    suspend fun refresh() {
        snapshot = withContext(Dispatchers.IO) { service.snapshot() }
        loaded = true
    }
    fun act(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                operation()
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = context.getString(R.string.workflow_error, failure.message ?: failure.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }
    fun leaveEditor() {
        if (editor != editorInitial) discardEditor = true
        else { editor = null; editorInitial = null }
    }
    fun back() {
        when {
            editor != null -> leaveEditor()
            selectedRunId != null -> selectedRunId = null
            selectedId != null -> selectedId = null
            else -> onBack()
        }
    }
    BackHandler(onBack = ::back)

    LaunchedEffect(service) {
        while (isActive) {
            try { refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = context.getString(R.string.workflow_error, failure.message ?: failure.javaClass.simpleName)
            }
            delay(1_500)
        }
    }

    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act {
            val installation = withContext(Dispatchers.IO) {
                val text = context.contentResolver.openInputStream(uri)?.use(::readWorkflowDocument)
                    ?: throw WorkflowException("document_unreadable", context.getString(R.string.workflow_document_unreadable))
                service.importPackage(text)
            }
            selectedId = installation.id
            selectedRunId = null
            submittedRequest = null
            notice = context.getString(R.string.workflow_imported)
        }
    }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        if (uri != null && id != null) act {
            withContext(Dispatchers.IO) {
                val text = service.exportPackage(id)
                val output = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw WorkflowException("document_unwritable", context.getString(R.string.workflow_document_unwritable))
                output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            }
            notice = context.getString(R.string.workflow_exported)
            exportId = null
        }
    }
    val installation = snapshot.installations.firstOrNull { it.id == selectedId }
    val run = snapshot.runs.firstOrNull { it.id == selectedRunId }
    val currentEditor = editor

    KeyboardDismissArea {
        PageScaffold(stringResource(R.string.workflow_title), ::back, Modifier.testTag("workflows-page")) {
            if (!loaded) Text(stringResource(R.string.workflow_loading), style = MikaType.Body, color = MikaTheme.colors.ink2)
            snapshot.storageIssue?.let { WorkflowNotice(stringResource(R.string.workflow_storage_problem, it), true) }
            error?.let { WorkflowNotice(it, true) }
            notice?.let { WorkflowNotice(it, false) }
            if (busy) WorkflowNotice(stringResource(R.string.workflow_working), false)

            when {
                currentEditor != null -> {
                    GroupLabel(stringResource(R.string.workflow_editor))
                    Text(stringResource(R.string.workflow_editor_help), style = MikaType.Caption, color = MikaTheme.colors.ink2)
                    OutlinedTextField(
                        currentEditor, { editor = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("workflow-definition"),
                        label = { Text(stringResource(R.string.workflow_definition)) },
                        enabled = !busy, minLines = 12, maxLines = 22,
                    )
                    WorkflowButtons(
                        stringResource(R.string.workflow_save_version), {
                            act {
                                val saved = withContext(Dispatchers.IO) {
                                    val definition = WorkflowCodec.json.decodeFromString<WorkflowDefinition>(currentEditor)
                                    service.saveDefinition(definition)
                                }
                                selectedId = saved.id
                                submittedRequest = null
                                editor = null
                                editorInitial = null
                                notice = context.getString(R.string.workflow_saved)
                            }
                        },
                        stringResource(R.string.cancel), ::leaveEditor, enabled = !busy,
                    )
                }
                run != null -> WorkflowRunDetails(
                    run, busy,
                    onResume = {
                        val waiting = run.waiting
                        if (waiting != null) act {
                            withContext(Dispatchers.IO) {
                                service.resume(run.id, waiting.id, waiting.stateVersion, JsonPrimitive(true))
                            }
                        }
                    },
                    onStop = { act { withContext(Dispatchers.IO) { service.cancel(run.id) } } },
                )
                installation != null -> formStates.SaveableStateProvider(installation.id) { WorkflowInstallationDetails(
                    installation, busy, submittedRequest,
                    activeRuns = snapshot.runs.filter { it.installationId == installation.id && it.state !in terminalRunStates },
                    remoteAllowed = host.isRemoteAllowed(installation.id),
                    onRemoteAllowed = { allowed -> act { withContext(Dispatchers.IO) { host.setRemoteAllowed(installation.id, allowed) } } },
                    onBindings = { bindings ->
                        act {
                            withContext(Dispatchers.IO) { service.bind(installation.id, bindings) }
                            notice = context.getString(R.string.workflow_bindings_saved)
                        }
                    },
                    onEnabled = { enabled -> act { withContext(Dispatchers.IO) { service.setEnabled(installation.id, enabled) } } },
                    onValidate = { parameters ->
                        act {
                            val started = withContext(Dispatchers.IO) { service.startValidation(installation.id, parameters) }
                            selectedRunId = started.id
                        }
                    },
                    onRun = { parameters, allowEffects ->
                        act {
                            val request = submittedRequest?.let { WorkflowCodec.json.decodeFromString<WorkflowStartRequest>(it) }
                                ?: WorkflowStartRequest(
                                    installationId = installation.id,
                                    requestId = UUID.randomUUID().toString(),
                                    parameters = parameters,
                                    workflowVersion = installation.definition.version,
                                    adaptationVersion = installation.adaptationVersion,
                                    allowExternalEffects = allowEffects,
                                ).also { submittedRequest = WorkflowCodec.json.encodeToString(it) }
                            if (request.parameters != parameters || request.installationId != installation.id ||
                                request.workflowVersion != installation.definition.version ||
                                request.adaptationVersion != installation.adaptationVersion ||
                                request.allowExternalEffects != allowEffects) {
                                throw WorkflowException("request_changed", context.getString(R.string.workflow_request_changed))
                            }
                            val started = withContext(Dispatchers.IO) { service.start(request) }
                            selectedRunId = started.id
                        }
                    },
                    onFindRequest = {
                        act {
                            val request = submittedRequest?.let { WorkflowCodec.json.decodeFromString<WorkflowStartRequest>(it) }
                            val found = request?.let { withContext(Dispatchers.IO) { service.findByRequest(it.requestId) } }
                            if (found == null) notice = context.getString(R.string.workflow_request_not_found)
                            else selectedRunId = found.id
                        }
                    },
                    onNewRequest = { submittedRequest = null },
                    onOpenRun = { selectedRunId = it },
                    onExport = {
                        exportId = installation.id
                        exportDocument.launch("bksop-workflow-" + installation.definition.version + ".json")
                    },
                    onEdit = {
                        val versions = snapshot.installations.filter { it.definition.id == installation.definition.id }
                            .map { it.definition.version }.toSet()
                        val copy = installation.definition.copy(version = nextWorkflowVersion(installation.definition.version, versions))
                        editor = WorkflowCodec.json.encodeToString(copy)
                        editorInitial = editor
                    },
                    onFailure = { failure ->
                        error = context.getString(R.string.workflow_error, failure.message ?: failure.javaClass.simpleName)
                    },
                ) }
                else -> {
                    Text(stringResource(R.string.workflow_intro), style = MikaType.Body, color = MikaTheme.colors.ink2)
                    WorkflowButtons(
                        stringResource(R.string.workflow_new), {
                            editor = starterWorkflow(context)
                            editorInitial = editor
                        },
                        stringResource(R.string.workflow_import), { importDocument.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        enabled = !busy,
                    )
                    GroupLabel(stringResource(R.string.workflow_saved_flows))
                    if (snapshot.installations.isEmpty()) WorkflowNotice(stringResource(R.string.workflow_empty), false)
                    snapshot.installations.forEach { item ->
                        GroupCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.definition.name, style = MikaType.Title, color = MikaTheme.colors.ink)
                                Text(item.definition.description, style = MikaType.Caption, color = MikaTheme.colors.ink2)
                                Text(stringResource(R.string.workflow_version, item.definition.version, item.adaptationVersion), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                WorkflowInstallationPill(item)
                                MikaButton(
                                    stringResource(R.string.workflow_open), {
                                        selectedId = item.id
                                        submittedRequest = null
                                        notice = null
                                        error = null
                                    },
                                    Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = !busy,
                                )
                            }
                        }
                    }
                    GroupLabel(stringResource(R.string.workflow_runs))
                    if (snapshot.runs.isEmpty()) WorkflowNotice(stringResource(R.string.workflow_no_runs), false)
                    snapshot.runs.sortedByDescending { it.createdAt }.forEach { item ->
                        GroupCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.definition.name, style = MikaType.Body, color = MikaTheme.colors.ink)
                                Text(stringResource(R.string.workflow_request_id, item.requestId), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                WorkflowRunPill(item)
                                MikaButton(
                                    stringResource(R.string.workflow_view_run), { selectedRunId = item.id },
                                    Modifier.fillMaxWidth(), kind = ButtonKind.Text, size = ButtonSize.Medium, enabled = !busy,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (discardEditor) AlertDialog(
        onDismissRequest = { discardEditor = false },
        title = { Text(stringResource(R.string.workflow_discard_title)) },
        text = { Text(stringResource(R.string.workflow_discard_body)) },
        confirmButton = { TextButton(onClick = {
            editor = null
            editorInitial = null
            discardEditor = false
        }) { Text(stringResource(R.string.workflow_discard)) } },
        dismissButton = { TextButton(onClick = { discardEditor = false }) { Text(stringResource(R.string.workflow_keep_editing)) } },
    )
}

@Composable
private fun WorkflowInstallationDetails(
    item: WorkflowInstallation,
    busy: Boolean,
    submittedRequest: String?,
    activeRuns: List<WorkflowRun>,
    remoteAllowed: Boolean,
    onRemoteAllowed: (Boolean) -> Unit,
    onBindings: (JsonObject) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onValidate: (JsonObject) -> Unit,
    onRun: (JsonObject, Boolean) -> Unit,
    onFindRequest: () -> Unit,
    onNewRequest: () -> Unit,
    onOpenRun: (String) -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    var parameters by rememberSaveable(item.id) { mutableStateOf(HashMap(workflowFieldValues(item.definition.parameters))) }
    var bindings by rememberSaveable(item.id, item.adaptationVersion) { mutableStateOf(HashMap(workflowFieldValues(item.definition.bindings, item.bindings))) }
    var allowEffects by rememberSaveable(item.id) { mutableStateOf(false) }
    val needsEffects = item.definition.steps.any { it.effect == WorkflowEffect.EXTERNAL }
    fun useValues(block: (JsonObject) -> Unit) {
        try { block(workflowInputValues(item.definition.parameters, parameters)) }
        catch (failure: Exception) { onFailure(failure) }
    }
    GroupLabel(item.definition.name)
    Text(item.definition.description, style = MikaType.Body, color = MikaTheme.colors.ink2)
    Text(stringResource(R.string.workflow_version, item.definition.version, item.adaptationVersion), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    WorkflowInstallationPill(item)
    Text(stringResource(R.string.workflow_digest, item.digest), Modifier.padding(vertical = 8.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    WorkflowButtons(stringResource(R.string.workflow_edit_copy), onEdit, stringResource(R.string.workflow_export), onExport, !busy)

    GroupLabel(stringResource(R.string.workflow_bindings))
    Text(stringResource(R.string.workflow_bindings_help), style = MikaType.Caption, color = MikaTheme.colors.ink2)
    WorkflowFields(item.definition.bindings, bindings, { name, value -> bindings = HashMap(bindings + (name to value)) }, !busy, "binding")
    if (item.definition.bindings.isNotEmpty()) MikaButton(
        stringResource(R.string.workflow_save_bindings), {
            try { onBindings(workflowInputValues(item.definition.bindings, bindings)) }
            catch (failure: Exception) { onFailure(failure) }
        },
        Modifier.fillMaxWidth().padding(vertical = 8.dp), kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = !busy,
    )
    GroupLabel(stringResource(R.string.workflow_parameters))
    WorkflowFields(item.definition.parameters, parameters, { name, value -> parameters = HashMap(parameters + (name to value)) }, !busy, "parameter")

    GroupLabel(stringResource(R.string.workflow_validation))
    Text(stringResource(R.string.workflow_validation_help), style = MikaType.Caption, color = MikaTheme.colors.ink2)
    item.validation?.let { validation ->
        Text(
            stringResource(R.string.workflow_validation_environment, validation.environment.deviceLabel),
            Modifier.padding(top = 8.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3,
        )
        Text(
            stringResource(if (validation.externalEffectsVerified) R.string.workflow_effects_verified else R.string.workflow_effects_not_verified),
            style = MikaType.Caption, color = MikaTheme.colors.ink2,
        )
    }
    MikaButton(
        stringResource(R.string.workflow_validate), { useValues(onValidate) },
        Modifier.fillMaxWidth().padding(vertical = 10.dp), kind = ButtonKind.Secondary, size = ButtonSize.Medium,
        enabled = !busy && activeRuns.isEmpty(),
    )
    WorkflowButtons(
        stringResource(if (item.enabled) R.string.workflow_disable else R.string.workflow_enable),
        { onEnabled(!item.enabled) },
        stringResource(R.string.workflow_run),
        { useValues { onRun(it, needsEffects && allowEffects) } },
        enabled = !busy, firstEnabled = item.enabled || item.validation != null,
        secondEnabled = item.enabled && activeRuns.isEmpty() && (!needsEffects || allowEffects),
    )
    if (needsEffects) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MikaSwitch(allowEffects, { allowEffects = it }, enabled = !busy)
            Text(stringResource(R.string.workflow_allow_effects), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MikaSwitch(remoteAllowed, onRemoteAllowed, enabled = !busy)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.workflow_remote_allowed), style = MikaType.Body, color = MikaTheme.colors.ink)
            Text(stringResource(R.string.workflow_remote_allowed_help), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        }
    }
    if (submittedRequest != null) {
        WorkflowNotice(stringResource(R.string.workflow_request_retained), false)
        WorkflowButtons(
            stringResource(R.string.workflow_find_request), onFindRequest,
            stringResource(R.string.workflow_new_request), { allowEffects = false; onNewRequest() },
            enabled = !busy,
        )
    }
    activeRuns.forEach { active ->
        MikaButton(
            stringResource(R.string.workflow_active_run), { onOpenRun(active.id) },
            Modifier.fillMaxWidth().padding(vertical = 6.dp), kind = ButtonKind.Text, size = ButtonSize.Medium,
        )
    }
    GroupLabel(stringResource(R.string.workflow_steps))
    item.definition.steps.forEachIndexed { index, step ->
        Text(
            stringResource(R.string.workflow_step, index + 1, step.title),
            Modifier.padding(vertical = 6.dp), style = MikaType.Body, color = MikaTheme.colors.ink,
        )
        Text(stringResource(effectLabel(step.effect)), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    }
}

@Composable
private fun WorkflowRunDetails(run: WorkflowRun, busy: Boolean, onResume: () -> Unit, onStop: () -> Unit) {
    GroupLabel(run.definition.name)
    Text(stringResource(if (run.mode == WorkflowRunMode.VALIDATION) R.string.workflow_validation else R.string.workflow_execution),
        style = MikaType.Caption, color = MikaTheme.colors.ink2)
    WorkflowRunPill(run)
    Text(stringResource(R.string.workflow_request_id, run.requestId), Modifier.padding(vertical = 8.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    Text(stringResource(R.string.workflow_run_id, run.id), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    Text(stringResource(R.string.workflow_version, run.definition.version, run.adaptationVersion), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    Text(stringResource(R.string.workflow_model_calls, run.actualModelCalls, run.blockedModelCalls),
        Modifier.padding(vertical = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
    if (run.message.isNotBlank()) WorkflowNotice(run.message, run.state in setOf(WorkflowRunState.FAILED, WorkflowRunState.UNKNOWN))
    run.waiting?.let { waiting ->
        WorkflowNotice(waiting.prompt, false)
        MikaButton(stringResource(R.string.workflow_resume), onResume, Modifier.fillMaxWidth().padding(vertical = 8.dp),
            size = ButtonSize.Medium, enabled = !busy && run.state !in terminalRunStates)
    }
    if (run.state !in terminalRunStates) MikaButton(
        stringResource(R.string.workflow_stop), onStop, Modifier.fillMaxWidth().padding(vertical = 8.dp),
        kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = !busy && run.state != WorkflowRunState.STOPPING,
    )
    if (run.state == WorkflowRunState.UNKNOWN || run.checkpoints.any { it.state == WorkflowStepState.UNKNOWN })
        WorkflowNotice(stringResource(R.string.workflow_unknown_help), true)
    if (run.mode == WorkflowRunMode.VALIDATION && run.state == WorkflowRunState.SUCCEEDED)
        WorkflowNotice(stringResource(R.string.workflow_validation_complete), false)
    GroupLabel(stringResource(R.string.workflow_step_results))
    if (run.checkpoints.isEmpty()) WorkflowNotice(stringResource(R.string.workflow_no_steps_yet), false)
    run.checkpoints.forEach { checkpoint ->
        val title = run.definition.steps.firstOrNull { it.id == checkpoint.stepId }?.title ?: checkpoint.stepId
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MikaType.Body, color = MikaTheme.colors.ink)
                Text(stringResource(stepStateLabel(checkpoint.state)), style = MikaType.Caption, color = MikaTheme.colors.ink2)
                if (checkpoint.message.isNotBlank()) Text(checkpoint.message, style = MikaType.Caption, color = MikaTheme.colors.ink2)
                if (checkpoint.evidence.isNotEmpty()) {
                    var expanded by remember(run.id, checkpoint.stepId) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.workflow_evidence)) }
                    if (expanded) Text(checkpoint.evidence.toString(), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                }
            }
        }
    }
}

@Composable
private fun WorkflowFields(
    specs: List<WorkflowParameter>, fields: Map<String, String>,
    onChange: (String, String) -> Unit, enabled: Boolean, tag: String,
) {
    if (specs.isEmpty()) Text(stringResource(R.string.workflow_no_fields), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    specs.forEach { spec ->
        val type = stringResource(valueTypeLabel(spec.type))
        OutlinedTextField(
            fields[spec.name].orEmpty(), { onChange(spec.name, it) },
            Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("workflow-" + tag + "-" + spec.name),
            label = { Text(stringResource(if (spec.required) R.string.workflow_required_field else R.string.workflow_optional_field, spec.name, type)) },
            supportingText = if (spec.description.isNotBlank()) ({ Text(spec.description) }) else null,
            enabled = enabled, minLines = if (spec.type in setOf(WorkflowValueType.OBJECT, WorkflowValueType.ARRAY)) 3 else 1,
            maxLines = 6,
        )
    }
}

@Composable
private fun WorkflowButtons(
    first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit,
    enabled: Boolean = true, firstEnabled: Boolean = true, secondEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaButton(first, onFirst, Modifier.weight(1f), kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = enabled && firstEnabled)
        MikaButton(second, onSecond, Modifier.weight(1f), kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = enabled && secondEnabled)
    }
}

@Composable
private fun WorkflowNotice(message: String, warning: Boolean) {
    Text(message, Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MikaType.Caption,
        color = if (warning) MikaTheme.colors.warn else MikaTheme.colors.ink2)
}

@Composable
private fun WorkflowInstallationPill(item: WorkflowInstallation) {
    StatusPill(
        stringResource(when { item.enabled -> R.string.workflow_enabled; item.validation != null -> R.string.workflow_disabled; else -> R.string.workflow_unverified }),
        when { item.enabled -> PillTone.Ok; item.validation != null -> PillTone.Neutral; else -> PillTone.Warn },
    )
}

@Composable
private fun WorkflowRunPill(run: WorkflowRun) {
    StatusPill(stringResource(runStateLabel(run.state)), when (run.state) {
        WorkflowRunState.SUCCEEDED -> PillTone.Ok
        WorkflowRunState.FAILED, WorkflowRunState.UNKNOWN, WorkflowRunState.WAITING_USER, WorkflowRunState.PAUSED -> PillTone.Warn
        WorkflowRunState.RUNNING, WorkflowRunState.QUEUED, WorkflowRunState.STOPPING -> PillTone.Accent
        WorkflowRunState.STOPPED -> PillTone.Neutral
    })
}

private val terminalRunStates = setOf(WorkflowRunState.SUCCEEDED, WorkflowRunState.FAILED, WorkflowRunState.STOPPED)

private fun runStateLabel(state: WorkflowRunState): Int = when (state) {
    WorkflowRunState.QUEUED -> R.string.workflow_state_queued
    WorkflowRunState.RUNNING -> R.string.workflow_state_running
    WorkflowRunState.WAITING_USER -> R.string.workflow_state_waiting
    WorkflowRunState.PAUSED -> R.string.workflow_state_paused
    WorkflowRunState.UNKNOWN -> R.string.workflow_state_unknown
    WorkflowRunState.STOPPING -> R.string.workflow_state_stopping
    WorkflowRunState.SUCCEEDED -> R.string.workflow_state_succeeded
    WorkflowRunState.FAILED -> R.string.workflow_state_failed
    WorkflowRunState.STOPPED -> R.string.workflow_state_stopped
}

private fun stepStateLabel(state: WorkflowStepState): Int = when (state) {
    WorkflowStepState.PREPARED -> R.string.workflow_step_prepared
    WorkflowStepState.SUCCEEDED -> R.string.workflow_state_succeeded
    WorkflowStepState.FAILED -> R.string.workflow_state_failed
    WorkflowStepState.UNKNOWN -> R.string.workflow_state_unknown
    WorkflowStepState.WAITING_USER -> R.string.workflow_state_waiting
    WorkflowStepState.SKIPPED -> R.string.workflow_step_skipped
}

private fun effectLabel(effect: WorkflowEffect): Int = when (effect) {
    WorkflowEffect.READ_ONLY -> R.string.workflow_effect_read
    WorkflowEffect.LOCAL -> R.string.workflow_effect_local
    WorkflowEffect.EXTERNAL -> R.string.workflow_effect_external
}

private fun valueTypeLabel(type: WorkflowValueType): Int = when (type) {
    WorkflowValueType.STRING -> R.string.workflow_type_text
    WorkflowValueType.INTEGER -> R.string.workflow_type_integer
    WorkflowValueType.NUMBER -> R.string.workflow_type_number
    WorkflowValueType.BOOLEAN -> R.string.workflow_type_boolean
    WorkflowValueType.OBJECT -> R.string.workflow_type_object
    WorkflowValueType.ARRAY -> R.string.workflow_type_array
}

/** A non-video template with no sending or publishing; the recipient supplies their app and expected text. */
private fun starterWorkflow(context: Context): String {
    val appCondition = WorkflowCondition(appPackage = "{{binding.target_app}}")
    val success = WorkflowCondition(appPackage = "{{binding.target_app}}", textPresent = "{{param.expected_text}}")
    return WorkflowCodec.json.encodeToString(WorkflowDefinition(
        id = "workflow-" + UUID.randomUUID().toString(),
        version = "1.0.0",
        name = context.getString(R.string.workflow_starter_name),
        description = context.getString(R.string.workflow_starter_description),
        parameters = listOf(WorkflowParameter("expected_text", description = context.getString(R.string.workflow_starter_text))),
        bindings = listOf(WorkflowParameter("target_app", description = context.getString(R.string.workflow_starter_app))),
        steps = listOf(
            WorkflowStep(
                id = "open", title = context.getString(R.string.workflow_starter_open),
                tool = "phone_launch_app", arguments = buildJsonObject { put("package", "{{binding.target_app}}") },
                effect = WorkflowEffect.LOCAL, after = appCondition,
            ),
            WorkflowStep(
                id = "observe", title = context.getString(R.string.workflow_starter_check),
                tool = "phone_observe", before = appCondition, after = success,
            ),
        ),
        successCondition = success,
    ))
}
