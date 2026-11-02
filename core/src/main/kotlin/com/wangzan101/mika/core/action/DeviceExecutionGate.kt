package com.wangzan101.mika.core.action

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 持有设备许可时不能派生并发界面操作；顺序调用可重入，独立任务须在许可外启动。 */
class ConcurrentDeviceExecutionException : IllegalStateException(
    "A child coroutine cannot reuse an active device execution permit; execute screen actions sequentially",
)

/**
 * 进程中所有原生界面动作共用的许可。固定流程在整段执行期间持有它，普通动作也通过同一入口排队。
 *
 * 同一顺序调用链可以重入，withContext、超时及不可取消的动作收尾仍是该调用链；launch/async 派生
 * 的协程不继承重入资格。它们在父许可仍有效时明确失败，而非越过锁或等待父任务造成死锁。
 * 要并行准备不动界面的工作，可以派生协程；界面操作须回到持有许可的顺序调用链。
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
object DeviceExecutionGate {
    private val mutex = Mutex()

    private class Lease {
        val active = AtomicBoolean(true)
    }

    /**
     * 只使用复制钩子区分顺序上下文切换和真正的子协程；许可不绑定操作系统线程。
     * 新加入 withContext 的元素也会被复制，所以重入资格只在取得全局锁后，于该上下文内部授予。
     */
    private class Permit(val lease: Lease) : AbstractCoroutineContextElement(Key), CopyableThreadContextElement<Unit> {
        @Volatile var sequentialOwner = false
        override fun updateThreadContext(context: CoroutineContext) = Unit
        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit
        override fun copyForChild() = Permit(lease)
        override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext =
            (overwritingElement as Permit).copyForChild()

        companion object Key : CoroutineContext.Key<Permit>
    }

    // 提供当前作用域，避免 block 中直接写 launch/async 时误捕获许可之外的词法作用域。
    suspend fun <T> withExclusive(block: suspend CoroutineScope.() -> T): T {
        val inherited = currentCoroutineContext()[Permit]
        if (inherited?.lease?.active?.get() == true) {
            if (!inherited.sequentialOwner) throw ConcurrentDeviceExecutionException()
            return coroutineScope { block() }
        }
        return mutex.withLock {
            val lease = Lease()
            try {
                withContext(Permit(lease)) {
                    checkNotNull(currentCoroutineContext()[Permit]).sequentialOwner = true
                    block()
                }
            } finally {
                lease.active.set(false)
            }
        }
    }
}
