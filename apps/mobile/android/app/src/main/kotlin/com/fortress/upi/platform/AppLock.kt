package com.fortress.upi.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fortress.upi.core.crypto.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level lock: locked on cold start, and again whenever the process returns
 * from the background after [timeoutMs]. Observes ProcessLifecycleOwner so it
 * covers every activity, not just the current one.
 */
class AppLock(private val clock: Clock, timeoutMs: Long = 60_000) : DefaultLifecycleObserver {
    /** Grace period in the background before locking; set from Settings → Auto-lock. */
    @Volatile
    var timeoutMs: Long = timeoutMs
    private val state = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = state.asStateFlow()

    @Volatile
    private var backgroundedAt = 0L

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = clock.nowMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundedAt != 0L && clock.nowMillis() - backgroundedAt >= timeoutMs) state.value = true
    }

    fun unlock() {
        state.value = false
    }

    fun lockNow() {
        state.value = true
    }
}
