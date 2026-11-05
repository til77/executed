package ch.schmidolin.executed.http

import ch.schmidolin.executed.model.*
import ch.schmidolin.executed.parser.HttpFileParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.GzipSource
import okio.buffer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.extension
import kotlin.io.nameWithoutExtension
import kotlin.io.use
import kotlin.io.writeBytes

class HttpExecutor(
    var outputDirectory: File = File(System.getProperty("user.dir"), "output"),
    var workingDirectory: File = File(System.getProperty("user.dir"))
) {
    val dynamicVariables = ConcurrentHashMap<String, String>()

    fun execute(
        spec: HttpRequestSpec,
        documentVariables: Map<String, String>,
        sharedClient: OkHttpClient? = null,
        saveFiles: Boolean = true
    ): HttpResponseResult {
        val allVars = LinkedHashMap<String, String>()
        allVars.putAll(documentVariables)
        allVars.putAll(dynamicVariables)

        val resolvedUrl = resolveEffectiveUrl(spec, allVars)
        val timingCollector = TimingEventListener()
        val capturedWireRequests = CopyOnWriteArrayList<ExecutedHttpRequest>()
        val capturedWireResponses = CopyOnWriteArrayList<ExecutedHttpResponse>()
        val wireCaptureInterceptor = Interceptor { chain ->
            val netReq = chain.request()
            val protocolStr = when (chain.connection()?.protocol()) {
                Protocol.HTTP_1_0 -> "HTTP/1.0"
                Protocol.HTTP_1_1 -> "HTTP/1.1"
                Protocol.HTTP_2, Protocol.H2_PRIOR_KNOWLEDGE -> "HTTP/2"
                Protocol.QUIC -> "HTTP/3"
                else -> spec.httpVersion ?: "HTTP/1.1"
            }
            capturedWireRequests.add(captureOkHttpRequest(netReq, protocolStr))

            val netResponse = chain.proceed(netReq)
            val respProto = when (netResponse.protocol) {
                Protocol.HTTP_1_0 -> "HTTP/1.0"
                Protocol.HTTP_1_1 -> "HTTP/1.1"
                Protocol.HTTP_2, Protocol.H2_PRIOR_KNOWLEDGE -> "HTTP/2"
                Protocol.QUIC -> "HTTP/3"
                else -> netResponse.protocol.toString().uppercase()
            }
            val respHeaders = (0 until netResponse.headers.size).map { idx ->
                netResponse.headers.name(idx) to netResponse.headers.value(idx)
            }
            val statusMsg = netResponse.message.ifBlank { defaultStatusPhrase(netResponse.code) }
            val contentEncoding = netResponse.header("Content-Encoding")
            val responseEntryIndex = capturedWireResponses.size
            capturedWireResponses.add(
                ExecutedHttpResponse(
                    protocol = respProto,
                    statusCode = netResponse.code,
                    statusMessage = statusMsg,
                    headers = respHeaders,
                    bodyBytes = ByteArray(0),
                    rawWireBodyBytes = ByteArray(0),
                    contentType = netResponse.header("Content-Type") ?: netResponse.body?.contentType()?.toString()
                )
            )

            val originalBody = netResponse.body
            if (originalBody == null) {
                netResponse
            } else {
                netResponse.newBuilder()
                    .body(
                        StreamInterceptingResponseBody(originalBody) { rawStreamBytes ->
                            val decodedBytes = decodeInterceptedStreamBytes(rawStreamBytes, contentEncoding)
                            capturedWireResponses[responseEntryIndex] = capturedWireResponses[responseEntryIndex].copy(
                                bodyBytes = decodedBytes,
                                rawWireBodyBytes = rawStreamBytes
                            )
                        }
                    )
                    .build()
            }
        }

        val client = sharedClient?.newBuilder()
            ?.eventListener(timingCollector)?.addNetworkInterceptor(wireCaptureInterceptor)?.build() ?: buildClientForSpec(spec).newBuilder()
            .eventListener(timingCollector)
            .addNetworkInterceptor(wireCaptureInterceptor)
            .build()
        val requestBuilder = Request.Builder()

        try {
            requestBuilder.url(resolvedUrl)
        } catch (e: Exception) {
            return createErrorResult(spec, resolvedUrl, allVars, "Invalid URL '$resolvedUrl': ${e.message}")
        }

        // Build headers
        var contentTypeHeader: String? = null
        for (h in spec.headers) {
            if (!h.enabled || h.key.isBlank()) continue
            val k = HttpFileParser.resolveVariables(h.key.trim(), allVars)
            val v = HttpFileParser.resolveVariables(h.value.trim(), allVars)
            if (k.equals("Content-Type", ignoreCase = true)) {
                contentTypeHeader = v
            }
            requestBuilder.addHeader(k, v)
        }

        // Apply Auth config
        when (spec.auth.type) {
            AuthType.NONE -> {}
            AuthType.BASIC -> {
                val user = HttpFileParser.resolveVariables(spec.auth.username, allVars)
                val pass = HttpFileParser.resolveVariables(spec.auth.password, allVars)
                val credential = Credentials.basic(user, pass, StandardCharsets.UTF_8)
                requestBuilder.header("Authorization", credential)
            }
            AuthType.BEARER -> {
                val token = HttpFileParser.resolveVariables(spec.auth.token, allVars)
                requestBuilder.header("Authorization", "Bearer $token")
            }
            AuthType.API_KEY -> {
                if (!spec.auth.apiKeyInQuery && spec.auth.apiKeyName.isNotBlank()) {
                    val keyName = HttpFileParser.resolveVariables(spec.auth.apiKeyName, allVars)
                    val keyVal = HttpFileParser.resolveVariables(spec.auth.apiKeyValue, allVars)
                    requestBuilder.header(keyName, keyVal)
                }
            }
        }

        // Default User-Agent if none specified
        if (spec.headers.none { it.enabled && it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", "Executed-HTTP-Client/1.0")
        }

        // Build RequestBody
        val requestBody = buildRequestBody(spec, allVars, contentTypeHeader)
        requestBuilder.method(spec.method.name, requestBody)

        val request = requestBuilder.build()
        val overallStartNs = System.nanoTime()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStartNs = System.nanoTime()
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val bodyEndNs = System.nanoTime()
                timingCollector.recordManualBodyReadIfNeeded(bodyStartNs, bodyEndNs)

                val timings = timingCollector.toRequestTimings(overallStartNs, bodyEndNs)
                val contentType = response.header("Content-Type") ?: response.body?.contentType()?.toString() ?: "application/octet-stream"
                val category = classifyContent(contentType, bytes)

                // Save to file if binary (other than inline image/PDF unless saveToFile is also explicitly set)
                val savedFile = if (saveFiles) maybeSaveOutputFile(spec, response, bytes, category) else null

                // Evaluate simple response handler scripts if present
                val scriptLogs = evaluateResponseScript(spec.responseHandlerScript, response.code, bytes)

                val headersMap = LinkedHashMap<String, List<String>>()
                for (name in response.headers.names()) {
                    headersMap[name] = response.headers.values(name)
                }

                val protocolName = response.protocol.toString().uppercase()
                val wireRequests = if (capturedWireRequests.isNotEmpty()) {
                    capturedWireRequests.toList()
                } else {
                    listOf(buildFallbackExecutedRequest(response.request, protocolName))
                }
                val wireResponses = if (capturedWireResponses.isNotEmpty()) {
                    capturedWireResponses.toList()
                } else {
                    val flatHeaders = headersMap.flatMap { (k, vals) -> vals.map { k to it } }
                    listOf(
                        ExecutedHttpResponse(
                            protocol = protocolName,
                            statusCode = response.code,
                            statusMessage = response.message.ifBlank { defaultStatusPhrase(response.code) },
                            headers = flatHeaders,
                            bodyBytes = bytes,
                            rawWireBodyBytes = bytes,
                            contentType = contentType
                        )
                    )
                }

                HttpResponseResult(
                    requestSpec = spec,
                    resolvedUrl = resolvedUrl,
                    finalUrl = response.request.url.toString(),
                    statusCode = response.code,
                    statusMessage = response.message.ifBlank { defaultStatusPhrase(response.code) },
                    protocol = protocolName,
                    headers = headersMap,
                    bodyBytes = bytes,
                    contentType = contentType,
                    category = category,
                    timings = timings,
                    savedFile = savedFile,
                    redirectCount = countPriorResponses(response),
                    tlsCipherSuite = response.handshake?.cipherSuite?.javaName,
                    errorMessage = null,
                    scriptOutput = scriptLogs,
                    executedRequest = wireRequests.last(),
                    executedRequests = wireRequests,
                    executedResponse = wireResponses.last(),
                    executedResponses = wireResponses
                )
            }
        } catch (e: Exception) {
            val endNs = System.nanoTime()
            val timings = timingCollector.toRequestTimings(overallStartNs, endNs)
            val wireRequests = if (capturedWireRequests.isNotEmpty()) {
                capturedWireRequests.toList()
            } else {
                listOf(buildFallbackExecutedRequest(request, spec.httpVersion ?: "HTTP/1.1"))
            }
            val errBytes = (e.stackTraceToString()).toByteArray(StandardCharsets.UTF_8)
            val wireResponses = if (capturedWireResponses.isNotEmpty()) {
                capturedWireResponses.toList()
            } else {
                listOf(
                    ExecutedHttpResponse(
                        protocol = "HTTP",
                        statusCode = 0,
                        statusMessage = "Execution Failed",
                        headers = emptyList(),
                        bodyBytes = errBytes,
                        rawWireBodyBytes = errBytes,
                        contentType = "text/plain"
                    )
                )
            }
            HttpResponseResult(
                requestSpec = spec,
                resolvedUrl = resolvedUrl,
                finalUrl = wireRequests.last().url,
                statusCode = 0,
                statusMessage = "Execution Failed",
                protocol = "HTTP",
                headers = emptyMap(),
                bodyBytes = errBytes,
                contentType = "text/plain",
                category = ResponseContentCategory.TEXT,
                timings = timings,
                savedFile = null,
                errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "Request failed"}",
                executedRequest = wireRequests.last(),
                executedRequests = wireRequests,
                executedResponse = wireResponses.last(),
                executedResponses = wireResponses
            )
        }
    }

    fun buildClientForSpec(spec: HttpRequestSpec, maxConcurrency: Int = 64): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = maxConcurrency.coerceAtLeast(64)
            maxRequestsPerHost = maxConcurrency.coerceAtLeast(64)
        }
        val clientBuilder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .followRedirects(spec.options.followRedirects)
            .followSslRedirects(spec.options.followRedirects)
            .connectTimeout(spec.options.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(spec.options.timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(spec.options.timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(spec.options.timeoutMillis * 2, TimeUnit.MILLISECONDS)

        if (!spec.options.verifySsl) {
            configureInsecureSsl(clientBuilder)
        }
        return clientBuilder.build()
    }

    fun resolvePreviewUrl(spec: HttpRequestSpec, documentVariables: Map<String, String>): String {
        val allVars = LinkedHashMap<String, String>()
        allVars.putAll(documentVariables)
        allVars.putAll(dynamicVariables)
        return resolveEffectiveUrl(spec, allVars)
    }

    private fun resolveEffectiveUrl(spec: HttpRequestSpec, allVars: Map<String, String>): String {
        val base = HttpFileParser.resolveVariables(spec.baseUrlWithoutQuery(), allVars)
        val params = spec.queryParams.filter { it.enabled && it.key.isNotBlank() }.map {
            KeyValueItem(
                key = HttpFileParser.resolveVariables(it.key, allVars),
                value = HttpFileParser.resolveVariables(it.value, allVars),
                enabled = true
            )
        }.toMutableList()

        if (spec.auth.type == AuthType.API_KEY && spec.auth.apiKeyInQuery && spec.auth.apiKeyName.isNotBlank()) {
            val k = HttpFileParser.resolveVariables(spec.auth.apiKeyName, allVars)
            val v = HttpFileParser.resolveVariables(spec.auth.apiKeyValue, allVars)
            params.removeAll { it.key.equals(k, ignoreCase = true) }
            params.add(KeyValueItem(k, v, true))
        }

        val full = if (params.isNotEmpty()) {
            HttpFileParser.buildUrlWithQueryParams(base, params)
        } else {
            HttpFileParser.resolveVariables(spec.rawUrl, allVars)
        }
        return if (!full.startsWith("http://", ignoreCase = true) && !full.startsWith("https://", ignoreCase = true)) {
            "https://$full"
        } else {
            full
        }
    }

    private fun buildRequestBody(
        spec: HttpRequestSpec,
        allVars: Map<String, String>,
        contentTypeHeader: String?
    ): RequestBody? {
        val allowsBody = spec.method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
        if (!allowsBody && spec.body.isBlank() && spec.bodyFileReference.isNullOrBlank()) {
            return null
        }

        val mediaType = (contentTypeHeader ?: "text/plain; charset=utf-8").toMediaTypeOrNull()

        if (!spec.bodyFileReference.isNullOrBlank()) {
            val resolvedPath = HttpFileParser.resolveVariables(spec.bodyFileReference!!, allVars)
            val file = File(resolvedPath).let { if (it.isAbsolute) it else File(workingDirectory, resolvedPath) }
            if (file.exists() && file.isFile) {
                return file.asRequestBody(mediaType)
            }
        }

        val resolvedBody = HttpFileParser.resolveVariables(spec.body, allVars)
        if (resolvedBody.isEmpty()) {
            return if (spec.method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) {
                ByteArray(0).toRequestBody(mediaType)
            } else {
                null
            }
        }
        return resolvedBody.toByteArray(StandardCharsets.UTF_8).toRequestBody(mediaType)
    }

    fun classifyContent(contentType: String, bytes: ByteArray): ResponseContentCategory {
        val lowerType = contentType.lowercase()

        // 1. Check PDF by content-type or magic bytes (%PDF-)
        if (lowerType.contains("application/pdf") || isPdfMagic(bytes)) {
            return ResponseContentCategory.PDF
        }

        // 2. Check Image by content-type or magic bytes (PNG, JPEG, GIF, BMP, WEBP)
        if (lowerType.startsWith("image/") && !lowerType.contains("svg")) {
            return ResponseContentCategory.IMAGE
        }
        if (isImageMagic(bytes)) {
            return ResponseContentCategory.IMAGE
        }

        // 3. Check JSON / XML / HTML / Text
        if (lowerType.contains("json")) return ResponseContentCategory.JSON
        if (lowerType.contains("html")) return ResponseContentCategory.HTML
        if (lowerType.contains("xml") || lowerType.contains("svg")) return ResponseContentCategory.XML
        if (lowerType.startsWith("text/") ||
            lowerType.contains("javascript") ||
            lowerType.contains("ecmascript") ||
            lowerType.contains("x-www-form-urlencoded") ||
            lowerType.contains("yaml") ||
            lowerType.contains("csv")
        ) {
            return ResponseContentCategory.TEXT
        }

        // 4. Known binary MIME types
        if (lowerType.contains("octet-stream") ||
            lowerType.contains("zip") ||
            lowerType.contains("gzip") ||
            lowerType.contains("tar") ||
            lowerType.startsWith("audio/") ||
            lowerType.startsWith("video/") ||
            lowerType.contains("msword") ||
            lowerType.contains("officedocument") ||
            lowerType.contains("font/") ||
            lowerType.contains("wasm")
        ) {
            // Double check if octet-stream is actually plain UTF-8 text or JSON
            if (lowerType.contains("octet-stream") && bytes.isNotEmpty() && looksLikeUtf8Text(bytes)) {
                val sample = String(bytes, 0, minOf(bytes.size, 256), StandardCharsets.UTF_8).trimStart()
                if (sample.startsWith("{") || sample.startsWith("[")) return ResponseContentCategory.JSON
            }
            return ResponseContentCategory.BINARY
        }

        // 5. Fallback heuristic inspection
        if (bytes.isEmpty()) return ResponseContentCategory.TEXT
        return if (looksLikeUtf8Text(bytes)) {
            val sample = String(bytes, 0, minOf(bytes.size, 256), StandardCharsets.UTF_8).trimStart()
            when {
                sample.startsWith("{") || sample.startsWith("[") -> ResponseContentCategory.JSON
                sample.startsWith("<!DOCTYPE html", ignoreCase = true) || sample.startsWith("<html", ignoreCase = true) -> ResponseContentCategory.HTML
                sample.startsWith("<?xml", ignoreCase = true) -> ResponseContentCategory.XML
                else -> ResponseContentCategory.TEXT
            }
        } else {
            ResponseContentCategory.BINARY
        }
    }

    private fun isPdfMagic(bytes: ByteArray): Boolean {
        return bytes.size >= 5 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[4] == '-'.code.toByte()
    }

    private fun isImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return true
        }
        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return true
        }
        // GIF: GIF87a or GIF89a
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()) {
            return true
        }
        // BMP: BM
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return true
        }
        return false
    }

    private fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
        val checkLen = minOf(bytes.size, 1024)
        var nonPrintable = 0
        for (i in 0 until checkLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false // Null byte strongly indicates binary
            if (b < 0x09 || (b in 0x0E..0x1F)) {
                nonPrintable++
            }
        }
        return nonPrintable.toDouble() / checkLen < 0.05
    }

    private fun maybeSaveOutputFile(
        spec: HttpRequestSpec,
        response: Response,
        bytes: ByteArray,
        category: ResponseContentCategory
    ): File? {
        val explicitSavePath = spec.options.saveToFile
        if (!explicitSavePath.isNullOrBlank()) {
            val target = File(explicitSavePath).let {
                if (it.isAbsolute) it else File(outputDirectory, explicitSavePath)
            }
            target.parentFile?.mkdirs()
            val finalFile = if (!spec.options.forceOverwriteFile && target.exists()) {
                uniqueFile(target)
            } else {
                target
            }
            finalFile.writeBytes(bytes)
            return finalFile
        }

        // Requirement: "images should be displayed directly inline in the UI, PDFs too. all other binary files should be stored locally in an output folder."
        if (category == ResponseContentCategory.BINARY) {
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }
            val fileName = deriveBinaryFileName(response)
            val target = uniqueFile(File(outputDirectory, fileName))
            target.writeBytes(bytes)
            return target
        }

        return null
    }

    private fun deriveBinaryFileName(response: Response): String {
        val contentDisposition = response.header("Content-Disposition")
        if (contentDisposition != null) {
            val match = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotBlank()) {
                    return candidate.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                }
            }
        }

        val pathSegment = response.request.url.pathSegments.lastOrNull { it.isNotBlank() }
        val ext = guessExtension(response.header("Content-Type") ?: "")
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

        if (!pathSegment.isNullOrBlank()) {
            val sanitized = pathSegment.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            return if (sanitized.contains('.')) sanitized else "${sanitized}_$timestamp.$ext"
        }

        return "response_$timestamp.$ext"
    }

    private fun guessExtension(contentType: String): String {
        val lower = contentType.lowercase()
        return when {
            lower.contains("zip") -> "zip"
            lower.contains("gzip") -> "gz"
            lower.contains("tar") -> "tar"
            lower.contains("audio/mpeg") || lower.contains("mp3") -> "mp3"
            lower.contains("audio/wav") -> "wav"
            lower.contains("video/mp4") -> "mp4"
            lower.contains("wasm") -> "wasm"
            lower.contains("msword") -> "doc"
            lower.contains("spreadsheetml") -> "xlsx"
            lower.contains("wordprocessingml") -> "docx"
            else -> "bin"
        }
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val name = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var counter = 1
        while (true) {
            val candidate = File(file.parentFile, "${name}_$counter$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    private fun evaluateResponseScript(script: String?, statusCode: Int, bodyBytes: ByteArray): List<String> {
        if (script.isNullOrBlank()) return emptyList()
        val logs = mutableListOf<String>()
        // Support IntelliJ-style client.global.set("key", "value") and client.log("msg")
        val globalSetRegex = Regex("""client\.global\.set\(\s*["']([^"']+)["']\s*,\s*(?:["']([^"']*)["']|response\.body\.([A-Za-z0-9_.]+))\s*\)""")
        val bodyStr by lazy { String(bodyBytes, StandardCharsets.UTF_8) }

        for (match in globalSetRegex.findAll(script)) {
            val varName = match.groupValues[1]
            val literalVal = match.groupValues[2]
            val jsonPath = match.groupValues[3]
            if (jsonPath.isNotEmpty()) {
                val extracted = extractJsonField(bodyStr, jsonPath)
                if (extracted != null) {
                    dynamicVariables[varName] = extracted
                    logs.add("client.global.set('$varName', '$extracted')")
                }
            } else {
                dynamicVariables[varName] = literalVal
                logs.add("client.global.set('$varName', '$literalVal')")
            }
        }

        val logRegex = Regex("""client\.log\(\s*["']([^"']+)["']\s*\)""")
        for (match in logRegex.findAll(script)) {
            logs.add("LOG: ${match.groupValues[1]} (status=$statusCode)")
        }
        return logs
    }

    private fun extractJsonField(jsonText: String, path: String): String? {
        var currentSegment = jsonText.trim()
        for (part in path.split('.')) {
            val keyPattern = Regex(""""${Regex.escape(part)}"\s*:\s*""")
            val match = keyPattern.find(currentSegment) ?: return null
            val valStart = match.range.last + 1
            if (valStart >= currentSegment.length) return null
            when (currentSegment[valStart]) {
                '"' -> {
                    val sb = StringBuilder()
                    var i = valStart + 1
                    var escaped = false
                    while (i < currentSegment.length) {
                        val c = currentSegment[i]
                        if (escaped) {
                            sb.append(c)
                            escaped = false
                        } else if (c == '\\') {
                            escaped = true
                        } else if (c == '"') {
                            return sb.toString()
                        } else {
                            sb.append(c)
                        }
                        i++
                    }
                    return null
                }
                '{' -> {
                    var depth = 0
                    var inStr = false
                    var esc = false
                    var endIdx = valStart
                    while (endIdx < currentSegment.length) {
                        val c = currentSegment[endIdx]
                        if (inStr) {
                            if (esc) esc = false
                            else if (c == '\\') esc = true
                            else if (c == '"') inStr = false
                        } else {
                            if (c == '"') inStr = true
                            else if (c == '{') depth++
                            else if (c == '}') {
                                depth--
                                if (depth == 0) break
                            }
                        }
                        endIdx++
                    }
                    currentSegment = currentSegment.substring(valStart, (endIdx + 1).coerceAtMost(currentSegment.length))
                }
                else -> {
                    val tokenEnd = currentSegment.indexOfAny(charArrayOf(',', '}', ']', ' ', '\n', '\r', '\t'), valStart)
                        .let { if (it == -1) currentSegment.length else it }
                    val token = currentSegment.substring(valStart, tokenEnd).trim()
                    return if (token == "null") null else token
                }
            }
        }
        return null
    }

    private fun countPriorResponses(response: Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private fun configureInsecureSsl(builder: OkHttpClient.Builder) {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }

    private fun decodeInterceptedStreamBytes(rawStreamBytes: ByteArray, contentEncoding: String?): ByteArray {
        if (rawStreamBytes.size >= 2 &&
            contentEncoding?.equals("gzip", ignoreCase = true) == true &&
            rawStreamBytes[0] == 0x1f.toByte() &&
            rawStreamBytes[1] == 0x8b.toByte()
        ) {
            return runCatching {
                GzipSource(Buffer().write(rawStreamBytes)).buffer().use { it.readByteArray() }
            }.getOrDefault(rawStreamBytes)
        }
        return rawStreamBytes
    }

    private class StreamInterceptingResponseBody(
        private val delegate: ResponseBody,
        private val onStreamComplete: (ByteArray) -> Unit
    ) : ResponseBody() {
        private val teeBuffer = Buffer()
        private var completed = false
        private val bufferedSource: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    if (bytesRead > 0L) {
                        synchronized(teeBuffer) {
                            sink.copyTo(teeBuffer, sink.size - bytesRead, bytesRead)
                        }
                    } else if (bytesRead == -1L) {
                        emitIfNeeded()
                    }
                    return bytesRead
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        emitIfNeeded()
                    }
                }
            }.buffer()
        }

        private fun emitIfNeeded() {
            synchronized(teeBuffer) {
                if (!completed) {
                    completed = true
                    onStreamComplete(teeBuffer.readByteArray())
                }
            }
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = bufferedSource
    }

    private fun captureOkHttpRequest(req: Request, protocolStr: String): ExecutedHttpRequest {
        val reqTarget = buildString {
            append(req.url.encodedPath.ifEmpty { "/" })
            req.url.encodedQuery?.let { append('?').append(it) }
        }
        val headerList = (0 until req.headers.size).map { idx ->
            req.headers.name(idx) to req.headers.value(idx)
        }
        val reqBodyBytes = req.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readByteArray()
        } ?: ByteArray(0)

        return ExecutedHttpRequest(
            method = req.method,
            url = req.url.toString(),
            requestTarget = reqTarget,
            protocol = protocolStr,
            headers = headerList,
            bodyBytes = reqBodyBytes,
            contentType = req.header("Content-Type") ?: req.body?.contentType()?.toString()
        )
    }

    private fun buildFallbackExecutedRequest(req: Request, protocolStr: String): ExecutedHttpRequest {
        val base = captureOkHttpRequest(req, protocolStr)
        val headers = base.headers.toMutableList()
        val body = req.body
        if (body != null) {
            val ct = body.contentType()?.toString()
            if (ct != null && headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
                headers.add("Content-Type" to ct)
            }
            val len = runCatching { body.contentLength() }.getOrDefault(-1L)
            if (len >= 0L && headers.none { it.first.equals("Content-Length", ignoreCase = true) }) {
                headers.add("Content-Length" to len.toString())
            }
        }
        if (headers.none { it.first.equals("Host", ignoreCase = true) }) {
            val defaultPort = if (req.url.isHttps) 443 else 80
            val hostVal = if (req.url.port != defaultPort) "${req.url.host}:${req.url.port}" else req.url.host
            headers.add("Host" to hostVal)
        }
        if (headers.none { it.first.equals("Connection", ignoreCase = true) }) {
            headers.add("Connection" to "Keep-Alive")
        }
        if (headers.none { it.first.equals("Accept-Encoding", ignoreCase = true) } &&
            headers.none { it.first.equals("Range", ignoreCase = true) }
        ) {
            headers.add("Accept-Encoding" to "gzip")
        }
        return base.copy(headers = headers)
    }

    private fun createErrorResult(
        spec: HttpRequestSpec,
        url: String,
        allVars: Map<String, String>,
        error: String
    ): HttpResponseResult {
        val resolvedHeaders = spec.headers
            .filter { it.enabled && it.key.isNotBlank() }
            .map {
                HttpFileParser.resolveVariables(it.key.trim(), allVars) to
                    HttpFileParser.resolveVariables(it.value.trim(), allVars)
            }
        val resolvedBodyBytes = HttpFileParser.resolveVariables(spec.body, allVars).toByteArray(StandardCharsets.UTF_8)
        val fallbackReq = ExecutedHttpRequest(
            method = spec.method.name,
            url = url,
            requestTarget = url,
            protocol = spec.httpVersion ?: "HTTP/1.1",
            headers = resolvedHeaders,
            bodyBytes = resolvedBodyBytes,
            contentType = resolvedHeaders.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        )
        return HttpResponseResult(
            requestSpec = spec,
            resolvedUrl = url,
            finalUrl = url,
            statusCode = 0,
            statusMessage = "Invalid Request",
            protocol = "HTTP",
            headers = emptyMap(),
            bodyBytes = error.toByteArray(StandardCharsets.UTF_8),
            contentType = "text/plain",
            category = ResponseContentCategory.TEXT,
            timings = RequestTimings(),
            errorMessage = error,
            executedRequest = fallbackReq,
            executedRequests = listOf(fallbackReq)
        )
    }

    private fun defaultStatusPhrase(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "HTTP $code"
    }

    private class TimingEventListener : EventListener() {
        private var callStartNs: Long = 0L
        private var dnsStartNs: Long = 0L
        private var dnsTotalNs: Long = 0L

        private var connectStartNs: Long = 0L
        private var connectTotalNs: Long = 0L

        private var secureConnectStartNs: Long = 0L
        private var secureConnectTotalNs: Long = 0L

        private var requestSendStartNs: Long = 0L
        private var requestSendEndNs: Long = 0L
        private var requestSendTotalNs: Long = 0L

        private var responseHeadersStartNs: Long = 0L
        private var responseHeadersEndNs: Long = 0L
        private var serverWaitTotalNs: Long = 0L

        private var responseBodyStartNs: Long = 0L
        private var responseBodyEndNs: Long = 0L
        private var responseTransferTotalNs: Long = 0L

        override fun callStart(call: Call) {
            callStartNs = System.nanoTime()
        }

        override fun dnsStart(call: Call, domainName: String) {
            dnsStartNs = System.nanoTime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            if (dnsStartNs > 0L) {
                dnsTotalNs += (System.nanoTime() - dnsStartNs).coerceAtLeast(0L)
            }
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStartNs = System.nanoTime()
        }

        override fun secureConnectStart(call: Call) {
            secureConnectStartNs = System.nanoTime()
            if (connectStartNs > 0L) {
                connectTotalNs += (secureConnectStartNs - connectStartNs).coerceAtLeast(0L)
                connectStartNs = 0L
            }
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            if (secureConnectStartNs > 0L) {
                secureConnectTotalNs += (System.nanoTime() - secureConnectStartNs).coerceAtLeast(0L)
                secureConnectStartNs = 0L
            }
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            if (connectStartNs > 0L) {
                connectTotalNs += (System.nanoTime() - connectStartNs).coerceAtLeast(0L)
                connectStartNs = 0L
            }
        }

        override fun requestHeadersStart(call: Call) {
            if (requestSendStartNs == 0L) {
                requestSendStartNs = System.nanoTime()
            }
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            requestSendEndNs = System.nanoTime()
        }

        override fun requestBodyStart(call: Call) {
            if (requestSendStartNs == 0L) {
                requestSendStartNs = System.nanoTime()
            }
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestSendEndNs = System.nanoTime()
        }

        override fun responseHeadersStart(call: Call) {
            responseHeadersStartNs = System.nanoTime()
            if (requestSendStartNs > 0L) {
                val endReq = if (requestSendEndNs >= requestSendStartNs) requestSendEndNs else responseHeadersStartNs
                requestSendTotalNs += (endReq - requestSendStartNs).coerceAtLeast(0L)
            }
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            responseHeadersEndNs = System.nanoTime()
            val waitStart = when {
                requestSendEndNs > 0L -> requestSendEndNs
                requestSendStartNs > 0L -> requestSendStartNs
                responseHeadersStartNs > 0L -> responseHeadersStartNs
                else -> callStartNs
            }
            if (waitStart > 0L) {
                serverWaitTotalNs += (responseHeadersEndNs - waitStart).coerceAtLeast(0L)
            }
            requestSendStartNs = 0L
            requestSendEndNs = 0L
        }

        override fun responseBodyStart(call: Call) {
            responseBodyStartNs = System.nanoTime()
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBodyEndNs = System.nanoTime()
            val start = if (responseBodyStartNs > 0L) responseBodyStartNs else responseHeadersEndNs
            if (start > 0L) {
                responseTransferTotalNs += (responseBodyEndNs - start).coerceAtLeast(0L)
            }
        }

        fun recordManualBodyReadIfNeeded(bodyStartNs: Long, bodyEndNs: Long) {
            if (responseTransferTotalNs == 0L && bodyEndNs >= bodyStartNs) {
                val start = if (responseHeadersEndNs > 0L) responseHeadersEndNs else bodyStartNs
                responseTransferTotalNs = (bodyEndNs - start).coerceAtLeast(0L)
            }
        }

        fun toRequestTimings(overallStartNs: Long, overallEndNs: Long): RequestTimings {
            fun nsToMs(ns: Long): Double = (ns.coerceAtLeast(0L)) / 1_000_000.0
            val totalMs = nsToMs(overallEndNs - overallStartNs)
            return RequestTimings(
                dnsDurationMs = nsToMs(dnsTotalNs),
                tcpConnectDurationMs = nsToMs(connectTotalNs),
                tlsHandshakeDurationMs = nsToMs(secureConnectTotalNs),
                requestSentDurationMs = nsToMs(requestSendTotalNs),
                serverAcceptAndWaitDurationMs = nsToMs(serverWaitTotalNs),
                responseTransferDurationMs = nsToMs(responseTransferTotalNs),
                totalDurationMs = totalMs
            )
        }
    }
}
