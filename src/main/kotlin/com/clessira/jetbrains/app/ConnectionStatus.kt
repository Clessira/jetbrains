package com.clessira.jetbrains.app

/** Mirrors the VS Code extension's `ConnectionStatus`, plus the non-macOS guard state. */
enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    CHECKING,
    NEEDS_APP,
    UNSUPPORTED_OS,
}
