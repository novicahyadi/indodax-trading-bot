package com.indodaxbot.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderStateMachineTest {
    private fun ok(a: OrderStatus, b: OrderStatus) = assertTrue("$a -> $b", OrderStateMachine.canTransition(a, b))
    private fun no(a: OrderStatus, b: OrderStatus) = assertFalse("$a -> $b", OrderStateMachine.canTransition(a, b))

    @Test
    fun normalLifecycle() {
        ok(OrderStatus.CREATED, OrderStatus.SUBMITTED)
        ok(OrderStatus.SUBMITTED, OrderStatus.OPEN)
        ok(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED)
        ok(OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED)
        ok(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED)
        ok(OrderStatus.SUBMITTED, OrderStatus.FILLED) // market order
    }

    @Test
    fun cancelFlow_andRaceWithFill() {
        ok(OrderStatus.OPEN, OrderStatus.CANCEL_PENDING)
        ok(OrderStatus.CANCEL_PENDING, OrderStatus.CANCELLED)
        ok(OrderStatus.CANCEL_PENDING, OrderStatus.FILLED)
        ok(OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCELLED)
    }

    @Test
    fun terminalStates_cannotChange() {
        for (t in listOf(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.FAILED)) {
            assertTrue(t.isTerminal)
            for (to in OrderStatus.values()) no(t, to)
        }
    }

    @Test
    fun illegalJumps() {
        no(OrderStatus.CREATED, OrderStatus.FILLED)
        no(OrderStatus.OPEN, OrderStatus.CREATED)
        no(OrderStatus.OPEN, OrderStatus.OPEN)
        no(OrderStatus.OPEN, OrderStatus.REJECTED)
    }

    @Test
    fun require_throwsOnIllegal() {
        try {
            OrderStateMachine.require(OrderStatus.FILLED, OrderStatus.OPEN)
            throw AssertionError("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("Illegal order transition FILLED -> OPEN", e.message)
        }
    }

    @Test
    fun activeStates() {
        assertTrue(OrderStatus.OPEN.isActive)
        assertTrue(OrderStatus.PARTIALLY_FILLED.isActive)
        assertFalse(OrderStatus.FILLED.isActive)
    }
}
