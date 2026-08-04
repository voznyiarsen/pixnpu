package com.pixnpu.util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Simple circuit breaker to prevent repeated operations after failures.
 * Opens after consecutive failures, stays open for cooldown period, then half-opens.
 */
class CircuitBreaker(
    private val maxFailures: Int = 3,
    private val cooldownMs: Long = 30000, // 30 seconds
) {
    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    private val state = AtomicReference<State>(State.CLOSED)

    enum class State { CLOSED, OPEN, HALF_OPEN }

    /**
     * Check if operation is allowed
     */
    fun canExecute(): Boolean {
        when (state.get()) {
            State.CLOSED -> return true
            State.OPEN -> {
                val now = System.currentTimeMillis()
                val timeSinceFailure = now - lastFailureTime.get()
                if (timeSinceFailure >= cooldownMs) {
                    state.set(State.HALF_OPEN)
                    return true
                }
                return false
            }
            State.HALF_OPEN -> return true
        }
    }

    /**
     * Record a successful operation
     */
    fun recordSuccess() {
        failureCount.set(0)
        state.set(State.CLOSED)
    }

    /**
     * Record a failed operation
     */
    fun recordFailure() {
        val count = failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        if (count >= maxFailures) {
            state.set(State.OPEN)
        }
    }

    /**
     * Reset the circuit breaker
     */
    fun reset() {
        failureCount.set(0)
        state.set(State.CLOSED)
    }

    /**
     * Get current state for logging/debugging
     */
    fun getState(): State = state.get()
}
