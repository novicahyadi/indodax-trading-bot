# Development Status

Last updated: 2026-11-15

## Build status
Phase 1 built successfully on GitHub Actions (confirmed by the user, 2026-11-14).
**Phase 2 code is written but NOT yet compiled or tested.** The next GitHub Actions run is its first check (it runs the unit tests before building the APK).

## Strategy rework: normalized 0-100 indicator, dual signal (written 2026-11-15, NOT yet compiled)
Replaces the raw +/- zones (invalid across coins with different price scales).
- Indicator: MACD and signal line are scaled to 0-100 over a rolling window of `norm_lookback` candles (highest/lowest over both lines; range 0 gives 50/50). Closed candles only.
- WARMING_UP until candles >= macd_slow + macd_signal + norm_lookback (default 85). The service fetches more than that.
- Strategy 1 (reversal zone) and strategy 2 (crossover angle) follow the formulas in the request exactly; each has an on/off switch. `signal_logic_mode` AND/OR. With one switch off, only the active one counts. Choice made for OR: if the two disagree (one BUY, one SELL) the result is HOLD.
- Settings is now in tabs: API, Strategy, Execution, Paper. The timeframe selector moved to Settings > Strategy (and is no longer on the Dashboard card, which only shows a label). All parameters have input validation (zones 0-100, buy zone below sell zone, angle 0-90, EMA ranges, lookback 10-500, at least one strategy on).
- Execution settings (maker/taker, chase-cancel, SL/TP, SL levels 30/50/80, taker SL/TP toggle) are validated and stored, but **nothing uses them yet**: order execution is Phase 5/6.
- Chart: indicator panel has a fixed 0-100 axis, ZONA SELL and ZONA BUY bands (follow the settings) drawn behind the blue MACD and orange signal lines. No new library.
- Signal rows in the DB now store the normalized values (macd, signal, difference). No schema change (DB stays v3).
- Tests: NormalizerTest (new), StrategyTest and SignalEvaluatorTest rewritten; the first BUY/SELL candles on the test wave were cross-checked with an independent Python calculation.
Open points for the execution phase: Indodax cannot create STOP_LOSS_LIMIT orders, so the staged stop loss must be synthetic (only works while the phone and service are online); selling at the signal candle's High after cancelling the SL leaves the position unprotected until it fills; weekly/3-day candle alignment is unverified; the early part of a series is seeded EMA, so 85 candles is a minimum, not a guarantee of accuracy.

## Phase 4: Paper trading (written 2026-11-14, NOT yet compiled)
Phase 3 was confirmed on the phone (HOLD signal, MACD - signal = histogram checks out, candle time = open time confirmed).
- All money is exact integers: IDR as whole rupiah, BTC as satoshis (`trading/Money.kt`). No floating point in balances, fees or P/L.
- `trading/PositionMath.kt`: weighted average entry, realized P/L (average-cost, buy fees included in cost), unrealized P/L valued at the bid minus estimated sell fee, P/L from the first partial fill. OPEN/CLOSED and FILLING/HOLDING are derived from quantities, independent of order states.
- `trading/OrderStateMachine.kt`: CREATED, SUBMITTED, OPEN, PARTIALLY_FILLED, FILLED, CANCEL_PENDING, CANCELLED, REJECTED, FAILED with enforced transitions.
- `trading/PaperBroker.kt`: market buy/sell, limit buy/sell (fill when the market crosses; partial fills in 50% steps), cancel, reserved IDR/BTC, no sell above available BTC, minimum order Rp50,000. Each operation is one DB transaction. PAPER data is stored with `mode = PAPER` and every query filters on it.
- Room DB v3: Position, TradingOrder (unique client order id), TradeExecution, PaperBalance with foreign keys and indexes. Versions 1-2 may be wiped; from v3 on a schema change needs a real Migration (Room will refuse to open otherwise).
- UI: Positions screen (cards, badges, expandable orders, ALL/OPEN/CLOSED filters, required sorting) with temporary paper test buttons; Settings has the paper balance reset (two taps to confirm).
- Limit orders are only matched while the Positions screen is open (30 s). Real background matching comes with the Foreground Service in Phase 5.

Known gaps / honest limits:
1. Fee rates are placeholders (taker 0.51%, maker 0.30%) and fees are charged in IDR; real Indodax may charge in the received asset. Compare with a real trade history before trusting P/L.
2. Not done yet: PaperTransaction table (executions serve as history for now), Portfolio snapshots and the 24 h / daily / all-time portfolio summary (all-time realized query exists), TP/SL fields are stored but nothing sets them yet, MACD signals do not trigger paper trades yet (Phase 5).
3. A tiny leftover amount below Rp50,000 cannot be sold (same rule as the exchange) and keeps a position open.
4. Paper-broker database operations have no automated tests yet (they need a device or Robolectric); the pure math and state machine do.

## Phase 3: MACD engine (written 2026-11-14, built and confirmed on phone)
- `strategy/Macd.kt`: EMA (seeded with simple average), MACD 12/26/9, crossover rules exactly as in the spec.
- `strategy/SignalEvaluator.kt`: closed candles only; needs 87 candles warm-up; blocks signals on gaps/invalid prices; a signal is `actionable` only if its candle is new (not processed before) AND closed less than one timeframe ago. Stale signals are displayed but never traded.
- `strategy/SignalService.kt`: fetches 200 candles, saves signal + last-processed-candle cursor in one DB transaction; unique index prevents two signals for the same candle.
- Room DB is now v2 with `fallbackToDestructiveMigration` (acceptable only while no financial data is stored; MUST be replaced by real migrations in Phase 4).
- Dashboard: MACD signal card (BUY/SELL/HOLD, MACD, signal line, histogram, last signal, timeframe 15m), re-evaluated shortly after each candle close while the app is open.
- Timeframe is fixed at 15m for now; the Settings option comes with the bot configuration.
- The test expectations were cross-checked with an independent Python version of the same math (EMA values, buy/sell crossovers on a test wave).
- Doubts: candle `Time` is assumed to be the open time; if live candles look shifted by one, the freshness check needs adjusting.

## Phase 2: Indodax API (written 2026-11-14, unverified)
- `api/IndodaxClient`: OkHttp, HTTPS only, timeouts, rate gate. Public: ticker, candles. Signed reads: account, openOrders, order (by id or clientOrderId), testConnection. **No order placement or cancel yet (Phase 6).**
- `api/Signer`: HMAC-SHA256 hex over the exact query string that is sent.
- `api/Parsers`, `api/Candles` (closed-candle filter), `api/Models` (Credentials toString is redacted; money uses BigDecimal).
- `security/CredentialStore`: AES-256-GCM key in Android Keystore, ciphertext only in SharedPreferences.
- UI: Settings (save, test connection, remove, warns if the key has withdraw permission) and a Dashboard price view.
- Tests: signer (RFC 4231 vector), query builder, parsers, closed-candle logic.
- Retrofit was not added: OkHttp alone is enough and avoids an extra dependency.

Known doubts to resolve on the first real run:
1. The docs say HMAC-SHA256, but their sample output is 128 hex characters (SHA-512 length). If "Test API connection" returns a signature error (-1022), try SHA-512.
2. Candle `Time` is assumed to be the candle open time.
3. Account `balances[].asset` casing is normalized to upper case.
4. Real order status values (partial fill) still unconfirmed.

## Phase 1: Project foundation
Done:
- Gradle config with pinned versions: AGP 8.5.2, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Gradle 8.9 (installed by the CI workflow)
- Manifest, Compose Material 3 shell with Dashboard / Positions / Settings bottom navigation
- Room database with one entity (BotEvent)
- GitHub Actions workflow: unit tests, debug APK, artifact upload

Pending in Phase 1:
- Remaining Room entities, DataStore, dark/light theme, Gradle wrapper (optional, CI uses setup-gradle)
- Confirm the first cloud build succeeds

## Indodax API findings (official docs: github.com/btcid/indodax-official-api-docs)
Read the docs on 2026-11-14. Verify again before Phase 2.

**Two private APIs exist.** TAPI v2 is the current one. The legacy `https://indodax.com/tapi` is documented as headed for decommissioning (no date announced), and its `tradeHistory` and `orderHistory` methods were scheduled to be removed on 2026-04-07. **Use TAPI v2 only.**

TAPI v2:
- Base URL `https://api.indodax.com/`
- Headers `X-APIKEY` and `Sign`. Signature is HMAC-SHA256 (hex) of the query string / form body using the secret. Requires `timestamp` (ms), optional `recvWindow` (default 5000).
- Needs a dedicated TAPI v2 key; old keys do not work.
- Trade permission **requires an IP whitelist**. See risk below.
- Endpoints: `POST /api/v2/order`, `DELETE /api/v2/order`, `GET /api/v2/order`, `GET /api/v2/openOrders`, `GET /api/v2/account`, `GET /api/v2/order/histories`, `GET /api/v2/myTrades`
- Order creation supports `LIMIT` and `MARKET` only. **STOP_LIMIT is explicitly rejected on creation**, and no OCO is documented. So stop-loss must be synthetic (local price monitoring).
- MARKET BUY takes `quoteOrderQty` in IDR; MARKET SELL and LIMIT take `quantity`.
- Supports `newClientOrderId` (max 36 chars, alphanumeric `_-`). Duplicates are rejected, which helps duplicate-order prevention.
- Order status carries `origQty`, `executedQty`, `status` (NEW seen in docs; history shows FILLED / CANCELLED / REJECTED). Exact partial-fill status enum is on the docs' enums page, which I have not read yet.
- A successful create response may not be immediately visible via `GET /api/v2/order`. After a timeout, check by `clientOrderId` before retrying.
- Trade fills (`myTrades`) include `commission` and `commissionAsset`, usable for fee accounting.
- Rate limits: create order 20 req/s per pair, cancel 30 req/s, most reads 300 req/min per IP (the docs also mention 10 req/10 s for some general info, so keep the client conservative). Public API 180 req/min. Exceeding the trade limit triggers a 5-second block.
- Private WebSocket order updates exist (needs a token from `POST https://indodax.com/api/private_ws/v1/generate_token`). Not planned until after REST polling works.

Public API (`https://indodax.com`):
- Ticker `/api/ticker/btcidr`, pairs `/api/pairs` (BTC/IDR min order 50,000 IDR or 0.0001 BTC per the docs example), `/api/price_increments`
- Candles: `/tradingview/history_v2?symbol=BTCIDR&tf=15&from=<unix s>&to=<unix s>`, timeframes 1, 15, 30, 60, 240, 1D, 3D, 1W. Response fields Time, Open, High, Low, Close, Volume. Volume can be "0" on empty candles. Candles need a closed-candle check, since the latest one can still be forming.

## Open risks and decisions for you
1. **IP whitelist vs phone.** Trade keys require registered IPs. A phone on mobile data or changing Wi-Fi has a changing IP, so live orders may be rejected (`-2015`). This is the biggest threat to a phone-only live bot. Paper trading is unaffected. Decide before Phase 6; options include a fixed home IP or confirming Indodax's current IP rules.
2. **Synthetic stop-loss** only works while the phone is online and the service is alive. If connectivity or MIUI kills the service, no stop-loss is active.
3. The task spec said "never request withdrawal permissions". The app will only ever call trade and read endpoints, and the README will tell you to create a key without withdraw permission.
4. I only read the first part of INDODAX-TradeAPI-2.md (the last ~1,200 characters, the rest of the trade-history fields, were not read) and have not read the enums page, so the status values are not final.

## Next steps
1. Upload the Phase 2 files, let the workflow run, fix any compile or test errors.
2. Install the APK, save a read/trade key, tap "Test API connection" and report the result.
3. Phase 3: MACD engine (EMA, MACD, crossover, duplicate-signal prevention, signal history).
