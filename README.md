<h1 align="center">Clessira for JetBrains IDEs</h1>

<p align="center">
  <a href="https://github.com/Clessira/jetbrains/releases/latest"><img alt="GitHub release" src="https://img.shields.io/github/v/release/Clessira/jetbrains?label=release" /></a>
  <a href="https://github.com/Clessira/jetbrains/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/Clessira/jetbrains/actions/workflows/ci.yml/badge.svg?branch=main" /></a>
  <a href="https://clessira.app"><img alt="Website" src="https://img.shields.io/badge/website-clessira.app-1F1F23" /></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-blue.svg" /></a>
</p>

Notifies the [Clessira](https://clessira.app) macOS app when you switch Git
branches in IntelliJ IDEA, WebStorm, PyCharm or any other JetBrains IDE, so
Clessira can pop up its time-entry prompt. You can also start activities
directly from the IDE.

Requires the Clessira macOS app with the editor integration enabled. The
plugin talks to a Unix-domain socket inside the app's sandbox container and
never sends data over the network. On Windows and Linux the plugin is a no-op.

## Features

- Branch-aware prompts. Switching branches in any open repository triggers a
  Clessira prompt, debounced to avoid spam during rebases.
- Start activities via the `Clessira: Start Activity…` action (Tools menu or
  Find Action) with type-ahead search and create-if-missing.
- Live status-bar readout of the currently tracked activity and elapsed time
  (visibility is controlled via settings).
- Clicking the activity widget opens the activity picker; clicking the
  connection widget opens an action menu (track, test, reconnect, settings,
  logs).
- No network port. All traffic goes through a Unix-domain socket inside the
  Clessira sandbox container and is signed with HMAC plus timestamp and nonce.

## How it works

The plugin listens to the IDE's Git integration (git4idea) for branch
changes. After a short debounce window (default 1.5 s) it `POST`s to a local
Unix-domain socket inside the Clessira app's sandbox container:

```http
POST /branch-changed                       (via UDS, no TCP)
X-Clessira-Token: <from capability file>
X-Clessira-Timestamp: <unix-seconds>
X-Clessira-Nonce: <random-hex>
X-Clessira-Signature: <hmac-sha256>
Content-Type: application/json

{"repo": "Clessira", "repoPath": "/Users/me/dev/Clessira",
 "branch": "feat/auth", "previousBranch": "main"}
```

Clessira opens its prompt popover with the new branch name. A separate
`GET /healthcheck` endpoint is used for reachability checks and never
triggers a prompt.

Discovery is zero-config: the Mac app writes
`~/Library/Containers/com.mattes.nowdoing/Data/api-endpoint.json` (mode 0600)
with the socket path and auth token; the plugin re-reads it on every request.

## Installation

1. In the Clessira macOS app, open Settings and enable the editor (VS Code)
   integration — this starts the local socket the plugin connects to.
2. Install the plugin:
   - from JetBrains Marketplace (search for "Clessira"), or
   - from a [GitHub release](https://github.com/Clessira/jetbrains/releases):
     Settings | Plugins | ⚙ | Install Plugin from Disk… and pick the zip.
3. The status bar shows `✓ Clessira` once the app is reachable.

Requires a 2024.2+ JetBrains IDE.

## Settings

Settings | Tools | Clessira — the keys mirror the VS Code extension:

| Setting | Default | Description |
| --- | --- | --- |
| Notify on branch change | on | Master switch for branch notifications |
| Debounce (ms) | 1500 | Quiet window after a branch change (0–10000) |
| Ignore branches matching | empty | Regex, e.g. `^(main\|master\|develop)$` |
| Show current activity | on | Activity name in the status bar |
| Show elapsed time | on | Elapsed tracking time in the status bar |
| Poll interval (s) | 10 | How often the current activity is fetched (2–120) |

## Development

```sh
./gradlew test                 # unit + wire-protocol tests (fake UDS server)
./gradlew runIde               # launch a sandbox IDE with the plugin
./gradlew buildPlugin          # build the distributable zip
./gradlew verifyPlugin         # Plugin Verifier against recommended IDEs
```

The wire protocol (canonical string, headers, capability file) must stay in
sync with the Mac app's `BranchChangeServer` and the other clients (VS Code
extension, JS/Python SDKs). The OpenAPI spec at
[clessira.app/specs](https://clessira.app/specs/openapi.yaml) is the canonical
reference; `src/test/.../protocol/ProtocolTest.kt` pins the exact format.

## License

[MIT](LICENSE)
