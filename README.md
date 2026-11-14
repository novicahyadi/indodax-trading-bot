# Indodax Trading Bot (Android)

Local-only Android bot for BTC/IDR using MACD. Paper mode first. See DEVELOPMENT_STATUS.md for what exists and what does not.

**Status: early skeleton, never compiled yet. Do not use with real money.**

## Build from your phone (no PC)
1. In the phone browser, create a GitHub repository and upload this folder's contents (GitHub's web "Add file > Upload files" works; keep the `.github/workflows/build.yml` path).
2. Open the repo's **Actions** tab, pick **Build APK**, tap **Run workflow**.
3. When it finishes, open the run and download the artifact `indodax-bot-debug-apk` (a zip). Unzip it with your file manager and install the APK. Allow "install unknown apps" for your browser or file manager when asked.
4. If the build fails, open the failed step's log and send me the error text.

GitHub's free quota for private repos is limited; public repos are free but never commit keys.

## API key safety
- Create a key on indodax.com/trade_api with **trade (and view) only**. Never enable withdrawal.
- Never put the key or secret in the repo. The app will store them encrypted with Android Keystore.
- Release signing uses GitHub Secrets (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), not files in the repo.

## Troubleshooting
- Build fails on versions: share the log; pinned versions may need adjusting.
- App closes in background: Xiaomi settings guide will be added in Phase 5.
