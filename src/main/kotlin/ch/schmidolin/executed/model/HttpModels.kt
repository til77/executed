package ch.schmidolin.executed.model

import java.io.File
import java.nio.charset.StandardCharsets

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE;

    companion object {
        fun fromString(value: String): HttpMethod? =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

enum class AuthType() {
    NONE(),
    BASIC(),
    BEARER(),
    API_KEY()
}

data class AuthConfig(
    var type: AuthType = AuthType.NONE,
    var username: String = "",
    var password: String = "",
    var token: String = "",
    var apiKeyName: String = "X-API-Key",
    var apiKeyValue: String = "",
    var apiKeyInQuery: Boolean = false
)

data class KeyValueItem(
    var key: String,
    var value: String,
    var enabled: Boolean = true
)

data class RequestOptions(
    var followRedirects: Boolean = true,
    var verifySsl: Boolean = true,
    var timeoutMillis: Long = 30_000L,
    var saveToFile: String? = null,
    var forceOverwriteFile: Boolean = false,
    var noCookieJar: Boolean = false
)

data class HttpRequestSpec(
    var id: String,
    var name: String = "",
    var method: HttpMethod = HttpMethod.GET,
    var rawUrl: String = "https://httpbin.org/get",
    var httpVersion: String? = null,
    var headers: MutableList<KeyValueItem> = mutableListOf(),
    var queryParams: MutableList<KeyValueItem> = mutableListOf(),
    var auth: AuthConfig = AuthConfig(),
    var body: String = "",
    var bodyFileReference: String? = null,
    var responseHandlerScript: String? = null,
    var options: RequestOptions = RequestOptions(),
    var startLine: Int = 0, // 0-based line index of the ### or first line of block
    var requestLine: Int = 0, // 0-based line index of the actual METHOD URL line
    var requestColumn: Int = 0, // 0-based column index before the HTTP verb on requestLine
    var endLine: Int = 0 // 0-based inclusive end line index
) {
    val displayTitle: String
        get() = name.ifBlank { "${method.name} ${baseUrlWithoutQuery()}" }

    fun baseUrlWithoutQuery(): String {
        val qIdx = rawUrl.indexOf('?')
        return if (qIdx >= 0) rawUrl.substring(0, qIdx) else rawUrl
    }
}

data class HttpDocument(
    val variables: Map<String, String>,
    val requests: List<HttpRequestSpec>
)

data class RequestTimings(
    val dnsDurationMs: Double = 0.0,
    val tcpConnectDurationMs: Double = 0.0,
    val tlsHandshakeDurationMs: Double = 0.0,
    val requestSentDurationMs: Double = 0.0,
    val serverAcceptAndWaitDurationMs: Double = 0.0, // TTFB: time from request sent until server responds with first header byte
    val responseTransferDurationMs: Double = 0.0,
    val totalDurationMs: Double = 0.0
) {
    val timeUntilServerAcceptsMs: Double
        get() = dnsDurationMs + tcpConnectDurationMs + tlsHandshakeDurationMs + requestSentDurationMs + serverAcceptAndWaitDurationMs
}

enum class ResponseContentCategory {
    TEXT,
    JSON,
    XML,
    HTML,
    IMAGE,
    PDF,
    BINARY
}

data class ExecutedHttpRequest(
    val method: String,
    val url: String,
    val requestTarget: String,
    val protocol: String = "HTTP/1.1",
    val headers: List<Pair<String, String>> = emptyList(),
    val bodyBytes: ByteArray = ByteArray(0),
    val contentType: String? = null
) {
    fun toWireFormatString(): String = buildString {
        append(method).append(' ').append(url).append(' ').append(protocol).append('\n')
        for ((name, value) in headers) {
            append(name).append(": ").append(value).append('\n')
        }
        if (bodyBytes.isNotEmpty()) {
            append('\n')
            append(String(bodyBytes, StandardCharsets.UTF_8))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExecutedHttpRequest

        if (method != other.method) return false
        if (url != other.url) return false
        if (requestTarget != other.requestTarget) return false
        if (protocol != other.protocol) return false
        if (headers != other.headers) return false
        if (!bodyBytes.contentEquals(other.bodyBytes)) return false
        if (contentType != other.contentType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + requestTarget.hashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

data class ExecutedHttpResponse(
    val protocol: String = "HTTP/1.1",
    val statusCode: Int,
    val statusMessage: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val bodyBytes: ByteArray = ByteArray(0),
    val rawWireBodyBytes: ByteArray = bodyBytes,
    val contentType: String? = null
) {
    fun toWireFormatString(): String = buildString {
        append(protocol).append(' ').append(statusCode)
        if (statusMessage.isNotBlank()) {
            append(' ').append(statusMessage)
        }
        append('\n')
        for ((name, value) in headers) {
            append(name).append(": ").append(value).append('\n')
        }
        if (bodyBytes.isNotEmpty()) {
            append('\n')
            append(String(bodyBytes, StandardCharsets.UTF_8))
        }
    }
}

data class HttpResponseResult(
    val requestSpec: HttpRequestSpec,
    val resolvedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val statusMessage: String,
    val protocol: String,
    val headers: Map<String, List<String>>,
    val bodyBytes: ByteArray,
    val contentType: String,
    val category: ResponseContentCategory,
    val timings: RequestTimings,
    val savedFile: File? = null,
    val redirectCount: Int = 0,
    val tlsCipherSuite: String? = null,
    val errorMessage: String? = null,
    val scriptOutput: List<String> = emptyList(),
    val executedRequest: ExecutedHttpRequest? = null,
    val executedRequests: List<ExecutedHttpRequest> = emptyList(),
    val executedResponse: ExecutedHttpResponse? = null,
    val executedResponses: List<ExecutedHttpResponse> = emptyList()
) {
    val isSuccess: Boolean
        get() = errorMessage == null && statusCode in 200..299

    val bodySizeFormatted: String
        get() {
            val size = bodyBytes.size
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
                else -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            }
        }

    fun formatWireRequestText(): String {
        return when {
            executedRequests.size > 1 -> executedRequests.mapIndexed { idx, req ->
                val label = if (idx == executedRequests.lastIndex) "Final Request" else "Redirect Hop ${idx + 1}"
                "### Hop ${idx + 1} ($label)\n${req.toWireFormatString().trimEnd()}"
            }.joinToString("\n\n")
            executedRequest != null -> executedRequest.toWireFormatString()
            else -> buildString {
                append(requestSpec.method.name).append(' ')
                    .append(finalUrl.ifBlank { resolvedUrl.ifBlank { requestSpec.rawUrl } }).append(' ')
                    .append(requestSpec.httpVersion ?: "HTTP/1.1").append('\n')
                for (h in requestSpec.headers.filter { it.enabled && it.key.isNotBlank() }) {
                    append(h.key).append(": ").append(h.value).append('\n')
                }
                if (requestSpec.body.isNotEmpty()) {
                    append('\n').append(requestSpec.body)
                }
            }
        }
    }

    fun formatWireResponseText(): String {
        return when {
            executedResponses.size > 1 -> executedResponses.mapIndexed { idx, resp ->
                val label = if (idx == executedResponses.lastIndex) "Final Response" else "Redirect Response ${idx + 1}"
                "### Hop ${idx + 1} ($label)\n${resp.toWireFormatString().trimEnd()}"
            }.joinToString("\n\n")
            executedResponse != null -> executedResponse.toWireFormatString()
            else -> buildString {
                append(protocol).append(' ').append(statusCode)
                if (statusMessage.isNotBlank()) {
                    append(' ').append(statusMessage)
                }
                append('\n')
                for ((k, values) in headers) {
                    for (v in values) {
                        append(k).append(": ").append(v).append('\n')
                    }
                }
                if (bodyBytes.isNotEmpty()) {
                    append('\n')
                    append(String(bodyBytes, StandardCharsets.UTF_8))
                }
            }
        }
    }
}

data class LoadTestConfig(
    val totalRequests: Int = 100,
    val parallelism: Int = 10,
    val rampUpSeconds: Double = 0.0,
    val delayBetweenRequestsMs: Long = 0L,
    val reuseConnectionPool: Boolean = true
)

data class LoadSampleResult(
    val requestIndex: Int, // 1-based execution index
    val threadId: Int,
    val startOffsetMs: Double,
    val completedOffsetMs: Double,
    val statusCode: Int,
    val isSuccess: Boolean,
    val payloadBytes: Int,
    val timings: RequestTimings,
    val errorMessage: String? = null,
    val responseResult: HttpResponseResult? = null
)

data class MetricStats(
    val minMs: Double,
    val maxMs: Double,
    val meanMs: Double,
    val medianMs: Double,
    val stdDevMs: Double,
    val p50Ms: Double,
    val p75Ms: Double,
    val p90Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double
)

data class HistogramBucket(
    val rangeLabel: String,
    val minMs: Double,
    val maxMs: Double,
    val count: Int,
    val fraction: Double
)

data class LoadTestStatistics(
    val config: LoadTestConfig,
    val requestSpec: HttpRequestSpec,
    val resolvedUrl: String,
    val totalPlanned: Int,
    val completedCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val activeThreads: Int,
    val elapsedDurationMs: Double,
    val requestsPerSecond: Double,
    val throughputBytesPerSecond: Double,
    val totalBytesReceived: Long,
    val totalDurationStats: MetricStats,
    val ttfbStats: MetricStats,
    val serverWaitStats: MetricStats,
    val transferStats: MetricStats,
    val connectStats: MetricStats,
    val statusCodeCounts: Map<Int, Int>,
    val errorCounts: Map<String, Int>,
    val histogram: List<HistogramBucket>,
    val samples: List<LoadSampleResult>,
    val isFinished: Boolean,
    val isCancelled: Boolean = false
)

