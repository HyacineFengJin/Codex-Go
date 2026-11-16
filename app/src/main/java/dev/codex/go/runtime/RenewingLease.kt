package dev.codex.go.runtime

import kotlinx.coroutines.*

/** Main-thread owner of a finite OS lease, renewed only while work remains active. */
class RenewingLease(
    private val scope: CoroutineScope,
    private val durationMs: Long,
    private val renewMs: Long,
    private val acquire: (Long) -> Unit,
    private val release: () -> Unit,
) {
    private var job: Job? = null
    private var generation = 0
    init { require(renewMs > 0 && durationMs > renewMs) }
    fun setActive(active: Boolean) {
        if (!active) {
            ++generation; job?.cancel(); job = null; release()
            return
        }
        if (job?.isActive == true) return
        val token = ++generation
        acquire(durationMs)
        job = scope.launch {
            while (isActive) { delay(renewMs); if (generation == token) acquire(durationMs) }
        }
        job!!.invokeOnCompletion { if (generation == token) { job = null; release() } }
    }
}
