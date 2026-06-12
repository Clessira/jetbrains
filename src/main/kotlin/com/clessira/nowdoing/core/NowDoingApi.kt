package com.clessira.nowdoing.core

import java.io.IOException
import java.nio.file.Path

/** Non-2xx response from the local API; message format mirrors `util.ts errorMessageFromResponse`. */
class NowDoingHttpException(val status: Int, message: String) : IOException(message)

/**
 * Capability discovery + signing + transport for one request. Port of
 * `extension.ts requestNowDoing`: the capability file is read on every
 * request (never cached), and any read failure means "app not reachable".
 */
class ApiTransport(
    private val capabilityPath: () -> Path = { Capability.filePath() },
    private val http: UdsHttpClient = UdsHttpClient(),
) {
    fun request(method: String, pathWithQuery: String, body: ByteArray? = null): HttpResponse {
        val cap = Capability.read(capabilityPath())
        val auth = Auth.buildAuthHeaders(method, pathWithQuery, body, cap.token)
        val headers = linkedMapOf(
            "X-Clessira-Token" to cap.token,
            "X-Clessira-Timestamp" to auth.timestamp,
            "X-Clessira-Nonce" to auth.nonce,
            "X-Clessira-Signature" to auth.signature,
        )
        return http.request(cap.socketPath, method, pathWithQuery, headers, body)
    }
}

/**
 * Typed, blocking API surface over the loopback protocol — the JVM
 * counterpart of the request helpers in `extension.ts`. Callers run these on
 * a background dispatcher.
 */
class NowDoingApi(private val transport: ApiTransport = ApiTransport()) {

    /** Returns the HTTP status (2xx) or throws. */
    fun healthcheck(): Int {
        val response = transport.request("GET", Endpoints.HEALTHCHECK)
        requireSuccess(response)
        return response.status
    }

    /** Returns the HTTP status (2xx) or throws. */
    fun postBranchChange(body: BranchChangeBody): Int {
        val response = transport.request("POST", Endpoints.BRANCH_CHANGED, Wire.toJsonBytes(body))
        requireSuccess(response)
        return response.status
    }

    fun searchActivities(query: String, limit: Int = 20): List<ActivitySearchItem> {
        val path = Endpoints.buildActivitySearchPath(query, limit)
        val response = transport.request("GET", path)
        requireSuccess(response)
        val payload = Wire.parseJson(response.bodyText, ActivitySearchResponse::class.java)
        return payload?.items ?: throw IOException("invalid search response")
    }

    fun startActivity(body: ActivityStartBody): ActivityStartResult {
        val response = transport.request("POST", Endpoints.ACTIVITY_START, Wire.toJsonBytes(body))
        requireSuccess(response)
        val payload = Wire.parseJson(response.bodyText, ActivityStartResponse::class.java)
        return payload?.result ?: throw IOException("invalid start response")
    }

    /** Null when nothing is currently tracked. */
    fun current(): CurrentActivityResult? {
        val response = transport.request("GET", Endpoints.CURRENT)
        requireSuccess(response)
        return Wire.parseJson(response.bodyText, CurrentActivityResponse::class.java)?.result
    }

    private fun requireSuccess(response: HttpResponse) {
        if (response.status < 200 || response.status >= 300) {
            throw NowDoingHttpException(
                response.status,
                Wire.errorMessageFromResponse(response.status, response.bodyText),
            )
        }
    }
}
