package com.goon.app.services

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for automation state machine logic
 */
class AutomationStateTest {

    @Test
    fun `initial state should be IDLE`() {
        val stateMachine = AutomationStateMachine()
        assertEquals(AutomationState.IDLE, stateMachine.currentState)
    }

    @Test
    fun `should transition from IDLE to LAUNCHING_APP`() {
        val stateMachine = AutomationStateMachine()

        stateMachine.transition(AutomationEvent.START_AUTOMATION)

        assertEquals(AutomationState.LAUNCHING_APP, stateMachine.currentState)
    }

    @Test
    fun `should transition through complete flow`() {
        val stateMachine = AutomationStateMachine()

        // Start -> Launching
        stateMachine.transition(AutomationEvent.START_AUTOMATION)
        assertEquals(AutomationState.LAUNCHING_APP, stateMachine.currentState)

        // App opened -> Entering pickup
        stateMachine.transition(AutomationEvent.APP_OPENED)
        assertEquals(AutomationState.ENTERING_PICKUP, stateMachine.currentState)

        // Pickup entered -> Entering destination
        stateMachine.transition(AutomationEvent.PICKUP_ENTERED)
        assertEquals(AutomationState.ENTERING_DESTINATION, stateMachine.currentState)

        // Destination entered -> Waiting for prices
        stateMachine.transition(AutomationEvent.DESTINATION_ENTERED)
        assertEquals(AutomationState.WAITING_FOR_PRICES, stateMachine.currentState)

        // Prices received -> Extracting prices
        stateMachine.transition(AutomationEvent.PRICES_SHOWN)
        assertEquals(AutomationState.EXTRACTING_PRICES, stateMachine.currentState)

        // Prices extracted -> Complete
        stateMachine.transition(AutomationEvent.PRICES_EXTRACTED)
        assertEquals(AutomationState.COMPLETE, stateMachine.currentState)
    }

    @Test
    fun `should handle errors and reset`() {
        val stateMachine = AutomationStateMachine()

        stateMachine.transition(AutomationEvent.START_AUTOMATION)
        stateMachine.transition(AutomationEvent.APP_OPENED)
        stateMachine.transition(AutomationEvent.ERROR_OCCURRED)

        assertEquals(AutomationState.ERROR, stateMachine.currentState)

        stateMachine.transition(AutomationEvent.RESET)
        assertEquals(AutomationState.IDLE, stateMachine.currentState)
    }

    @Test
    fun `should timeout if stuck in state`() {
        val stateMachine = AutomationStateMachine()

        stateMachine.transition(AutomationEvent.START_AUTOMATION)
        stateMachine.transition(AutomationEvent.APP_OPENED)

        // Simulate timeout
        stateMachine.checkTimeout(31000) // 31 seconds

        assertEquals(AutomationState.TIMEOUT, stateMachine.currentState)
    }

    @Test
    fun `should not timeout within threshold`() {
        val stateMachine = AutomationStateMachine()

        stateMachine.transition(AutomationEvent.START_AUTOMATION)
        stateMachine.transition(AutomationEvent.APP_OPENED)

        // Check before timeout
        stateMachine.checkTimeout(15000) // 15 seconds

        assertEquals(AutomationState.ENTERING_PICKUP, stateMachine.currentState)
    }

    @Test
    fun `should track retry count`() {
        val stateMachine = AutomationStateMachine()

        stateMachine.transition(AutomationEvent.START_AUTOMATION)
        assertEquals(0, stateMachine.retryCount)

        stateMachine.transition(AutomationEvent.ERROR_OCCURRED)
        stateMachine.transition(AutomationEvent.RETRY)
        assertEquals(1, stateMachine.retryCount)

        stateMachine.transition(AutomationEvent.ERROR_OCCURRED)
        stateMachine.transition(AutomationEvent.RETRY)
        assertEquals(2, stateMachine.retryCount)
    }

    @Test
    fun `should limit retries`() {
        val stateMachine = AutomationStateMachine()

        stateMachine.transition(AutomationEvent.START_AUTOMATION)

        // Retry 3 times (max)
        repeat(3) {
            stateMachine.transition(AutomationEvent.ERROR_OCCURRED)
            stateMachine.transition(AutomationEvent.RETRY)
        }

        // 4th retry should fail
        stateMachine.transition(AutomationEvent.ERROR_OCCURRED)
        val canRetry = stateMachine.canRetry()

        assertFalse(canRetry)
    }
}

// State machine implementation for testing
enum class AutomationState {
    IDLE,
    LAUNCHING_APP,
    ENTERING_PICKUP,
    ENTERING_DESTINATION,
    WAITING_FOR_PRICES,
    EXTRACTING_PRICES,
    COMPLETE,
    ERROR,
    TIMEOUT
}

enum class AutomationEvent {
    START_AUTOMATION,
    APP_OPENED,
    PICKUP_ENTERED,
    DESTINATION_ENTERED,
    PRICES_SHOWN,
    PRICES_EXTRACTED,
    ERROR_OCCURRED,
    RESET,
    RETRY
}

class AutomationStateMachine {
    var currentState: AutomationState = AutomationState.IDLE
        private set

    var retryCount: Int = 0
        private set

    private var stateEnteredAt: Long = System.currentTimeMillis()
    private val maxRetries = 3
    private val timeoutMs = 30000L

    fun transition(event: AutomationEvent) {
        currentState = when (currentState) {
            AutomationState.IDLE -> when (event) {
                AutomationEvent.START_AUTOMATION -> AutomationState.LAUNCHING_APP
                else -> currentState
            }
            AutomationState.LAUNCHING_APP -> when (event) {
                AutomationEvent.APP_OPENED -> AutomationState.ENTERING_PICKUP
                AutomationEvent.ERROR_OCCURRED -> AutomationState.ERROR
                else -> currentState
            }
            AutomationState.ENTERING_PICKUP -> when (event) {
                AutomationEvent.PICKUP_ENTERED -> AutomationState.ENTERING_DESTINATION
                AutomationEvent.ERROR_OCCURRED -> AutomationState.ERROR
                else -> currentState
            }
            AutomationState.ENTERING_DESTINATION -> when (event) {
                AutomationEvent.DESTINATION_ENTERED -> AutomationState.WAITING_FOR_PRICES
                AutomationEvent.ERROR_OCCURRED -> AutomationState.ERROR
                else -> currentState
            }
            AutomationState.WAITING_FOR_PRICES -> when (event) {
                AutomationEvent.PRICES_SHOWN -> AutomationState.EXTRACTING_PRICES
                AutomationEvent.ERROR_OCCURRED -> AutomationState.ERROR
                else -> currentState
            }
            AutomationState.EXTRACTING_PRICES -> when (event) {
                AutomationEvent.PRICES_EXTRACTED -> AutomationState.COMPLETE
                AutomationEvent.ERROR_OCCURRED -> AutomationState.ERROR
                else -> currentState
            }
            AutomationState.ERROR -> when (event) {
                AutomationEvent.RESET -> {
                    retryCount = 0
                    AutomationState.IDLE
                }
                AutomationEvent.RETRY -> {
                    retryCount++
                    AutomationState.LAUNCHING_APP
                }
                else -> currentState
            }
            AutomationState.TIMEOUT -> when (event) {
                AutomationEvent.RESET -> {
                    retryCount = 0
                    AutomationState.IDLE
                }
                else -> currentState
            }
            AutomationState.COMPLETE -> when (event) {
                AutomationEvent.RESET -> {
                    retryCount = 0
                    AutomationState.IDLE
                }
                else -> currentState
            }
        }
        stateEnteredAt = System.currentTimeMillis()
    }

    fun checkTimeout(elapsedMs: Long) {
        if (elapsedMs > timeoutMs && currentState !in listOf(
            AutomationState.IDLE,
            AutomationState.COMPLETE,
            AutomationState.ERROR,
            AutomationState.TIMEOUT
        )) {
            currentState = AutomationState.TIMEOUT
        }
    }

    fun canRetry(): Boolean = retryCount < maxRetries
}
