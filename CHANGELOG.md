# Changelog

All notable changes to the NowDoing JetBrains plugin are documented here.

## 0.1.0 - 2026-06-12

### Added

- Initial release with full feature parity to the NowDoing VS Code extension.
- Branch-aware prompts: switching Git branches in any open repository notifies
  the NowDoing macOS app (debounced, with an ignore-pattern setting and an
  in-memory retry queue with exponential backoff).
- `Start Activity…` action with type-ahead search and create-if-missing,
  available from Tools | NowDoing, Find Action and the status bar.
- Status bar widgets for connection state (click for the quick action menu)
  and the currently tracked activity with elapsed time.
- Settings under Settings | Tools | NowDoing mirroring the VS Code extension's
  configuration (enabled, debounce, ignore pattern, status bar toggles,
  poll interval).
- Zero-config discovery via the app's `api-endpoint.json` capability file and
  HMAC-SHA256-signed requests over the sandboxed Unix domain socket — no TCP,
  no bundled dependencies.
