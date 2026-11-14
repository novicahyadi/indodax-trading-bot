# Development Status

Last updated: 2026-11-14

## Build status
**NOT YET COMPILED.** No Gradle build has run. The first GitHub Actions run is the first real compile check, so expect version or syntax fixes.

## Phase 1: Project foundation
Done (written, unverified):
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
1. Push to GitHub and run the workflow; fix any compile errors.
2. Phase 2: Retrofit/OkHttp client, HMAC signer with unit tests (use the doc's example vectors), Keystore credential storage.
