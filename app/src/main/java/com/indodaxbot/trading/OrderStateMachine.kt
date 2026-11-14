package com.indodaxbot.trading

enum class OrderStatus {
    CREATED, SUBMITTED, OPEN, PARTIALLY_FILLED, FILLED, CANCEL_PENDING, CANCELLED, REJECTED, FAILED;

    val isTerminal: Boolean get() = this == FILLED || this == CANCELLED || this == REJECTED || this == FAILED
    val isActive: Boolean get() = this == OPEN || this == PARTIALLY_FILLED
}

object OrderStateMachine {
    private val allowed: Map<OrderStatus, Set<OrderStatus>> = mapOf(
        OrderStatus.CREATED to setOf(OrderStatus.SUBMITTED, OrderStatus.REJECTED, OrderStatus.FAILED, OrderStatus.CANCELLED),
        OrderStatus.SUBMITTED to setOf(
            OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
            OrderStatus.REJECTED, OrderStatus.FAILED
        ),
        OrderStatus.OPEN to setOf(
            OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCEL_PENDING, OrderStatus.CANCELLED
        ),
        OrderStatus.PARTIALLY_FILLED to setOf(
            OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCEL_PENDING, OrderStatus.CANCELLED
        ),
        // A cancel request can lose the race against a fill.
        OrderStatus.CANCEL_PENDING to setOf(OrderStatus.CANCELLED, OrderStatus.FILLED, OrderStatus.PARTIALLY_FILLED),
        OrderStatus.FILLED to emptySet(),
        OrderStatus.CANCELLED to emptySet(),
        OrderStatus.REJECTED to emptySet(),
        OrderStatus.FAILED to emptySet()
    )

    fun canTransition(from: OrderStatus, to: OrderStatus): Boolean = allowed[from]?.contains(to) == true

    /** Throws if the transition is not allowed, so a bug can never silently corrupt an order. */
    fun require(from: OrderStatus, to: OrderStatus) {
        check(canTransition(from, to)) { "Illegal order transition $from -> $to" }
    }
}
