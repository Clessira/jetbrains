# Changelog

All notable changes to the Clessira JetBrains plugin are documented here.

## 0.1.1 - 2026-06-13

### Changed

- Release pipeline: tag-driven semantic versioning — push `vX.Y.Z` and the
  version is injected into the build, a GitHub Release is cut, and the plugin
  is published to the JetBrains Marketplace via GitHub Actions.

### Fixed

- Activity picker: run the search debounce on the EDT so the stale-response
  guard (`requestVersion`/`busy`) and the `search.text` read are no longer
  touched from a background thread (`ActivityPickerPopup`).

## 0.1.0 - 2026-06-12

### Added

- Initial release with full feature parity to the Clessira VS Code extension.
- Branch-aware prompts: switching Git branches in any open repository notifies
  the Clessira macOS app (debounced, with an ignore-pattern setting and an
  in-memory retry queue with exponential backoff).
- `Start Activity…` action with type-ahead search and create-if-missing,
  available from Tools | Clessira, Find Action and the status bar.
- Status bar widgets for connection state (click for the quick action menu)
  and the currently tracked activity with elapsed time.
- Settings under Settings | Tools | Clessira mirroring the VS Code extension's
  configuration (enabled, debounce, ignore pattern, status bar toggles,
  poll interval).
- Zero-config discovery via the app's `api-endpoint.json` capability file and
  HMAC-SHA256-signed requests over the sandboxed Unix domain socket — no TCP,
  no bundled dependencies.
