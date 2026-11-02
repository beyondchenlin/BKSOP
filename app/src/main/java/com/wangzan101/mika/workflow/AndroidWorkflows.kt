package com.wangzan101.mika.workflow

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.core.assembly.WorkflowActions
import com.wangzan101.mika.core.action.DeviceExecutionGate
import com.wangzan101.mika.core.mcp.McpWorkflowTools
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.workflow.*
import com.wangzan101.mika.shell.IMikaShell
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Phone-owned workflow host. It never creates an AgentLoop or obtains a model from ProviderStore. */
class AndroidWorkflows private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("bksop-workflows", Context.MODE_PRIVATE)
    private val phone = PhoneServices.of(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val native = NativePort()
    val service: WorkflowService
    @Volatile private var cleanup: Job? = null

    init {
        val device = preferences.getString("device-id", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("device-id", it).commit()) { "Could not save the workflow device identity" }
        }
        service = WorkflowService(FileWorkflowStore(File(app.filesDir, "bksop/workflows")), native, scope, device)
    }

    fun isRemoteAllowed(installationId: String): Boolean = preferences.getBoolean("remote:$installationId", false)

    /** Local owner action only. Revocation blocks future steps and stops already queued remote work. */
    fun setRemoteAllowed(installationId: String, allowed: Boolean) {
        check(service.snapshot().installations.any { it.id == installationId }) { "Unknown installation" }
        check(preferences.edit().putBoolean("remote:$installationId", allowed).commit()) { "Could not save workflow authorization" }
        if (!allowed) service.snapshot().runs.filter {
            it.installationId == installationId && it.callerId != "local" && !terminal(it.state)
        }.forEach { service.cancel(it.id) }
    }

    suspend fun environment(): WorkflowEnvironment = native.environment()

    fun stopAll() {
        service.snapshot().runs.filter { !terminal(it.state) }.forEach { runCatching { service.cancel(it.id) } }
    }

    /** A service instance may stop only the runs it actually served, never a newer queued request. */
    internal fun stopRuns(runIds: Set<String>) {
        runIds.forEach { id -> runCatching { service.cancel(id) } }
    }

    fun hasActiveRuns(): Boolean = service.snapshot().let { it.storageIssue == null && it.runs.any { run -> !terminal(run.state) } }

    private inner class Session(
        val run: WorkflowRun,
        val shell: IMikaShell,
        val accessibility: ScreenAccessibility,
        val display: LazyAgentDisplay,
        val screen: AndroidScreen,
        val actions: WorkflowActions,
    ) {
        fun release() {
            runCatching { display.current?.release() }
            phone.sharedShell.release()
        }
    }

    private inner class NativePort : WorkflowActionPort {
        override val supportedTools: Set<String> get() = WorkflowActions.SUPPORTED_TOOLS
        @Volatile private var current: Session? = null

        override suspend fun environment(): WorkflowEnvironment = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val versions = app.packageManager.getInstalledPackages(0).associate { it.packageName to it.longVersionCode.toString() }.toSortedMap()
            val access = (app as MikaApp).appAccess.current()
            val facts = buildJsonObject {
                put("system", Build.FINGERPRINT); put("sdk", Build.VERSION.SDK_INT)
                put("apps", JsonObject(versions.mapValues { JsonPrimitive(it.value) }))
                putJsonObject("access") {
                    put("available", access.available)
                    put("blocked", JsonArray(access.blocked.sorted().map(::JsonPrimitive)))
                    put("shareAllowed", JsonArray(access.shareAllowed.sorted().map(::JsonPrimitive)))
                }
                put("accessibility", ScreenAccessibility.instance.value != null)
                put("helper_paired", phone.shellControl.paired)
            }
            WorkflowEnvironment(WorkflowCodec.digest(facts), "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}", versions)
        }

        override suspend fun open(run: WorkflowRun) {
            currentCoroutineContext().ensureActive()
            checkRemote(run)
            checkUnlocked()
            try {
                app.startForegroundService(Intent(app, WorkflowForegroundService::class.java)
                    .putExtra(WorkflowForegroundService.RUN_ID, run.id))
            } catch (_: Exception) {
                throw WorkflowException("foreground_unavailable", app.getString(R.string.workflow_open_phone_required))
            }
            val old = sessions[run.id]
            if (old != null && old.shell.asBinder().isBinderAlive && ScreenAccessibility.instance.value === old.accessibility) {
                current = old
                return
            }
            if (old != null && sessions.remove(run.id, old)) old.release()
            var acquired = false
            var display: LazyAgentDisplay? = null
            try {
                val connection = phone.sharedShell.acquire().also { acquired = true }
                val shell = withTimeoutOrNull(10_000) { connection.shell.filterNotNull().first() }
                    ?: throw WorkflowException("phone_unavailable", app.getString(R.string.workflow_phone_setup_required))
                val accessibility = ScreenAccessibility.instance.value
                    ?: throw WorkflowException("accessibility_unavailable", app.getString(R.string.workflow_accessibility_required))
                val metrics = app.resources.displayMetrics
                val screenDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                    withContext(NonCancellable + Dispatchers.IO) { AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi) }
                }.also { display = it }
                val access = (app as MikaApp).appAccess
                val beforeDispatch: suspend () -> Unit = {
                    currentCoroutineContext().ensureActive()
                    checkRemote(run)
                    checkUnlocked()
                    if (!shell.asBinder().isBinderAlive || ScreenAccessibility.instance.value !== accessibility)
                        throw WorkflowException("phone_connection_changed", app.getString(R.string.workflow_connection_changed))
                    val saved = service.status(run.id)
                    if (saved.state != WorkflowRunState.RUNNING) throw CancellationException("Workflow no longer running")
                }
                val screen = AndroidScreen(accessibility, shell, screenDisplay, appAccess = access, beforeDispatch = beforeDispatch)
                val guarded = AppAccessScreenPort(screen, access,
                    packages = { screen.accessPackages() }, protectedUi = { access.sensitiveUi }, ownPackage = app.packageName,
                    windowToken = { ScreenAccessibility.windowGeneration() })
                val actions = WorkflowActions(guarded, run.id, selfPackage = app.packageName, beforeAction = beforeDispatch)
                val session = Session(run, shell, accessibility, screenDisplay, screen, actions)
                sessions[run.id] = session
                current = session
                monitorSessions()
            } catch (failure: Throwable) {
                runCatching { display?.current?.release() }
                if (acquired) phone.sharedShell.release()
                throw failure
            }
        }

        override suspend fun check(condition: WorkflowCondition): WorkflowCheck {
            val session = current ?: throw WorkflowException("session_unavailable", "Workflow session is not open")
            checkRemote(session.run)
            checkUnlocked()
            return session.actions.check(condition)
        }

        override suspend fun execute(step: WorkflowStep, arguments: JsonObject, runId: String, allowExternalEffects: Boolean): WorkflowActionResult {
            val session = current?.takeIf { it.run.id == runId }
                ?: throw WorkflowException("session_mismatch", "Workflow session does not match this run")
            return session.actions.execute(step, arguments, allowExternalEffects)
        }

        override suspend fun close(runId: String) {
            if (current?.run?.id == runId) current = null
            // A user wait releases the execution permit but retains the isolated page; every continuation rechecks it.
            // Terminal/failed sessions are released by the monitor, after the core has durably settled the result.
            monitorSessions()
        }
    }

    private fun checkRemote(run: WorkflowRun) {
        if (run.callerId != "local" && !isRemoteAllowed(run.installationId))
            throw WorkflowException("authorization_revoked", app.getString(R.string.workflow_remote_revoked))
    }

    private fun checkUnlocked() {
        if (app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            throw WorkflowException("device_locked", app.getString(R.string.workflow_unlock_required))
    }

    @Synchronized private fun monitorSessions() {
        if (cleanup?.isActive == true) return
        cleanup = scope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    // A storage failure may cancel a native action while its synchronous input transaction is still
                    // draining. Release its display only after that execution segment has relinquished the same gate.
                    DeviceExecutionGate.withExclusive {
                        val snapshot = service.snapshot()
                        sessions.entries.toList().forEach { (id, session) ->
                            val run = snapshot.runs.find { it.id == id }
                            if ((snapshot.storageIssue != null || run == null || terminal(run.state)) && sessions.remove(id, session)) session.release()
                        }
                    }
                    if (sessions.isNotEmpty()) delay(500)
                } while (sessions.isNotEmpty())
            } finally {
                synchronized(this@AndroidWorkflows) {
                    cleanup = null
                    if (scope.isActive && sessions.isNotEmpty()) monitorSessions()
                }
            }
        }.also { it.start() }
    }

    companion object {
        @Volatile private var instance: AndroidWorkflows? = null
        fun get(context: Context): AndroidWorkflows = instance ?: synchronized(this) {
            instance ?: AndroidWorkflows(context.applicationContext).also { instance = it }
        }

        /** Metadata construction does not read disk. The authenticated request thread resolves the host lazily. */
        fun remoteTools(context: Context) = McpWorkflowTools(
            service = { get(context).service },
            allowed = { get(context).isRemoteAllowed(it) },
            environment = { get(context).environment() },
        )

        internal fun terminal(state: WorkflowRunState) = state in setOf(
            WorkflowRunState.SUCCEEDED, WorkflowRunState.FAILED, WorkflowRunState.STOPPED, WorkflowRunState.UNKNOWN,
        )
    }
}
