package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.guard.GuardRules
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.workflow.WorkflowActionStatus
import com.wangzan101.mika.core.workflow.WorkflowCondition
import com.wangzan101.mika.core.workflow.WorkflowEffect
import com.wangzan101.mika.core.workflow.WorkflowStep
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowActionsTest {
    private class Screen : ScreenPort by FakeScreen() {
        var label = "继续"
        var snapshots = 0
        var generation = 0
        var imageReads = 0
        var unstable = false
        var protectedScreen = false
        var duplicate = false
        var secret = false
        var editable = false
        var scrollable = false
        var packageName = "com.chat"
        val calls = mutableListOf<String>()
        val swipes = mutableListOf<List<Int>>()
        var tapResult: ActionResult = ActionResult.Ok(changed = true)
        var tapThrows = false
        val currentRef get() = "@e${snapshots + 10}"
        val currentSnapshot get() = "s$snapshots"
        private fun shown(): List<UiElement> {
            val target = UiElement(currentRef, if (editable) "textbox" else if (scrollable) "list" else "button", label,
                500, 900, listOf(100, 100, 900, 800), "com.chat:id/target", !scrollable, editable, scrollable, secret = secret)
            return listOf(target, UiElement("@e99", "text", "页面 $generation", 10, 10,
                listOf(0, 0, 50, 50), null, false, false, false)) +
                if (duplicate) listOf(target.copy(ref = "@e98")) else emptyList()
        }
        private fun snap(id: String) = ScreenSnapshot(id, packageName, shown(), 1080, 2400,
            changedDuringRead = unstable, protected = protectedScreen)
        override suspend fun snapshot(): ScreenSnapshot { snapshots++; return snap(currentSnapshot) }
        override suspend fun peek() = snap("peek")
        override suspend fun screenshotPng(): ByteArray? { imageReads++; return byteArrayOf(1) }
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            calls += "tap $ref $snapshotId"
            if (tapThrows) throw IllegalStateException("private-screen-content-that-must-not-escape")
            generation++
            return tapResult
        }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            calls += "type $ref $snapshotId $text"
            generation++
            return ActionResult.Ok(changed = true, verification = Verification.MATCH)
        }
        override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult {
            calls += "clear $ref $snapshotId"
            generation++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun back(): ActionResult { calls += "back"; generation++; return ActionResult.Ok(changed = true) }
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            calls += "open $packageName $cold"; this.packageName = packageName
            return ActionResult.Ok(changed = true)
        }
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            calls += "scroll $down"; generation++; return ActionResult.Ok(changed = true)
        }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
            calls += "swipe"; swipes += listOf(x1, y1, x2, y2); generation++
            return ActionResult.Ok(changed = true)
        }
    }

    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private val app = WorkflowCondition(appPackage = "com.chat")
    private fun step(tool: String, effect: WorkflowEffect = WorkflowEffect.LOCAL) = WorkflowStep("step", "本机测试", tool = tool,
        effect = effect, before = if (effect == WorkflowEffect.EXTERNAL) app else null,
        after = if (effect == WorkflowEffect.EXTERNAL) app else null)

    @Test fun selectorUsesFreshNativeRefAndReturnsNoScreenTextOrImage() = runTest {
        val screen = Screen().apply { snapshots = 20 }
        val actions = WorkflowActions(screen, "run")
        val result = actions.execute(step("phone_tap"), args("""{"selector":{"text":"继续","resourceId":"com.chat:id/target"}}"""), false)
        assertEquals(WorkflowActionStatus.SUCCEEDED, result.status)
        assertEquals(listOf("tap ${screen.currentRef} ${screen.currentSnapshot}"), screen.calls)
        assertEquals(JsonPrimitive(false), result.evidence["businessVerified"])
        assertFalse(result.evidence.toString().contains("继续"))
        assertFalse(result.evidence.toString().contains(screen.currentRef))
        assertEquals(0, screen.imageReads)
    }

    @Test fun readOnlyDeclarationCannotDispatchAWriteAndCompositeOrModelToolsAreRejected() = runTest {
        val screen = Screen()
        val actions = WorkflowActions(screen, "run")
        val wrong = actions.execute(step("phone_back", WorkflowEffect.READ_ONLY), JsonObject(emptyMap()), false)
        assertEquals(WorkflowActionStatus.FAILED, wrong.status)
        assertEquals(JsonPrimitive("effect_declaration_mismatch"), wrong.evidence["code"])
        for (name in listOf("phone_run", "web_script", "run_command", "phone_describe")) {
            assertEquals(WorkflowActionStatus.FAILED, actions.execute(step(name), JsonObject(emptyMap()), true).status)
        }
        assertTrue(screen.calls.isEmpty())
        assertEquals(0, screen.snapshots)
    }

    @Test fun importedRefsCoordinatesAndAmbiguousOrPrivateTargetsNeverReachThePort() = runTest {
        for (arguments in listOf("""{"ref":"@e1","snapshotId":"old"}""", """{"x":500,"y":500}""")) {
            val screen = Screen()
            assertEquals(WorkflowActionStatus.FAILED, WorkflowActions(screen, "run").execute(step("phone_tap"), args(arguments), false).status)
            assertTrue(screen.calls.isEmpty())
        }
        for (screen in listOf(Screen().apply { duplicate = true }, Screen().apply { secret = true })) {
            val result = WorkflowActions(screen, "run").execute(step("phone_tap"), args("""{"selector":{"text":"继续"}}"""), false)
            assertEquals(WorkflowActionStatus.FAILED, result.status)
            assertTrue(screen.calls.isEmpty())
        }
    }

    @Test fun actualSendAndDeleteCannotHideBehindALocalDeclaration() = runTest {
        for (label in listOf("发送", "删除")) {
            val screen = Screen().apply { this.label = label }
            val actions = WorkflowActions(screen, "run")
            val arguments = args("""{"selector":{"resourceId":"com.chat:id/target"}}""")
            val local = actions.execute(step("phone_tap"), arguments, allowExternalEffects = true)
            assertEquals(label, WorkflowActionStatus.FAILED, local.status)
            assertFalse(local.effectDispatched)
            assertTrue(screen.calls.isEmpty())
            val external = actions.execute(step("phone_tap", WorkflowEffect.EXTERNAL), arguments, allowExternalEffects = true)
            assertEquals(label, WorkflowActionStatus.SUCCEEDED, external.status)
            assertTrue(external.effectDispatched)
            assertEquals(JsonPrimitive(false), external.evidence["businessVerified"])
            assertEquals(1, screen.calls.size)
        }
    }

    @Test fun externalToolFailureAfterDispatchIsUnknownAndCannotClaimNotSent() = runTest {
        for (throws in listOf(false, true)) {
            val screen = Screen().apply {
                label = "发送"; tapThrows = throws; tapResult = ActionResult.Failed("operation_failed")
            }
            val result = WorkflowActions(screen, "run").execute(step("phone_tap", WorkflowEffect.EXTERNAL),
                args("""{"selector":{"text":"发送"}}"""), true)
            assertEquals(WorkflowActionStatus.UNKNOWN, result.status)
            assertTrue(result.effectDispatched)
            assertEquals(1, screen.calls.size)
            assertFalse(result.toString().contains("private-screen-content"))
        }
    }

    @Test fun changingTheTargetAfterResolutionStopsBeforeThePhysicalWrite() = runTest {
        val screen = Screen()
        val actions = WorkflowActions(screen, "run", beforeAction = {
            if (screen.snapshots > 0) screen.label = "已经换成另一按钮"
        })
        val result = actions.execute(step("phone_tap"), args("""{"selector":{"text":"继续"}}"""), false)
        assertEquals(WorkflowActionStatus.FAILED, result.status)
        assertFalse(result.effectDispatched)
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun hostPermissionFailurePreventsAllReadsAndWritesWithoutLeakingItsMessage() = runTest {
        val screen = Screen()
        val actions = WorkflowActions(screen, "run", beforeAction = { throw IllegalStateException("private-permission-detail") })
        val result = actions.execute(step("phone_back"), JsonObject(emptyMap()), false)
        assertEquals(WorkflowActionStatus.FAILED, result.status)
        assertTrue(screen.calls.isEmpty())
        assertEquals(0, screen.snapshots)
        assertFalse(result.toString().contains("private-permission-detail"))
    }

    @Test fun checksUseOnlyStablePublicAccessibilityTextAndReturnBooleanEvidence() = runTest {
        val screen = Screen().apply { label = "公开页面内容" }
        val result = WorkflowActions(screen, "run").check(WorkflowCondition(appPackage = "com.chat", textPresent = "公开页面"))
        assertTrue(result.matched)
        assertEquals(JsonPrimitive(true), result.evidence["textPresentMatched"])
        assertFalse(result.toString().contains("公开页面内容"))
        assertEquals(0, screen.imageReads)
        assertFalse(WorkflowActions(Screen(), "run").check(WorkflowCondition()).matched)
        assertFalse(WorkflowActions(Screen().apply { unstable = true }, "run").check(app).matched)
        assertFalse(WorkflowActions(Screen().apply { protectedScreen = true }, "run").check(app).matched)
    }

    @Test fun secretAndEditableValuesCannotBecomeBusinessSuccessEvidence() = runTest {
        val secret = Screen().apply { label = "已发布"; this.secret = true }
        assertFalse(WorkflowActions(secret, "run").check(WorkflowCondition(textPresent = "已发布")).matched)
        assertFalse(WorkflowActions(secret, "run").check(WorkflowCondition(textAbsent = "已发布")).matched)
        val typed = Screen().apply { label = "已发布"; editable = true }
        assertFalse(WorkflowActions(typed, "run").check(WorkflowCondition(textPresent = "已发布")).matched)
    }

    @Test fun typingUsesASelectedFieldAndCannotCombineTypingWithSubmission() = runTest {
        val screen = Screen().apply { label = "标题"; editable = true }
        val actions = WorkflowActions(screen, "run")
        val blocked = actions.execute(step("phone_type_text"),
            args("""{"selector":{"text":"标题"},"text":"私人文案","pressEnter":true}"""), true)
        assertEquals(WorkflowActionStatus.FAILED, blocked.status)
        assertTrue(screen.calls.isEmpty())
        val result = actions.execute(step("phone_type_text"), args("""{"selector":{"text":"标题"},"text":"私人文案"}"""), false)
        assertEquals(WorkflowActionStatus.SUCCEEDED, result.status)
        assertTrue(screen.calls.single().startsWith("type "))
        assertFalse(result.toString().contains("私人文案"))
    }

    @Test fun swipeCoordinatesAreDerivedFromTheCurrentUniqueContainer() = runTest {
        val screen = Screen().apply { label = "列表"; scrollable = true }
        val result = WorkflowActions(screen, "run").execute(step("phone_swipe"),
            args("""{"selector":{"resourceId":"com.chat:id/target"},"direction":"down"}"""), false)
        assertEquals(WorkflowActionStatus.SUCCEEDED, result.status)
        val gesture = screen.swipes.single()
        assertEquals(gesture[0], gesture[2])
        assertTrue(gesture[1] > gesture[3])
        assertTrue(gesture[0] in 1..1079 && gesture[1] in 1..2399 && gesture[3] in 1..2399)
    }

    @Test fun launchAppUsesTheNativePackageArgumentAndConditionChecksTheResultingApp() = runTest {
        val screen = Screen()
        val actions = WorkflowActions(screen, "run")
        val result = actions.execute(step("phone_launch_app"), args("""{"package":"com.example.notes"}"""), false)
        assertEquals(WorkflowActionStatus.SUCCEEDED, result.status)
        assertEquals(listOf("open com.example.notes false"), screen.calls)
        assertTrue(actions.check(WorkflowCondition(appPackage = "com.example.notes")).matched)
    }

    @Test fun operationGuardWarningCannotBeReportedAsStepSuccess() = runTest {
        val screen = Screen()
        val actions = WorkflowActions(screen, "run")
        val results = List(GuardRules.REREAD_LADDER.first() + 1) {
            actions.execute(step("phone_list_elements", WorkflowEffect.READ_ONLY), JsonObject(emptyMap()), false)
        }
        assertEquals(WorkflowActionStatus.FAILED, results.last().status)
        assertEquals(JsonPrimitive("operation_guard"), results.last().evidence["code"])
        assertTrue(screen.calls.isEmpty())
    }
}
