package com.indodaxbot.trading

import com.indodaxbot.strategy.ExecutionConfig
import com.indodaxbot.strategy.ExecutionMode
import com.indodaxbot.strategy.SignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPlannerTest {
    private val maker = ExecutionConfig()
    private val taker = ExecutionConfig(mode = ExecutionMode.MARKET_TAKER)
    private val candle = SignalCandle(low = 1_000_000_000, high = 1_020_000_000)
    private val m = Market(last = 1_010_000_000, ask = 1_011_000_000, bid = 1_009_000_000)
    private val flat = CycleView()

    private fun holding(filledPct: Double = 100.0, buyId: String? = null, held: Long = 10_000_000) = CycleView(
        positionId = 1, heldSats = held, avgEntryPrice = 1_000_000_000,
        buyOrderId = buyId, buyLimitPrice = 1_000_000_000, buyFilledPct = filledPct
    )

    private fun market(bid: Long) = Market(last = bid, ask = bid + 1000, bid = bid)

    // ---- maker, signals
    @Test fun maker_buy_placesLimitAtSignalLow() =
        assertEquals(listOf<Action>(Action.LimitBuy(1_000_000_000, 1_000_000)), ExecutionPlanner.onSignal(SignalType.BUY, flat, candle, m, maker))

    @Test fun maker_buy_skippedWhenLowNotBelowAsk() {
        val r = ExecutionPlanner.onSignal(SignalType.BUY, flat, candle, market(990_000_000), maker)
        assertTrue(r.single() is Action.Skip)
    }

    @Test fun buy_ignoredWhenPositionOpen() =
        assertTrue(ExecutionPlanner.onSignal(SignalType.BUY, holding(), candle, m, maker).single() is Action.Skip)

    @Test fun maker_sell_cancelsThenLimitSellAtHigh() =
        assertEquals(
            listOf(Action.CancelAllOrders, Action.LimitSell(1_020_000_000)),
            ExecutionPlanner.onSignal(SignalType.SELL, holding(50.0, "b"), candle, m, maker)
        )

    @Test fun maker_sell_marketWhenBidAlreadyAboveHigh() {
        val r = ExecutionPlanner.onSignal(SignalType.SELL, holding(), candle, market(1_030_000_000), maker)
        assertTrue(r.last() is Action.MarketSellAll)
    }

    @Test fun sell_withNothingFilled_cancelsPendingBuy() {
        val c = holding(0.0, "b", held = 0)
        assertTrue(ExecutionPlanner.onSignal(SignalType.SELL, c, candle, m, maker).single() is Action.CancelBuyOrder)
    }

    @Test fun sell_withoutPosition_isIgnored() =
        assertTrue(ExecutionPlanner.onSignal(SignalType.SELL, flat, candle, m, maker).single() is Action.Skip)

    // ---- taker, signals
    @Test fun taker_buy_isMarketBuy() =
        assertEquals(listOf<Action>(Action.MarketBuy(1_000_000)), ExecutionPlanner.onSignal(SignalType.BUY, flat, candle, m, taker))

    @Test fun taker_sell_marketSellsEverything() =
        assertEquals(
            listOf(Action.CancelAllOrders, Action.MarketSellAll("SELL signal")),
            ExecutionPlanner.onSignal(SignalType.SELL, holding(), candle, m, taker)
        )

    // ---- taker, minimum take profit / maximum stop loss gate on SELL signals
    private val minMax = taker.copy(takerUseMinMax = true, takerMinTakeProfitPct = 3.0, takerMaxStopLossPct = 2.0)

    private fun sellAt(pct: Double, ex: ExecutionConfig) =
        ExecutionPlanner.onSignal(SignalType.SELL, holding().copy(netPnlPct = pct), candle, m, ex)

    @Test fun minMax_profitBelowMinimum_sellIgnored() =
        assertTrue(sellAt(2.0, minMax).single() is Action.Skip)

    @Test fun minMax_profitAtOrAboveMinimum_sells() {
        assertTrue(sellAt(3.0, minMax).last() is Action.MarketSellAll)
        assertTrue(sellAt(4.5, minMax).last() is Action.MarketSellAll)
    }

    @Test fun minMax_smallLoss_sellIgnored() {
        assertTrue(sellAt(-1.2, minMax).single() is Action.Skip)
        assertTrue(sellAt(0.0, minMax).single() is Action.Skip)
    }

    @Test fun minMax_lossBeyondMaximum_sells() {
        assertTrue(sellAt(-2.0, minMax).last() is Action.MarketSellAll)
        assertTrue(sellAt(-5.0, minMax).last() is Action.MarketSellAll)
    }

    @Test fun minMax_off_sellFollowsSignalAlways() =
        assertTrue(sellAt(0.5, taker).last() is Action.MarketSellAll)

    @Test fun minMax_neverTriggersExitsOnItsOwn() {
        val c = holding().copy(netPnlPct = 50.0)
        assertTrue(ExecutionPlanner.onTick(c, market(1_500_000_000), minMax).isEmpty())
        assertTrue(ExecutionPlanner.onTick(c.copy(netPnlPct = -40.0), market(500_000_000), minMax).isEmpty())
    }

    // ---- maker, ticks
    @Test fun maker_chaseCancel_whenPriceRises5PercentAndNothingFilled() {
        val c = holding(0.0, "b", held = 0)
        assertTrue(ExecutionPlanner.onTick(c, Market(1_050_000_000, 1_050_001_000, 1_050_000_000), maker).single() is Action.CancelBuyOrder)
        assertTrue(ExecutionPlanner.onTick(c, Market(1_049_000_000, 1_049_001_000, 1_049_000_000), maker).isEmpty())
    }

    @Test fun maker_noChaseCancelOncePartiallyFilled() {
        val c = holding(30.0, "b")
        assertTrue(ExecutionPlanner.onTick(c, Market(1_060_000_000, 1_060_001_000, 1_059_000_000), maker.copy(makerTakeProfitPct = 50.0)).isEmpty())
    }

    @Test fun maker_takeProfit_cancelsRemainderThenSellsAll() {
        val r = ExecutionPlanner.onTick(holding(40.0, "b"), market(1_100_000_000), maker)
        assertEquals(listOf(Action.CancelBuyOrder("take profit"), Action.MarketSellAll("TAKE_PROFIT")), r)
    }

    @Test fun maker_takeProfit_noBuyOrderToCancel() {
        val r = ExecutionPlanner.onTick(holding(), market(1_100_000_000), maker)
        assertEquals(listOf<Action>(Action.MarketSellAll("TAKE_PROFIT")), r)
    }

    @Test fun maker_stopLoss_onlyArmedFromLevel1() {
        val sl = market(940_000_000)   // 6% below entry, SL is 5%
        assertTrue(ExecutionPlanner.onTick(holding(20.0, "b"), sl, maker).isEmpty())      // below level 1 (30%)
        val r = ExecutionPlanner.onTick(holding(30.0, "b"), sl, maker)
        assertEquals(listOf(Action.CancelBuyOrder("stop loss"), Action.MarketSellAll("STOP_LOSS")), r)
        assertEquals(listOf<Action>(Action.MarketSellAll("STOP_LOSS")), ExecutionPlanner.onTick(holding(), sl, maker))
    }

    @Test fun maker_betweenStopAndTakeProfit_doesNothing() =
        assertTrue(ExecutionPlanner.onTick(holding(), market(1_020_000_000), maker).isEmpty())

    @Test fun sellOrderPending_disablesStopAndTakeProfit() {
        val c = holding().copy(sellOrderActive = true)
        assertTrue(ExecutionPlanner.onTick(c, market(900_000_000), maker).isEmpty())
    }

    @Test fun slStage_levels() {
        assertEquals(0, ExecutionPlanner.slStage(29.9, maker))
        assertEquals(1, ExecutionPlanner.slStage(30.0, maker))
        assertEquals(2, ExecutionPlanner.slStage(50.0, maker))
        assertEquals(3, ExecutionPlanner.slStage(80.0, maker))
        assertEquals(4, ExecutionPlanner.slStage(100.0, maker))
    }

    // ---- taker, ticks
    @Test fun taker_slTpOffByDefault_holdsUntilSellSignal() =
        assertTrue(ExecutionPlanner.onTick(holding(), market(500_000_000), taker).isEmpty())

    @Test fun taker_slTpOn_triggersBothWays() {
        val t = taker.copy(takerUseSlTp = true)
        assertEquals(listOf<Action>(Action.MarketSellAll("STOP_LOSS")), ExecutionPlanner.onTick(holding(), market(940_000_000), t))
        assertEquals(listOf<Action>(Action.MarketSellAll("TAKE_PROFIT")), ExecutionPlanner.onTick(holding(), market(1_100_000_000), t))
        assertTrue(ExecutionPlanner.onTick(holding(), market(1_000_000_000), t).isEmpty())
    }

    @Test fun noPosition_noTickActions() =
        assertTrue(ExecutionPlanner.onTick(flat, m, maker).isEmpty())
}
