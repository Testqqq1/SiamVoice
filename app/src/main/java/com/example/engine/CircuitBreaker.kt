package com.example.engine

import com.example.model.CircuitBreakerState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class QuotaExhaustedException(message: String) : Exception(message)
class AuthFailedException(message: String) : Exception(message)
class CloudModelUnavailableException(message: String) : Exception(message)
class InvalidAudioException(message: String) : Exception(message)

class CircuitBreaker(
    val name: String,
    private val failureThreshold: Int = 2,
    private val defaultCooldownMs: Long = 60_000L,
    private val quotaCooldownMs: Long = 180_000L
) {
    private val state = AtomicReference(CircuitBreakerState.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0L)
    private val currentCooldownMs = AtomicLong(defaultCooldownMs)

    fun canExecute(): Boolean {
        val now = System.currentTimeMillis()
        return when (state.get()) {
            CircuitBreakerState.CLOSED -> true
            CircuitBreakerState.OPEN -> {
                if (now - lastFailureTime.get() >= currentCooldownMs.get()) {
                    state.set(CircuitBreakerState.HALF_OPEN)
                    true
                } else {
                    false
                }
            }
            CircuitBreakerState.HALF_OPEN -> true
        }
    }

    fun recordSuccess() {
        failureCount.set(0)
        state.set(CircuitBreakerState.CLOSED)
    }

    fun recordFailure(t: Throwable) {
        val now = System.currentTimeMillis()
        lastFailureTime.set(now)

        when (t) {
            is QuotaExhaustedException -> {
                currentCooldownMs.set(quotaCooldownMs)
                state.set(CircuitBreakerState.OPEN)
            }
            is AuthFailedException -> {
                currentCooldownMs.set(quotaCooldownMs)
                state.set(CircuitBreakerState.OPEN)
            }
            is CloudModelUnavailableException -> {
                currentCooldownMs.set(defaultCooldownMs)
                state.set(CircuitBreakerState.OPEN)
            }
            else -> {
                currentCooldownMs.set(defaultCooldownMs)
                val count = failureCount.incrementAndGet()
                if (count >= failureThreshold || state.get() == CircuitBreakerState.HALF_OPEN) {
                    state.set(CircuitBreakerState.OPEN)
                }
            }
        }
    }

    fun getState(): CircuitBreakerState {
        val now = System.currentTimeMillis()
        if (state.get() == CircuitBreakerState.OPEN && now - lastFailureTime.get() >= currentCooldownMs.get()) {
            return CircuitBreakerState.HALF_OPEN
        }
        return state.get()
    }

    fun tripManually() {
        lastFailureTime.set(System.currentTimeMillis())
        currentCooldownMs.set(defaultCooldownMs)
        state.set(CircuitBreakerState.OPEN)
    }

    fun reset() {
        failureCount.set(0)
        state.set(CircuitBreakerState.CLOSED)
    }
}
