package ch.schmidolin.executed.ui

import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder

object HttpSyntaxHighlighter {

    private val SEPARATOR_LINE = Regex("""^(\s*###)(\s+.*)?$""")
    private val DIRECTIVE_LINE = Regex("""^(\s*(?:#|//)\s*)(@[A-Za-z0-9_-]+)(.*)$""")
    private val COMMENT_LINE = Regex("""^\s*(?:#|//).*$""")
    private val VAR_DEF_LINE = Regex("""^(\s*@[A-Za-z0-9_.-]+)(\s*=\s*)(.*)$""")
    private val REQUEST_LINE = Regex(
        """^(\s*)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)(\s+)(\S+?)(\s+HTTP/\d+(?:\.\d+)?)?(\s*(?:#|//).*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val STATUS_LINE = Regex(
        """^(\s*)(HTTP(?:/\d+(?:\.\d+)?)?)(\s+)(\d{3})(.*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val CONTINUATION_QUERY_LINE = Regex("""^(\s*)([?&])([^=\s]+)(=?)(.*)$""")
    private val HEADER_LINE = Regex("""^(\s*[A-Za-z0-9_-]+)(\s*:\s*)(.*)$""")
    private val FILE_INPUT_LINE = Regex("""^(\s*<\s+)(\S+.*)$""")
    private val OUTPUT_REDIRECT_LINE = Regex("""^(\s*>>!?\s+)(\S+.*)$""")
    private val VAR_REF = Regex("""\{\{[^}]+\}\}""")

    private enum class SectionState {
        PRE_REQUEST,
        HEADERS,
        BODY,
        SCRIPT
    }

    fun computeHttpHighlighting(text: String): StyleSpans<Collection<String>> {
        val builder = StyleSpansBuilder<Collection<String>>()
        if (text.isEmpty()) {
            builder.add(listOf("http-default"), 0)
            return builder.create()
        }

        val lines = text.split("\n")
        var state = SectionState.PRE_REQUEST

        for ((index, rawLine) in lines.withIndex()) {
            val isLast = index == lines.size - 1
            val hasTrailingCr = rawLine.endsWith("\r")
            val line = if (hasTrailingCr) rawLine.dropLast(1) else rawLine
            val lineLen = line.length
            val trimmed = line.trim()

            when {
                lineLen == 0 -> {
                    if (state == SectionState.HEADERS) {
                        state = SectionState.BODY
                    }
                }

                state == SectionState.SCRIPT -> {
                    val endIdx = line.indexOf("%}")
                    if (endIdx >= 0) {
                        val before = line.substring(0, endIdx)
                        val marker = line.substring(endIdx, endIdx + 2)
                        val after = line.substring(endIdx + 2)
                        highlightScriptSegment(builder, before)
                        builder.add(listOf("http-script-marker"), marker.length)
                        if (after.isNotEmpty()) {
                            builder.add(listOf("http-comment"), after.length)
                        }
                        state = SectionState.BODY
                    } else {
                        highlightScriptSegment(builder, line)
                    }
                }

                SEPARATOR_LINE.matches(line) -> {
                    state = SectionState.PRE_REQUEST
                    val match = SEPARATOR_LINE.matchEntire(line)!!
                    val prefix = match.groupValues[1]
                    val titlePart = match.groupValues[2]
                    builder.add(listOf("http-separator"), prefix.length)
                    if (titlePart.isNotEmpty()) {
                        builder.add(listOf("http-separator-title"), titlePart.length)
                    }
                }

                DIRECTIVE_LINE.matches(line) -> {
                    val match = DIRECTIVE_LINE.matchEntire(line)!!
                    val commentPrefix = match.groupValues[1]
                    val directiveName = match.groupValues[2]
                    val directiveValue = match.groupValues[3]
                    builder.add(listOf("http-comment"), commentPrefix.length)
                    builder.add(listOf("http-directive"), directiveName.length)
                    if (directiveValue.isNotEmpty()) {
                        addWithVariableHighlight(builder, directiveValue, "http-directive-val")
                    }
                }

                COMMENT_LINE.matches(line) -> {
                    builder.add(listOf("http-comment"), lineLen)
                }

                VAR_DEF_LINE.matches(line) && state != SectionState.BODY -> {
                    val match = VAR_DEF_LINE.matchEntire(line)!!
                    val varName = match.groupValues[1]
                    val eqSign = match.groupValues[2]
                    val varVal = match.groupValues[3]
                    builder.add(listOf("http-variable-def"), varName.length)
                    builder.add(listOf("http-operator"), eqSign.length)
                    addWithVariableHighlight(builder, varVal, "http-variable-val")
                }

                REQUEST_LINE.matches(line) && state != SectionState.BODY -> {
                    state = SectionState.HEADERS
                    val match = REQUEST_LINE.matchEntire(line)!!
                    val lead = match.groupValues[1]
                    val method = match.groupValues[2]
                    val space = match.groupValues[3]
                    val url = match.groupValues[4]
                    val version = match.groupValues[5]
                    val trailingComment = match.groupValues[6]

                    if (lead.isNotEmpty()) builder.add(listOf("http-default"), lead.length)
                    val methodStyle = when (method.uppercase()) {
                        "GET" -> "http-method-get"
                        "POST" -> "http-method-post"
                        "PUT" -> "http-method-put"
                        "DELETE" -> "http-method-delete"
                        "PATCH" -> "http-method-patch"
                        else -> "http-method-other"
                    }
                    builder.add(listOf(methodStyle), method.length)
                    builder.add(listOf("http-default"), space.length)
                    highlightUrlSegment(builder, url)
                    if (version.isNotEmpty()) {
                        builder.add(listOf("http-version"), version.length)
                    }
                    if (trailingComment.isNotEmpty()) {
                        builder.add(listOf("http-comment"), trailingComment.length)
                    }
                }

                STATUS_LINE.matches(line) && state != SectionState.BODY -> {
                    state = SectionState.HEADERS
                    val match = STATUS_LINE.matchEntire(line)!!
                    val lead = match.groupValues[1]
                    val proto = match.groupValues[2]
                    val space = match.groupValues[3]
                    val codeStr = match.groupValues[4]
                    val phrase = match.groupValues[5]

                    if (lead.isNotEmpty()) builder.add(listOf("http-default"), lead.length)
                    builder.add(listOf("http-version"), proto.length)
                    builder.add(listOf("http-default"), space.length)
                    val code = codeStr.toIntOrNull() ?: 0
                    val statusStyle = when (code) {
                        in 200..299 -> "http-method-get"
                        in 300..399 -> "http-method-put"
                        else -> "http-method-delete"
                    }
                    builder.add(listOf(statusStyle), codeStr.length + phrase.length)
                }

                state == SectionState.HEADERS && CONTINUATION_QUERY_LINE.matches(line) -> {
                    val match = CONTINUATION_QUERY_LINE.matchEntire(line)!!
                    val lead = match.groupValues[1]
                    val sep = match.groupValues[2]
                    val key = match.groupValues[3]
                    val eq = match.groupValues[4]
                    val value = match.groupValues[5]
                    if (lead.isNotEmpty()) builder.add(listOf("http-default"), lead.length)
                    builder.add(listOf("http-query-sep"), sep.length)
                    addWithVariableHighlight(builder, key, "http-query-key")
                    if (eq.isNotEmpty()) builder.add(listOf("http-query-sep"), eq.length)
                    addWithVariableHighlight(builder, value, "http-query-val")
                }

                state == SectionState.HEADERS && HEADER_LINE.matches(line) -> {
                    val match = HEADER_LINE.matchEntire(line)!!
                    val keyPart = match.groupValues[1]
                    val colonPart = match.groupValues[2]
                    val valPart = match.groupValues[3]
                    builder.add(listOf("http-header-key"), keyPart.length)
                    builder.add(listOf("http-operator"), colonPart.length)
                    addWithVariableHighlight(builder, valPart, "http-header-val")
                }

                trimmed.startsWith("> {%") -> {
                    val startIdx = line.indexOf("> {%")
                    if (startIdx > 0) {
                        builder.add(listOf("http-default"), startIdx)
                    }
                    builder.add(listOf("http-script-marker"), 4)
                    val rest = line.substring(startIdx + 4)
                    val endIdx = rest.indexOf("%}")
                    if (endIdx >= 0) {
                        highlightScriptSegment(builder, rest.substring(0, endIdx))
                        builder.add(listOf("http-script-marker"), 2)
                        val tail = rest.substring(endIdx + 2)
                        if (tail.isNotEmpty()) builder.add(listOf("http-comment"), tail.length)
                    } else {
                        highlightScriptSegment(builder, rest)
                        state = SectionState.SCRIPT
                    }
                }

                OUTPUT_REDIRECT_LINE.matches(line) -> {
                    val match = OUTPUT_REDIRECT_LINE.matchEntire(line)!!
                    builder.add(listOf("http-script-marker"), match.groupValues[1].length)
                    addWithVariableHighlight(builder, match.groupValues[2], "http-file-path")
                }

                FILE_INPUT_LINE.matches(line) -> {
                    val match = FILE_INPUT_LINE.matchEntire(line)!!
                    builder.add(listOf("http-script-marker"), match.groupValues[1].length)
                    addWithVariableHighlight(builder, match.groupValues[2], "http-file-path")
                }

                else -> {
                    highlightBodyLine(builder, line)
                }
            }

            if (hasTrailingCr) {
                builder.add(listOf("http-default"), 1) // '\r'
            }
            if (!isLast) {
                builder.add(listOf("http-default"), 1) // '\n'
            }
        }
        return builder.create()
    }

    private fun highlightUrlSegment(builder: StyleSpansBuilder<Collection<String>>, url: String) {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) {
            addWithVariableHighlight(builder, url, "http-url")
            return
        }
        val basePart = url.substring(0, qIdx)
        val queryPart = url.substring(qIdx)
        addWithVariableHighlight(builder, basePart, "http-url")

        // Highlight ?key=val&key2=val2
        var i = 0
        while (i < queryPart.length) {
            val ch = queryPart[i]
            if (ch == '?' || ch == '&' || ch == '=') {
                builder.add(listOf("http-query-sep"), 1)
                i++
            } else {
                val nextSep = queryPart.indexOfAny(charArrayOf('&', '='), i).let { if (it < 0) queryPart.length else it }
                val token = queryPart.substring(i, nextSep)
                val prevChar = if (i > 0) queryPart[i - 1] else '?'
                val style = if (prevChar == '=') "http-query-val" else "http-query-key"
                addWithVariableHighlight(builder, token, style)
                i = nextSep
            }
        }
    }

    private fun highlightBodyLine(builder: StyleSpansBuilder<Collection<String>>, line: String) {
        if (line.isEmpty()) return
        // Apply JSON/XML/variable token highlighting inside request bodies
        var lastEnd = 0
        for (match in BODY_TOKEN_REGEX.findAll(line)) {
            if (match.range.first > lastEnd) {
                addWithVariableHighlight(builder, line.substring(lastEnd, match.range.first), null)
            }
            val tokenText = match.value
            val styleClass = when {
                match.groups[1] != null -> "json-key"
                match.groups[3] != null -> "json-string"
                match.groups[5] != null -> "json-boolean"
                match.groups[6] != null -> "json-number"
                match.groups[7] != null -> "xml-tag"
                else -> null
            }
            addWithVariableHighlight(builder, tokenText, styleClass)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < line.length) {
            addWithVariableHighlight(builder, line.substring(lastEnd), null)
        }
    }

    private val SCRIPT_TOKEN_REGEX = Regex(
        """(\b(?:client|response|global|set|assert|test|log|body|status|headers|function|var|let|const|if|else|return|true|false|null)\b)|("([^"\\]|\\.)*"|'([^'\\]|\\.)*')|(-?\d+(?:\.\d+)?)"""
    )

    private fun highlightScriptSegment(builder: StyleSpansBuilder<Collection<String>>, segment: String) {
        if (segment.isEmpty()) return
        var lastEnd = 0
        for (match in SCRIPT_TOKEN_REGEX.findAll(segment)) {
            if (match.range.first > lastEnd) {
                builder.add(listOf("http-default"), match.range.first - lastEnd)
            }
            val styleClass = when {
                match.groups[1] != null -> "http-script-keyword"
                match.groups[2] != null -> "json-string"
                match.groups[5] != null -> "json-number"
                else -> "http-default"
            }
            builder.add(listOf(styleClass), match.value.length)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < segment.length) {
            builder.add(listOf("http-default"), segment.length - lastEnd)
        }
    }

    private fun addWithVariableHighlight(
        builder: StyleSpansBuilder<Collection<String>>,
        segment: String,
        baseClass: String?
    ) {
        if (segment.isEmpty()) return
        val baseStyles = listOf(baseClass ?: "http-default")
        var lastIdx = 0
        for (match in VAR_REF.findAll(segment)) {
            if (match.range.first > lastIdx) {
                builder.add(baseStyles, match.range.first - lastIdx)
            }
            val inner = match.value.removePrefix("{{").removeSuffix("}}").trim()
            val varStyle = if (inner.startsWith("$")) "http-dynamic-var-ref" else "http-variable-ref"
            builder.add(listOf(varStyle), match.value.length)
            lastIdx = match.range.last + 1
        }
        if (lastIdx < segment.length) {
            builder.add(baseStyles, segment.length - lastIdx)
        }
    }

    private val BODY_TOKEN_REGEX = Regex(
        """("([^"\\]|\\.)*"\s*:)|("([^"\\]|\\.)*")|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(</?[A-Za-z0-9_:-]+(?:\s+[^>]*)?>)"""
    )

    private val JSON_TOKEN_REGEX = Regex(
        """("([^"\\]|\\.)*"\s*:)|("([^"\\]|\\.)*")|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)"""
    )

    fun computeOutputHighlighting(text: String): StyleSpans<Collection<String>> {
        val builder = StyleSpansBuilder<Collection<String>>()
        if (text.isEmpty()) {
            builder.add(listOf("http-default"), 0)
            return builder.create()
        }

        var lastEnd = 0
        for (match in JSON_TOKEN_REGEX.findAll(text)) {
            if (match.range.first > lastEnd) {
                builder.add(listOf("http-default"), match.range.first - lastEnd)
            }
            val styleClass = when {
                match.groups[1] != null -> "json-key"
                match.groups[3] != null -> "json-string"
                match.groups[5] != null -> "json-boolean"
                match.groups[6] != null -> "json-number"
                else -> "http-default"
            }
            builder.add(listOf(styleClass), match.value.length)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            builder.add(listOf("http-default"), text.length - lastEnd)
        }
        return builder.create()
    }
}

