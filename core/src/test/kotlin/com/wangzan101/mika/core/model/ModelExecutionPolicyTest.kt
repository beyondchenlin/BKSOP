package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.usage.RunUsageTally
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelExecutionPolicyTest {
    private lateinit var server: MockWebServer
    private val caps = Capabilities(true, false, 32000, false)
    private val user = listOf(Message(Role.User, listOf(Part.Text("task text")), Author.User))
    private val source = UsageSource("provider", "Provider", "model", UsagePurpose.Main)
    private val recorded = mutableListOf<Pair<UsageSource, Usage?>>()
    private val sink = UsageSink { s, u -> recorded += s to u }

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private class Plain(private val calls: AtomicInteger) : ChatModel {
        override val capabilities = Capabilities(true, false, 32000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls.incrementAndGet()
            return ModelReply(listOf(Part.Text("answer")), StopReason.EndTurn, Usage(3, 2))
        }
    }

    private class Streaming(private val calls: AtomicInteger) : StreamingChatModel {
        override val capabilities = Capabilities(true, false, 32000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls.incrementAndGet()
            return ModelReply(listOf(Part.Text("answer")), StopReason.EndTurn, Usage(3, 2))
        }
        override suspend fun completeStreaming(
            system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
        ): ModelReply {
            onText("answer")
            return complete(system, messages, tools)
        }
    }

    private suspend fun expectBlocked(runId: String, call: suspend () -> Unit): ModelCallForbiddenException {
        try {
            call()
            throw AssertionError("Fixed workflow unexpectedly called a model")
        } catch (e: ModelCallForbiddenException) {
            assertEquals(runId, e.runId)
            return e
        }
    }

    @Test fun meteredCallsForEveryPurposeAreBlockedWithoutRecordingUsage() = runTest {
        val events = mutableListOf<BlockedModelCall>()
        val policy = ModelExecutionPolicy.fixed("fixed-all-purposes", events::add)
        val calls = AtomicInteger()
        val tally = RunUsageTally()
        withContext(policy + tally) {
            UsagePurpose.entries.forEach { purpose ->
                val model = Plain(calls).metered(source.copy(purpose = purpose), sink)
                val error = expectBlocked(policy.runId) { model.complete("system", user, emptyList()) }
                assertEquals("metered", error.boundary)
            }
        }
        assertEquals(0, calls.get())
        assertTrue(recorded.isEmpty())
        assertEquals(0, tally.event(1).calls)
        assertEquals(UsagePurpose.entries.size, policy.blockedCalls)
        assertEquals(UsagePurpose.entries.size, events.size)
        assertTrue(events.all { it.runId == policy.runId && it.boundary == "metered" })
    }

    @Test fun meteredStreamingNeverInvokesInnerModelOrTextCallback() = runTest {
        val calls = AtomicInteger()
        val updates = mutableListOf<String>()
        val policy = ModelExecutionPolicy.fixed("fixed-streaming")
        val tally = RunUsageTally()
        val model = Streaming(calls).metered(source, sink) as StreamingChatModel
        withContext(policy + tally) {
            expectBlocked(policy.runId) { model.completeStreaming("system", user, emptyList(), updates::add) }
        }
        assertEquals(0, calls.get())
        assertTrue(updates.isEmpty())
        assertTrue(recorded.isEmpty())
        assertEquals(0, tally.event(1).calls)
        assertEquals(1, policy.blockedCalls)
    }

    @Test fun childrenAndDispatcherChangesInheritTheSamePolicy() = runTest {
        val calls = AtomicInteger()
        val policy = ModelExecutionPolicy.fixed("fixed-children")
        val tally = RunUsageTally()
        val model = Plain(calls).metered(source, sink)
        withContext(policy + tally) {
            coroutineScope {
                List(8) {
                    async(Dispatchers.Default) {
                        expectBlocked(policy.runId) { model.complete("system", user, emptyList()) }
                    }
                }.awaitAll()
            }
        }
        assertEquals(0, calls.get())
        assertTrue(recorded.isEmpty())
        assertEquals(0, tally.event(1).calls)
        assertEquals(8, policy.blockedCalls)
    }

    private fun rawModels(): List<StreamingChatModel> {
        val base = server.url("/").toString().trimEnd('/')
        val http = OkHttpClient()
        return listOf(
            OpenAiCompatModel("$base/v1", "test-key", "model", caps, http),
            AnthropicModel(base, "test-key", "model", caps, http),
            GeminiModel(base, "test-key", "model", caps, http),
            ResponsesModel("$base/v1", BearerToken.fixed("test-key"), "model", caps, http),
        )
    }

    // 原始适配器不套 metered，必须由真正的 HTTP 边界拦下；若错误地发出请求，服务器立即回错避免测试挂住。
    @Test fun everyRawAdapterBlocksNonStreamingCallsBeforeHttp() = runTest {
        val models = rawModels()
        models.forEach { server.enqueue(MockResponse().setResponseCode(500)) }
        val policy = ModelExecutionPolicy.fixed("fixed-raw")
        withContext(policy) {
            models.forEach { model ->
                val error = expectBlocked(policy.runId) { model.complete("system", user, emptyList()) }
                assertTrue(error.boundary == "http_execute" || error.boundary == "http_stream")
            }
        }
        assertEquals(models.size, policy.blockedCalls)
        assertEquals(0, server.requestCount)
    }

    @Test fun everyRawAdapterBlocksStreamingCallsBeforeHttp() = runTest {
        val models = rawModels()
        models.forEach { server.enqueue(MockResponse().setResponseCode(500)) }
        val policy = ModelExecutionPolicy.fixed("fixed-raw-streaming")
        val updates = mutableListOf<String>()
        withContext(policy) {
            models.forEach { model ->
                val error = expectBlocked(policy.runId) {
                    model.completeStreaming("system", user, emptyList(), updates::add)
                }
                assertEquals("http_stream", error.boundary)
            }
        }
        assertTrue(updates.isEmpty())
        assertEquals(models.size, policy.blockedCalls)
        assertEquals(0, server.requestCount)
    }

    @Test fun brokenAuditCallbackCannotAllowAnHttpRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val policy = ModelExecutionPolicy.fixed("fixed-broken-audit") { error("audit storage failed") }
        val model = rawModels().first()
        withContext(policy) {
            expectBlocked(policy.runId) { model.complete("system", user, emptyList()) }
        }
        assertEquals(1, policy.blockedCalls)
        assertEquals(0, server.requestCount)
    }

    @Test fun leavingFixedContextRestoresNormalHttpStreamingAndMetering() = runTest {
        val raw = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "test-key", "model", caps, OkHttpClient())
        val model = raw.metered(source, sink) as StreamingChatModel
        val policy = ModelExecutionPolicy.fixed("fixed-only-in-scope")
        withContext(policy) {
            expectBlocked(policy.runId) { model.complete("system", user, emptyList()) }
        }
        val response = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}"""
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response)) }
        val tally = RunUsageTally()
        val updates = mutableListOf<String>()
        withContext(tally) {
            assertEquals(Usage(3, 2), model.complete("system", user, emptyList()).usage)
            assertEquals(Usage(3, 2), model.completeStreaming("system", user, emptyList(), updates::add).usage)
        }
        assertEquals(listOf("ok"), updates)
        assertEquals(2, server.requestCount)
        assertEquals(2, recorded.size)
        assertEquals(2, tally.event(1).calls)
        assertEquals(6L, tally.event(1).inputTokens)
        assertEquals(1, policy.blockedCalls)
    }
}
