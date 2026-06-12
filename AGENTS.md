# Agent notes — NowDoing JetBrains plugin

Kotlin / IntelliJ Platform Gradle Plugin 2.x project. JVM toolchain 21,
since-build 242 (2024.2+). No bundled runtime dependencies — Gson, the Kotlin
stdlib and kotlinx-coroutines come from the IDE (`compileOnly` /
`kotlin.stdlib.default.dependency=false`); never package them into the zip.

## Commands

```sh
./gradlew test                  # 76+ unit & protocol tests, pure JVM
./gradlew buildPlugin           # zip to build/distributions/
./gradlew verifyPluginStructure # fast structural check (CI)
./gradlew verifyPlugin          # full Plugin Verifier (slow, downloads IDEs)
./gradlew runIde                # sandbox IDE for manual testing (macOS only)
```

## Layout

- `core/` — platform-free protocol code (discovery, HMAC signing, HTTP/1.1
  over UDS, retry/debounce). Fully unit-tested; keep it free of IntelliJ
  imports so tests stay headless.
- `app/`, `settings/`, `ui/`, `actions/`, `git/` — the IntelliJ layer.
- `src/test/.../protocol/` — `FakeUdsServer` ports the Swift
  `BranchChangeServer` verification rules; `ProtocolTest` runs the real
  client stack against it.

## Protocol sync rule

The wire contract (canonical string `METHOD\nPATH\nTS\nNONCE\nBODYHASH`,
`X-Clessira-*` headers, `api-endpoint.json` v1) is shared with the Mac app
(`BranchChangeServer.swift`), the VS Code extension and the JS/Python SDKs.
The behavioral source of truth for this plugin is the VS Code extension
(`Clessira/vscode`, `src/extension.ts`) — when the protocol or feature set
changes there, mirror it here and keep `AuthTest`'s golden vectors plus
`ProtocolTest` in lockstep. In the NowDoing superproject, the
`wire-protocol-change` skill drives that propagation.

## Behavior parity notes

- Capability file is read on EVERY request, never cached.
- Initial branch is seeded, never announced; detached HEAD never fires but
  updates the last-known branch; bursts coalesce to (final, directly
  preceding) — pinned in `BranchWatcherTest`.
- Branch retries: 1s/2s/4s/8s, max 4 attempts, dedupe by (repoPath, branch).
- All network I/O on `Dispatchers.IO`; UI updates on EDT.
- `SystemInfo.isMac` gates everything user-visible; the plugin must stay
  installable and silent on Windows/Linux.
