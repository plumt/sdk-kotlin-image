package net.plumt.image.compress

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * [block] 을 메인 디스패처의 새 범위에서 돌리고 결과를 [callback] 으로 한 번 준다. 취소됐으면 부르지 않는다.
 * 블로킹 작업은 [block] 안(압축기의 디스패처)에서 돈다 — 메인 스레드에서는 콜백만 부른다.
 */
internal fun launchCallback(block: suspend () -> CompressionResult, callback: CompressionCallback): CompressionCallHandle {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val job = scope.launch {
        val result = block()
        ensureActive()
        callback.onResult(result)
    }
    return JobHandle(job)
}

internal class JobHandle(private val job: Job) : CompressionCallHandle {
    override val isActive: Boolean get() = job.isActive

    override fun cancel() {
        job.cancel()
    }

    override fun toString(): String = "CompressionCallHandle(active=${job.isActive})"
}

/** 이미 끝난 호출의 핸들(가짜 압축기가 바로 결과를 줄 때). */
internal object CompletedHandle : CompressionCallHandle {
    override val isActive: Boolean get() = false

    override fun cancel() = Unit

    override fun toString(): String = "CompressionCallHandle(active=false)"
}
