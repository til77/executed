package ch.schmidolin.executed.parser

import ch.schmidolin.executed.model.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.*

object HttpFileParser {

    private val VARIABLE_DEF_REGEX = Regex("""^\s*@([A-Za-z0-9_.-]+)\s*=\s*(.*)$""")
    private val SEPARATOR_REGEX = Regex("""^\s*###(?:\s+(.*))?$""")
    private val REQUEST_LINE_REGEX = Regex(
        """^\s*(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)\s+(\S+)(?:\s+(HTTP/\d+(?:\.\d+)?))?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val URL_ONLY_LINE_REGEX = Regex("""^\s*(https?://\S+)\s*$""", RegexOption.IGNORE_CASE)
    private val HEADER_LINE_REGEX = Regex("""^\s*([A-Za-z0-9_-]+)\s*:\s*(.*)$""")
    private val DIRECTIVE_REGEX = Regex("""^\s*(?:#|//)\s*@([A-Za-z0-9_-]+)(?:\s+(.*))?$""")

    fun parse(text: String): HttpDocument {
        val lines = text.lines()
        val variables = LinkedHashMap<String, String>()
        val requests = mutableListOf<HttpRequestSpec>()

        // First pass: collect top-level @variable = value definitions
        for (line in lines) {
            val match = VARIABLE_DEF_REGEX.matchEntire(line)
            if (match != null) {
                variables[match.groupValues[1].trim()] = match.groupValues[2].trim()
            }
        }

        // Split into request blocks by ### or multiple request lines
        data class RawBlock(
            var name: String = "",
            val startLine: Int,
            var endLine: Int,
            val lines: MutableList<Pair<Int, String>> = mutableListOf()
        )

        val blocks = mutableListOf<RawBlock>()
        var currentBlock: RawBlock? = null

        for ((index, line) in lines.withIndex()) {
            val sepMatch = SEPARATOR_REGEX.matchEntire(line)
            if (sepMatch != null) {
                if (currentBlock != null) {
                    currentBlock.endLine = index - 1
                    blocks.add(currentBlock)
                }
                val title = sepMatch.groupValues[1].trim()
                currentBlock = RawBlock(name = title, startLine = index, endLine = index)
                currentBlock.lines.add(index to line)
                continue
            }

            // If we encounter a new request method line when currentBlock already has a request line
            // (and we're not inside a body or currentBlock has already seen a blank line after headers),
            // check if it starts a new implicit block without ###
            val isReqLine = REQUEST_LINE_REGEX.matches(line)
            if (isReqLine && currentBlock != null && currentBlock.lines.any { REQUEST_LINE_REGEX.matches(it.second) }) {
                // Check if previous non-blank lines weren't part of a body, or split when another request line appears
                // In IntelliJ .http files, ### separates requests, or if no ### is used at the top, the first block starts at 0.
            }

            if (currentBlock == null) {
                currentBlock = RawBlock(name = "", startLine = index, endLine = index)
            }
            currentBlock.endLine = index
            currentBlock.lines.add(index to line)
        }
        if (currentBlock != null) {
            currentBlock.endLine = (lines.size - 1).coerceAtLeast(currentBlock.startLine)
            blocks.add(currentBlock)
        }

        // Parse each block into one or more HttpRequestSpec
        for (block in blocks) {
            val spec = parseBlock(block.name, block.startLine, block.endLine, block.lines)
            if (spec != null) {
                requests.add(spec)
            }
        }

        return HttpDocument(variables = variables, requests = requests)
    }

    private fun parseBlock(
        initialName: String,
        blockStartLine: Int,
        blockEndLine: Int,
        indexedLines: List<Pair<Int, String>>
    ): HttpRequestSpec? {
        var name = initialName
        val options = RequestOptions()
        var requestLineIndex = -1
        var method = HttpMethod.GET
        var rawUrl = ""
        var httpVersion: String? = null

        val headers = mutableListOf<KeyValueItem>()
        val auth = AuthConfig()
        var cursor = 0

        // 1. Scan pre-request comments, directives, and find the request line
        while (cursor < indexedLines.size) {
            val (lineNo, line) = indexedLines[cursor]
            val trimmed = line.trim()

            if (trimmed.isEmpty() || SEPARATOR_REGEX.matches(line) || VARIABLE_DEF_REGEX.matches(line)) {
                cursor++
                continue
            }

            val directiveMatch = DIRECTIVE_REGEX.matchEntire(line)
            if (directiveMatch != null) {
                val directiveKey = directiveMatch.groupValues[1].lowercase()
                val directiveVal = directiveMatch.groupValues[2].trim()
                when (directiveKey) {
                    "name" -> if (directiveVal.isNotEmpty()) name = directiveVal
                    "no-redirect" -> options.followRedirects = false
                    "no-cookie-jar" -> options.noCookieJar = true
                    "no-verify-ssl", "insecure", "disable-ssl-verification" -> options.verifySsl = false
                    "timeout" -> {
                        val parsed = directiveVal.toLongOrNull()
                        if (parsed != null && parsed > 0) {
                            options.timeoutMillis = if (parsed < 300) parsed * 1000L else parsed
                        }
                    }
                }
                cursor++
                continue
            }

            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                if (name.isBlank()) {
                    val commentText = trimmed.removePrefix("//").removePrefix("#").trim()
                    if (commentText.isNotEmpty() && !commentText.startsWith("@")) {
                        name = commentText
                    }
                }
                cursor++
                continue
            }

            val reqMatch = REQUEST_LINE_REGEX.matchEntire(line)
            if (reqMatch != null) {
                method = HttpMethod.fromString(reqMatch.groupValues[1]) ?: HttpMethod.GET
                rawUrl = reqMatch.groupValues[2].trim()
                httpVersion = reqMatch.groupValues[3].takeIf { it.isNotBlank() }
                requestLineIndex = lineNo
                cursor++
                break
            }

            val urlOnlyMatch = URL_ONLY_LINE_REGEX.matchEntire(line)
            if (urlOnlyMatch != null) {
                method = HttpMethod.GET
                rawUrl = urlOnlyMatch.groupValues[1].trim()
                requestLineIndex = lineNo
                cursor++
                break
            }

            cursor++
        }

        if (requestLineIndex == -1 || rawUrl.isBlank()) {
            return null
        }

        // 2. Parse multiline query continuations (?key=val or &key=val) and headers until a blank line
        while (cursor < indexedLines.size) {
            val (_, line) = indexedLines[cursor]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                cursor++
                break
            }

            // Support IntelliJ multiline URL query parameters starting with ? or &
            if (trimmed.startsWith("?") || trimmed.startsWith("&")) {
                rawUrl += trimmed
                cursor++
                continue
            }

            val directiveMatch = DIRECTIVE_REGEX.matchEntire(line)
            if (directiveMatch != null) {
                val directiveKey = directiveMatch.groupValues[1].lowercase()
                val directiveVal = directiveMatch.groupValues[2].trim()
                when (directiveKey) {
                    "name" -> if (directiveVal.isNotEmpty()) name = directiveVal
                    "no-redirect" -> options.followRedirects = false
                    "no-verify-ssl", "insecure" -> options.verifySsl = false
                    "timeout" -> directiveVal.toLongOrNull()?.let {
                        options.timeoutMillis = if (it < 300) it * 1000L else it
                    }
                }
                cursor++
                continue
            }

            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                cursor++
                continue
            }

            val headerMatch = HEADER_LINE_REGEX.matchEntire(line)
            if (headerMatch != null) {
                val hKey = headerMatch.groupValues[1].trim()
                val hVal = headerMatch.groupValues[2].trim()
                if (!extractAuthFromHeader(hKey, hVal, auth)) {
                    headers.add(KeyValueItem(hKey, hVal, true))
                }
            }
            cursor++
        }

        // 3. Parse body, file input (< ./path), response handler (> {% ... %}), and output redirect (>> file)
        val bodyLines = mutableListOf<String>()
        var bodyFileRef: String? = null
        val scriptLines = mutableListOf<String>()
        var inScriptBlock = false

        while (cursor < indexedLines.size) {
            val (_, line) = indexedLines[cursor]
            val trimmed = line.trim()

            if (inScriptBlock) {
                val endIdx = trimmed.indexOf("%}")
                if (endIdx >= 0) {
                    scriptLines.add(trimmed.substring(0, endIdx))
                    inScriptBlock = false
                } else {
                    scriptLines.add(line)
                }
                cursor++
                continue
            }

            // Response handler script: > {% ... %}
            if (trimmed.startsWith("> {%")) {
                val afterStart = trimmed.removePrefix("> {%").trim()
                val endIdx = afterStart.indexOf("%}")
                if (endIdx >= 0) {
                    scriptLines.add(afterStart.substring(0, endIdx).trim())
                } else {
                    if (afterStart.isNotEmpty()) scriptLines.add(afterStart)
                    inScriptBlock = true
                }
                cursor++
                continue
            }

            // Response redirect to file: >>! path or >> path
            if (trimmed.startsWith(">>!")) {
                options.saveToFile = trimmed.removePrefix(">>!").trim()
                options.forceOverwriteFile = true
                cursor++
                continue
            }
            if (trimmed.startsWith(">>")) {
                options.saveToFile = trimmed.removePrefix(">>").trim()
                options.forceOverwriteFile = false
                cursor++
                continue
            }

            // Request body from file: < ./path
            if (trimmed.startsWith("< ") && bodyLines.isEmpty()) {
                bodyFileRef = trimmed.removePrefix("< ").trim()
                cursor++
                continue
            }

            bodyLines.add(line)
            cursor++
        }

        // Trim leading/trailing blank lines from body
        while (bodyLines.isNotEmpty() && bodyLines.first().isBlank()) {
            bodyLines.removeFirst()
        }
        while (bodyLines.isNotEmpty() && bodyLines.last().isBlank()) {
            bodyLines.removeLast()
        }

        val queryParams = parseQueryParams(rawUrl)
        val requestLineText = indexedLines.firstOrNull { it.first == requestLineIndex }?.second ?: ""
        val requestColumnIndex = findHttpVerbColumn(requestLineText, method)

        return HttpRequestSpec(
            id = "req-$requestLineIndex",
            name = name,
            method = method,
            rawUrl = rawUrl,
            httpVersion = httpVersion,
            headers = headers,
            queryParams = queryParams,
            auth = auth,
            body = bodyLines.joinToString("\n"),
            bodyFileReference = bodyFileRef,
            responseHandlerScript = scriptLines.joinToString("\n").takeIf { it.isNotBlank() },
            options = options,
            startLine = blockStartLine,
            requestLine = requestLineIndex,
            requestColumn = requestColumnIndex,
            endLine = blockEndLine
        )
    }

    fun findHttpVerbColumn(lineText: String, method: HttpMethod): Int {
        val verbIdx = lineText.indexOf(method.name, ignoreCase = true)
        if (verbIdx >= 0) return verbIdx
        val firstNonWhitespace = lineText.indexOfFirst { !it.isWhitespace() }
        return if (firstNonWhitespace >= 0) firstNonWhitespace else 0
    }

    private fun extractAuthFromHeader(key: String, value: String, auth: AuthConfig): Boolean {
        if (!key.equals("Authorization", ignoreCase = true)) return false

        if (value.startsWith("Basic ", ignoreCase = true)) {
            val rest = value.substring(6).trim()
            // In IntelliJ .http files, `Authorization: Basic user pass` or base64 is supported!
            if (rest.contains(" ") || (rest.contains(":") && !rest.endsWith("="))) {
                val parts = if (rest.contains(":")) rest.split(":", limit = 2) else rest.split(Regex("\\s+"), limit = 2)
                auth.type = AuthType.BASIC
                auth.username = parts.getOrElse(0) { "" }
                auth.password = parts.getOrElse(1) { "" }
                return true
            } else {
                try {
                    val decoded = String(Base64.getDecoder().decode(rest), StandardCharsets.UTF_8)
                    if (decoded.contains(":")) {
                        val parts = decoded.split(":", limit = 2)
                        auth.type = AuthType.BASIC
                        auth.username = parts[0]
                        auth.password = parts[1]
                        return true
                    }
                } catch (_: Exception) {
                    // Keep as normal header if not valid base64 or variables
                }
            }
        } else if (value.startsWith("Bearer ", ignoreCase = true)) {
            auth.type = AuthType.BEARER
            auth.token = value.substring(7).trim()
            return true
        }
        return false
    }

    fun parseQueryParams(rawUrl: String): MutableList<KeyValueItem> {
        val qIdx = rawUrl.indexOf('?')
        if (qIdx < 0 || qIdx == rawUrl.length - 1) return mutableListOf()
        val queryPart = rawUrl.substring(qIdx + 1)
        val result = mutableListOf<KeyValueItem>()
        for (pair in queryPart.split("&")) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx >= 0) {
                val k = decodeComponentPreservingVars(pair.substring(0, eqIdx))
                val v = decodeComponentPreservingVars(pair.substring(eqIdx + 1))
                result.add(KeyValueItem(k, v, true))
            } else {
                result.add(KeyValueItem(decodeComponentPreservingVars(pair), "", true))
            }
        }
        return result
    }

    fun buildUrlWithQueryParams(baseUrl: String, params: List<KeyValueItem>): String {
        val cleanBase = baseUrl.substringBefore('?')
        val enabledParams = params.filter { it.enabled && it.key.isNotBlank() }
        if (enabledParams.isEmpty()) return cleanBase
        val queryString = enabledParams.joinToString("&") { item ->
            val encodedKey = encodeComponentPreservingVars(item.key)
            if (item.value.isEmpty()) {
                "$encodedKey="
            } else {
                "$encodedKey=${encodeComponentPreservingVars(item.value)}"
            }
        }
        return "$cleanBase?$queryString"
    }

    private fun decodeComponentPreservingVars(input: String): String {
        if (input.contains("{{")) return input
        return try {
            URLDecoder.decode(input, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            input
        }
    }

    private fun encodeComponentPreservingVars(input: String): String {
        if (input.contains("{{") || input.contains("%")) return input
        return try {
            URLEncoder.encode(input, StandardCharsets.UTF_8).replace("+", "%20")
        } catch (_: Exception) {
            input
        }
    }

    fun resolveVariables(input: String, variables: Map<String, String>): String {
        val regex = Regex("""\{\{\s*([^}]+?)\s*\}\}""")
        return regex.replace(input) { match ->
            val key = match.groupValues[1].trim()
            when {
                key == $$"$uuid" || key == $$"$guid" -> UUID.randomUUID().toString()
                key == $$"$timestamp" -> Instant.now().epochSecond.toString()
                key == $$"$isoTimestamp" -> Instant.now().toString()
                key == $$"$randomInt" -> (0..1000).random().toString()
                key.startsWith($$"$randomInt ") -> {
                    val parts = key.removePrefix($$"$randomInt").trim().split(Regex("\\s+"))
                    val min = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val max = parts.getOrNull(1)?.toIntOrNull() ?: 1000
                    if (min <= max) (min..max).random().toString() else min.toString()
                }
                key.startsWith($$"$env.") -> System.getenv(key.removePrefix($$"$env.")) ?: match.value
                variables.containsKey(key) -> variables[key] ?: match.value
                else -> match.value
            }
        }
    }

    fun serializeRequestBlock(spec: HttpRequestSpec): String {
        val sb = StringBuilder()
        if (spec.name.isNotBlank()) {
            sb.append("### ").append(spec.name.trim()).append("\n")
        } else {
            sb.append("###\n")
        }

        if (!spec.options.followRedirects) {
            sb.append("# @no-redirect\n")
        }
        if (!spec.options.verifySsl) {
            sb.append("# @no-verify-ssl\n")
        }
        if (spec.options.noCookieJar) {
            sb.append("# @no-cookie-jar\n")
        }
        if (spec.options.timeoutMillis != 30_000L) {
            sb.append("# @timeout ").append(spec.options.timeoutMillis).append("\n")
        }

        val fullUrl = buildUrlWithQueryParams(spec.baseUrlWithoutQuery(), buildEffectiveQueryParams(spec))
        sb.append(spec.method.name).append(" ").append(fullUrl)
        if (!spec.httpVersion.isNullOrBlank()) {
            sb.append(" ").append(spec.httpVersion)
        }
        sb.append("\n")

        // Write Auth header if configured
        when (spec.auth.type) {
            AuthType.NONE -> {}
            AuthType.BASIC -> {
                if (spec.auth.username.isNotBlank() || spec.auth.password.isNotBlank()) {
                    sb.append("Authorization: Basic ").append(spec.auth.username).append(" ").append(spec.auth.password).append("\n")
                }
            }
            AuthType.BEARER -> {
                if (spec.auth.token.isNotBlank()) {
                    sb.append("Authorization: Bearer ").append(spec.auth.token).append("\n")
                }
            }
            AuthType.API_KEY -> {
                if (!spec.auth.apiKeyInQuery && spec.auth.apiKeyName.isNotBlank() && spec.auth.apiKeyValue.isNotBlank()) {
                    sb.append(spec.auth.apiKeyName.trim()).append(": ").append(spec.auth.apiKeyValue.trim()).append("\n")
                }
            }
        }

        // Write headers
        for (header in spec.headers) {
            if (header.enabled && header.key.isNotBlank()) {
                sb.append(header.key.trim()).append(": ").append(header.value.trim()).append("\n")
            }
        }

        val hasBody = spec.body.isNotBlank() || !spec.bodyFileReference.isNullOrBlank()
        val hasPostSections = hasBody || !spec.responseHandlerScript.isNullOrBlank() || !spec.options.saveToFile.isNullOrBlank()

        if (hasPostSections) {
            sb.append("\n")
        }

        if (!spec.bodyFileReference.isNullOrBlank()) {
            sb.append("< ").append(spec.bodyFileReference!!.trim()).append("\n")
        } else if (spec.body.isNotBlank()) {
            sb.append(spec.body.trimEnd()).append("\n")
        }

        if (!spec.responseHandlerScript.isNullOrBlank()) {
            sb.append("\n> {%\n")
            sb.append(spec.responseHandlerScript!!.trim()).append("\n")
            sb.append("%}\n")
        }

        if (!spec.options.saveToFile.isNullOrBlank()) {
            val op = if (spec.options.forceOverwriteFile) ">>!" else ">>"
            sb.append("\n").append(op).append(" ").append(spec.options.saveToFile!!.trim()).append("\n")
        }

        return sb.toString().trimEnd()
    }

    private fun buildEffectiveQueryParams(spec: HttpRequestSpec): List<KeyValueItem> {
        val list = spec.queryParams.map { it.copy() }.toMutableList()
        if (spec.auth.type == AuthType.API_KEY && spec.auth.apiKeyInQuery && spec.auth.apiKeyName.isNotBlank()) {
            list.removeAll { it.key.equals(spec.auth.apiKeyName, ignoreCase = true) }
            list.add(KeyValueItem(spec.auth.apiKeyName, spec.auth.apiKeyValue, true))
        }
        return list
    }

    fun replaceRequestBlockInText(originalText: String, spec: HttpRequestSpec): String {
        val lines = originalText.lines().toMutableList()
        val start = spec.startLine.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val end = spec.endLine.coerceIn(start, (lines.size - 1).coerceAtLeast(start))

        // Preserve any top-level @variable definitions or comments that might have been at the start of the first block
        val preservedPrefix = mutableListOf<String>()
        var replaceStart = start
        if (start == 0) {
            while (replaceStart <= end && replaceStart < lines.size) {
                val l = lines[replaceStart]
                val trimmed = l.trim()
                val isRegularTopComment = (trimmed.startsWith("#") || trimmed.startsWith("//")) &&
                    !SEPARATOR_REGEX.matches(l) &&
                    !DIRECTIVE_REGEX.matches(l)
                if (VARIABLE_DEF_REGEX.matches(l) || isRegularTopComment || (trimmed.isEmpty() && preservedPrefix.isNotEmpty())) {
                    preservedPrefix.add(l)
                    replaceStart++
                } else {
                    break
                }
            }
        }

        val serialized = serializeRequestBlock(spec)
        val newBlockLines = mutableListOf<String>()
        if (preservedPrefix.isNotEmpty()) {
            newBlockLines.addAll(preservedPrefix)
            if (preservedPrefix.last().isNotBlank()) {
                newBlockLines.add("")
            }
        }
        newBlockLines.addAll(serialized.lines())
        if (end < lines.size - 1) {
            newBlockLines.add("")
        }

        val before = lines.subList(0, start)
        val after = if (end + 1 < lines.size) lines.subList(end + 1, lines.size) else emptyList()

        return (before + newBlockLines + after).joinToString("\n")
    }
}
