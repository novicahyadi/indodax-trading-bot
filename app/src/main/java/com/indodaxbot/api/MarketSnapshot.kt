package com.indodaxbot.api

/** Ticker data plus the 24h percentage change (null when unknown). */
data class MarketSnapshot(val ticker: Ticker, val change24hPct: Double?)
