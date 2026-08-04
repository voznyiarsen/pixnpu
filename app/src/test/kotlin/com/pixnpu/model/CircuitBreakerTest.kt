package com.pixnpu.model

import com.pixnpu.util.CircuitBreaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the CircuitBreaker class used in ModelManager
 */
class CircuitBreakerTest {

    private lateinit var circuitBreaker: CircuitBreaker

    @Before
    fun setup() {
        // Use short cooldown for testing
        circuitBreaker = CircuitBreaker(maxFailures = 3, cooldownMs = 100)
    }

    @Test
    fun initialState_isClosed() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState())
    }

    @Test
    fun canExecute_whenClosed_returnsTrue() {
        assertTrue(circuitBreaker.canExecute())
    }

    @Test
    fun recordSuccess_whenClosed_staysClosed() {
        circuitBreaker.recordSuccess()
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState())
    }

    @Test
    fun recordFailure_underThreshold_staysClosed() {
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState())
    }

    @Test
    fun recordFailure_atThreshold_opens() {
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState())
    }

    @Test
    fun canExecute_whenOpen_returnsFalse() {
        // Open the circuit breaker
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        
        assertFalse(circuitBreaker.canExecute())
    }

    @Test
    fun canExecute_afterCooldown_returnsTrue() {
        // Open the circuit breaker
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        
        // Wait for cooldown
        Thread.sleep(150)
        
        assertTrue(circuitBreaker.canExecute())
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState())
    }

    @Test
    fun recordSuccess_afterCooldown_closes() {
        // Open the circuit breaker
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        
        // Wait for cooldown
        Thread.sleep(150)
        
        // Should be able to execute (half-open)
        assertTrue(circuitBreaker.canExecute())
        
        // Record success
        circuitBreaker.recordSuccess()
        
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState())
    }

    @Test
    fun reset_closesCircuit() {
        // Open the circuit breaker
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState())
        
        // Reset
        circuitBreaker.reset()
        
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState())
        assertTrue(circuitBreaker.canExecute())
    }
}
