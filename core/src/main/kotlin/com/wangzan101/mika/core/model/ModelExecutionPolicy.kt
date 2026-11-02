package com.wangzan101.mika.core.model

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** 被阻止的调用意图；不保存提示词、请求地址、凭据或媒体内容。 */
data class BlockedModelCall(val runId: String, val boundary: String)

/** 固定流程误入模型路径。请求尚未发出，不应计为已发生的模型调用。 */
class ModelCallForbiddenException(val runId: String, val boundary: String) :
    IllegalStateException("Model calls are forbidden in fixed workflow $runId ($boundary)")

/**
 * 固定执行的模型禁用边界。用 withContext(ModelExecutionPolicy.fixed(runId)) 包住整个运行及其善后；
 * 子协程和调度器切换继承同一个计数器。普通运行不安装本元素，原行为不变。
 *
 * 不提供放开模型的开关；智能修复须在原固定任务之外另建运行。脱离父上下文的新作用域不会自动继承，
 * 因此固定任务不得把总结、记忆等工作转移到不带此策略的独立作用域。
 */
class ModelExecutionPolicy private constructor(
    val runId: String,
    private val onBlocked: (BlockedModelCall) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    private val blocked = AtomicInteger()

    /** 只统计拦截，不冒充已发送请求或已使用的用量。 */
    val blockedCalls: Int get() = blocked.get()

    private fun forbid(boundary: String): Nothing {
        val event = BlockedModelCall(runId, boundary)
        blocked.incrementAndGet()
        // 审计存储失败也不能放开请求；调用方仍收到明确的禁用错误。
        runCatching { onBlocked(event) }
        throw ModelCallForbiddenException(runId, boundary)
    }

    companion object Key : CoroutineContext.Key<ModelExecutionPolicy> {
        fun fixed(runId: String, onBlocked: (BlockedModelCall) -> Unit = {}): ModelExecutionPolicy {
            require(runId.isNotBlank()) { "A fixed workflow requires a run id" }
            return ModelExecutionPolicy(runId, onBlocked)
        }

        internal suspend fun checkAllowed(boundary: String) {
            currentCoroutineContext()[Key]?.forbid(boundary)
        }
    }
}
