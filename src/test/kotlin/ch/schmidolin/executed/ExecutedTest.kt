package ch.schmidolin.executed

import ch.schmidolin.executed.http.HttpExecutor
import ch.schmidolin.executed.http.LoadTestRunner
import ch.schmidolin.executed.model.*
import ch.schmidolin.executed.parser.HttpFileParser
import ch.schmidolin.executed.ui.HttpSyntaxHighlighter
import ch.schmidolin.executed.ui.RequestEditorDialog
import ch.schmidolin.executed.ui.ResponseViewerPane
import ch.schmidolin.executed.ui.WorkspaceFileTreeHelper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExecutedTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses IntelliJ http file with variables headers auth directives and body`() {
        val sample = """
            @host = https://api.example.com
            @token = my-jwt-token

            ### Create Item
            # @no-redirect
            # @no-verify-ssl
            POST {{host}}/v1/items?env=dev&limit=25 HTTP/1.1
            Authorization: Bearer {{token}}
            Content-Type: application/json
            X-Custom: Hello World

            {
              "name": "Widget"
            }

            ### Basic Auth Request
            GET {{host}}/v1/secure
            Authorization: Basic alice wonderland
            Accept: application/json
        """.trimIndent()

        val doc = HttpFileParser.parse(sample)
        assertEquals(2, doc.variables.size)
        assertEquals("https://api.example.com", doc.variables["host"])
        assertEquals("my-jwt-token", doc.variables["token"])

        assertEquals(2, doc.requests.size)

        val first = doc.requests[0]
        assertEquals("Create Item", first.name)
        assertEquals(HttpMethod.POST, first.method)
        assertEquals("HTTP/1.1", first.httpVersion)
        assertFalse(first.options.followRedirects)
        assertFalse(first.options.verifySsl)
        assertEquals(AuthType.BEARER, first.auth.type)
        assertEquals("{{token}}", first.auth.token)
        assertEquals(2, first.queryParams.size)
        assertEquals("env", first.queryParams[0].key)
        assertEquals("dev", first.queryParams[0].value)
        assertEquals(2, first.headers.size)
        assertTrue(first.body.contains("\"Widget\""))

        val second = doc.requests[1]
        assertEquals(AuthType.BASIC, second.auth.type)
        assertEquals("alice", second.auth.username)
        assertEquals("wonderland", second.auth.password)
    }

    @Test
    fun `replaces edited request block in text preserving variables and other requests`() {
        val original = """
            @baseUrl = http://localhost:8080

            ### First Request
            GET {{baseUrl}}/items?page=1
            Accept: application/json

            ### Second Request
            DELETE {{baseUrl}}/items/42
        """.trimIndent()

        val doc = HttpFileParser.parse(original)
        val first = doc.requests[0]

        val edited = first.copy(
            name = "Updated First Request",
            method = HttpMethod.PUT,
            queryParams = mutableListOf(KeyValueItem("page", "2"), KeyValueItem("filter", "active")),
            headers = mutableListOf(KeyValueItem("Accept", "application/json"), KeyValueItem("X-Trace", "123")),
            auth = AuthConfig(type = AuthType.BEARER, token = "xyz-987"),
            options = first.options.copy(followRedirects = false, verifySsl = false),
            body = """{"updated": true}"""
        )

        val updatedText = HttpFileParser.replaceRequestBlockInText(original, edited)
        assertTrue(updatedText.contains("@baseUrl = http://localhost:8080"))
        assertTrue(updatedText.contains("### Updated First Request"))
        assertTrue(updatedText.contains("# @no-redirect"))
        assertTrue(updatedText.contains("# @no-verify-ssl"))
        assertTrue(updatedText.contains("PUT {{baseUrl}}/items?page=2&filter=active"))
        assertTrue(updatedText.contains("Authorization: Bearer xyz-987"))
        assertTrue(updatedText.contains("X-Trace: 123"))
        assertTrue(updatedText.contains("### Second Request"))
    }

    @Test
    fun `executes JSON request and records detailed timings`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","count":3}""")
        )

        val baseUrl = server.url("/api/test").toString()
        val httpText = """
            @endpoint = $baseUrl

            ### Test JSON
            POST {{endpoint}}?mode=fast
            Authorization: Bearer secret123
            Content-Type: application/json

            {"hello":"world"}
        """.trimIndent()

        val doc = HttpFileParser.parse(httpText)
        val executor = HttpExecutor(outputDirectory = tempDir.toFile())
        val result = executor.execute(doc.requests.first(), doc.variables)

        assertTrue(result.isSuccess)
        assertEquals(200, result.statusCode)
        assertEquals(ResponseContentCategory.JSON, result.category)
        assertTrue(result.timings.totalDurationMs > 0.0)
        assertTrue(result.timings.timeUntilServerAcceptsMs >= 0.0)

        val recorded = server.takeRequest()
        assertEquals("/api/test?mode=fast", recorded.path)
        assertEquals("Bearer secret123", recorded.getHeader("Authorization"))
        assertEquals("""{"hello":"world"}""", recorded.body.readUtf8())
    }

    @Test
    fun `detects inline PDF and inline Image and saves other binary files to output folder`() {
        // 1. Minimal valid PDF generated via PDFBox
        val pdfBytes = ByteArrayOutputStream().use { out ->
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.save(out)
            }
            out.toByteArray()
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .setBody(Buffer().write(pdfBytes))
        )

        // 2. Arbitrary binary payload (e.g. application/octet-stream with null/non-UTF8 bytes)
        val binaryBytes = ByteArray(128) { idx -> (idx * 7).toByte() }
        binaryBytes[0] = 0
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/octet-stream")
                .addHeader("Content-Disposition", "attachment; filename=\"archive.bin\"")
                .setBody(Buffer().write(binaryBytes))
        )

        val executor = HttpExecutor(outputDirectory = tempDir.toFile())

        // Execute PDF request
        val pdfDoc = HttpFileParser.parse("GET ${server.url("/doc.pdf")}")
        val pdfResult = executor.execute(pdfDoc.requests.first(), emptyMap())
        assertEquals(ResponseContentCategory.PDF, pdfResult.category)
        assertEquals(null, pdfResult.savedFile) // PDFs are rendered inline unless saveToFile is explicitly requested

        // Execute Binary request
        val binDoc = HttpFileParser.parse("GET ${server.url("/download")}")
        val binResult = executor.execute(binDoc.requests.first(), emptyMap())
        assertEquals(ResponseContentCategory.BINARY, binResult.category)
        assertNotNull(binResult.savedFile)
        assertTrue(binResult.savedFile.exists())
        assertEquals("archive.bin", binResult.savedFile.name)
        assertEquals(128, binResult.savedFile.readBytes().size)
    }

    @Test
    fun `builds workspace file tree and marks folders containing http files recursively`() {
        val rootDir = tempDir.resolve("workspace").toFile().apply { mkdirs() }
        val apiDir = rootDir.resolve("apis/v1/users").apply { mkdirs() }
        val emptyDir = rootDir.resolve("docs/guides").apply { mkdirs() }

        val httpFile = apiDir.resolve("users-api.http").apply {
            writeText("GET https://httpbin.org/get\n")
        }
        emptyDir.resolve("readme.txt").writeText("No http files here")

        val treeRoot = WorkspaceFileTreeHelper.buildFileTree(rootDir)
        assertNotNull(treeRoot.value)
        assertTrue(treeRoot.value.containsHttpFilesRecursively)
        assertEquals(1, treeRoot.value.httpFileCountRecursively)

        val apisNode = treeRoot.children.first { it.value.file.name == "apis" }
        assertTrue(apisNode.value.containsHttpFilesRecursively)
        assertEquals(1, apisNode.value.httpFileCountRecursively)

        val docsNode = treeRoot.children.first { it.value.file.name == "docs" }
        assertFalse(docsNode.value.containsHttpFilesRecursively)
        assertEquals(0, docsNode.value.httpFileCountRecursively)

        val foundHttpItem = WorkspaceFileTreeHelper.findFirstHttpFileItem(treeRoot)
        assertNotNull(foundHttpItem)
        assertEquals(httpFile.canonicalFile, foundHttpItem.value.file.canonicalFile)
        assertTrue(foundHttpItem.value.isHttpFile)

        assertTrue(WorkspaceFileTreeHelper.deleteFile(httpFile))
        assertFalse(httpFile.exists())

        val rebuiltRoot = WorkspaceFileTreeHelper.buildFileTree(rootDir)
        assertFalse(rebuiltRoot.value.containsHttpFilesRecursively)
        assertEquals(0, rebuiltRoot.value.httpFileCountRecursively)
    }

    @Test
    fun `computes syntax highlighting spans matching exact text length for complex http file`() {
        val sample = $$"""
            @baseUrl = https://httpbin.org
            # Comment line
            ### Create User Request
            # @no-redirect
            POST {{baseUrl}}/post?env=prod&id={{$uuid}} HTTP/1.1
            Authorization: ******
            Content-Type: application/json

            {
              "username": "alice",
              "active": true,
              "score": 42
            }

            > {%
              client.global.set("userId", response.body.id);
            %}
        """.trimIndent()

        val spans = HttpSyntaxHighlighter.computeHttpHighlighting(sample)
        assertEquals(sample.length, spans.length())
    }

    @Test
    fun `executes multi-request load test with parallelism and computes live and final statistics`() {
        val totalRequests = 20
        repeat(totalRequests) { i ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"iteration": $i}""")
            )
        }

        val doc = HttpFileParser.parse("GET ${server.url("/load")}")
        val executor = HttpExecutor(outputDirectory = tempDir.toFile())
        val runner = LoadTestRunner(executor)

        val progressSnapshots = CopyOnWriteArrayList<LoadTestStatistics>()
        val config = LoadTestConfig(
            totalRequests = totalRequests,
            parallelism = 4,
            rampUpSeconds = 0.1,
            delayBetweenRequestsMs = 0L,
            reuseConnectionPool = true
        )

        val finalStats = runner.runLoadTest(
            spec = doc.requests.first(),
            documentVariables = doc.variables,
            config = config,
            onProgress = { snapshot, _ ->
                progressSnapshots.add(snapshot)
            }
        )

        assertTrue(finalStats.isFinished)
        assertEquals(totalRequests, finalStats.completedCount)
        assertEquals(totalRequests, finalStats.successCount)
        assertEquals(0, finalStats.failureCount)
        assertEquals(totalRequests, finalStats.statusCodeCounts[200])
        assertTrue(finalStats.requestsPerSecond > 0.0)
        assertTrue(finalStats.totalDurationStats.maxMs >= finalStats.totalDurationStats.minMs)
        assertTrue(finalStats.totalDurationStats.p95Ms >= finalStats.totalDurationStats.p50Ms)
        assertTrue(finalStats.histogram.isNotEmpty())
        assertEquals(totalRequests, finalStats.histogram.sumOf { it.count })
        assertTrue(progressSnapshots.size >= 2)
        assertTrue(finalStats.samples.all { it.responseResult != null })
        assertEquals(1, finalStats.samples.first().requestIndex)
        assertTrue(finalStats.samples.first().responseResult!!.bodyBytes.isNotEmpty())
    }

    @Test
    fun `computes line and column before HTTP verb for requests in document`() {
        val sample = """
            @host = https://api.example.com

            ### Indented POST Request
                POST {{host}}/v1/items HTTP/1.1
            Content-Type: application/json

            ### Standard GET Request
            GET {{host}}/v1/status
        """.trimIndent()

        val doc = HttpFileParser.parse(sample)
        assertEquals(2, doc.requests.size)

        val first = doc.requests[0]
        assertEquals(3, first.requestLine)
        assertEquals(4, first.requestColumn)

        val second = doc.requests[1]
        assertEquals(7, second.requestLine)
        assertEquals(0, second.requestColumn)
    }

    @Test
    fun `loads all application icon resources for primaryStage`() {
        ExecutedApp.ensureLinuxDesktopEntryInstalled()
        val icons = ExecutedApp.loadStageIcons()
        assertEquals(5, icons.size)
        assertTrue(icons.all { !it.isError && it.width > 0 && it.height > 0 })
    }

    @Test
    fun `resolves jar path from code source location regardless of where user stores jar`() {
        val customJar = File("/tmp/my custom folder/Executed-download.jar")
        val resolved = ExecutedApp.resolveJarPath(customJar.toURI().toURL())
        assertEquals(customJar.absolutePath, resolved)
    }

    @Test
    fun `replaces first request block preserving top-level comments and variables and computes valid highlighting`() {
        val original = """
            # Welcome comment
            @baseUrl = http://localhost:8080

            ### First Request
            GET {{baseUrl}}/items?page=1
            Accept: application/json

            ### Second Request
            DELETE {{baseUrl}}/items/42
        """.trimIndent()

        val doc = HttpFileParser.parse(original)
        val first = doc.requests.first()
        val edited = first.copy(
            name = "Updated First Request",
            method = HttpMethod.POST,
            body = """{"key": "value"}"""
        )

        val updatedText = HttpFileParser.replaceRequestBlockInText(original, edited)
        assertTrue(updatedText.contains("# Welcome comment"))
        assertTrue(updatedText.contains("@baseUrl = http://localhost:8080"))
        assertTrue(updatedText.contains("### Updated First Request"))
        assertTrue(updatedText.contains("POST {{baseUrl}}/items?page=1"))

        val spans = HttpSyntaxHighlighter.computeHttpHighlighting(updatedText)
        assertEquals(updatedText.length, spans.length())
    }

    @Test
    fun `captures exact executed wire request including verb url wire headers and body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"created":true}""")
        )

        val baseUrl = server.url("/v1/orders").toString()
        val httpText = """
            @endpoint = $baseUrl
            @env = staging

            ### Create Order
            POST {{endpoint}}?env={{env}} HTTP/1.1
            Content-Type: application/json
            X-Custom-Header: custom-val-{{env}}

            {"item":"book","qty":2}
        """.trimIndent()

        val doc = HttpFileParser.parse(httpText)
        val executor = HttpExecutor(outputDirectory = tempDir.toFile())
        val result = executor.execute(doc.requests.first(), doc.variables)

        val recorded = server.takeRequest()
        val executedReq = result.executedRequest
        assertNotNull(executedReq)
        assertEquals("POST", executedReq.method)
        assertEquals("$baseUrl?env=staging", executedReq.url)
        assertEquals("/v1/orders?env=staging", executedReq.requestTarget)
        assertEquals("HTTP/1.1", executedReq.protocol)
        assertEquals("""{"item":"book","qty":2}""", String(executedReq.bodyBytes, Charsets.UTF_8))

        // Verify wire headers match what the server actually received over the wire
        val headerMap = executedReq.headers.toMap()
        assertEquals(recorded.getHeader("Host"), headerMap["Host"])
        assertEquals(recorded.getHeader("Content-Length"), headerMap["Content-Length"])
        assertEquals(recorded.getHeader("Content-Type"), headerMap["Content-Type"])
        assertEquals(recorded.getHeader("User-Agent"), headerMap["User-Agent"])
        assertEquals("custom-val-staging", headerMap["X-Custom-Header"])

        val wireText = result.formatWireRequestText()
        assertTrue(wireText.startsWith("POST $baseUrl?env=staging HTTP/1.1\n"))
        assertTrue(wireText.contains("Host: ${recorded.getHeader("Host")}\n"))
        assertTrue(wireText.contains("Content-Length: 23\n"))
        assertTrue(wireText.contains("X-Custom-Header: custom-val-staging\n"))
        assertTrue(wireText.endsWith("\n\n{\"item\":\"book\",\"qty\":2}"))

        val spans = HttpSyntaxHighlighter.computeHttpHighlighting(wireText)
        assertEquals(wireText.length, spans.length())

        // Verify intercepted response stream including status line, headers, and body
        val executedResp = result.executedResponse
        assertNotNull(executedResp)
        assertEquals("HTTP/1.1", executedResp.protocol)
        assertEquals(201, executedResp.statusCode)
        assertEquals("""{"created":true}""", String(executedResp.bodyBytes, Charsets.UTF_8))
        val respHeaderMap = executedResp.headers.toMap()
        assertEquals("application/json", respHeaderMap["Content-Type"])
        assertEquals("16", respHeaderMap["Content-Length"])

        val wireResponseText = result.formatWireResponseText()
        assertTrue(wireResponseText.startsWith("HTTP/1.1 201"))
        assertTrue(wireResponseText.contains("Content-Type: application/json\n"))
        assertTrue(wireResponseText.contains("Content-Length: 16\n"))
        assertTrue(wireResponseText.endsWith("\n\n{\"created\":true}"))

        val respSpans = HttpSyntaxHighlighter.computeHttpHighlighting(wireResponseText)
        assertEquals(wireResponseText.length, respSpans.length())
    }

    @Test
    fun `persists and reloads output directory workspace location and selected http file`() {
        val settingsFile = tempDir.resolve("config/settings.properties").toFile()
        val store = AppSettingsStore(settingsFile)

        val customOutputDir = tempDir.resolve("custom-output").toFile().apply { mkdirs() }
        val workspaceDir = tempDir.resolve("my-workspace").toFile().apply { mkdirs() }
        val firstHttp = workspaceDir.resolve("a-first.http").apply { writeText("GET https://httpbin.org/get\n") }
        val secondHttp = workspaceDir.resolve("sub/b-second.http").apply {
            parentFile.mkdirs()
            writeText("POST https://httpbin.org/post\n")
        }

        store.save(
            AppSettings(
                outputDirectory = customOutputDir,
                lastWorkspaceDirectory = workspaceDir,
                lastSelectedFile = secondHttp,
                windowX = 140.0,
                windowY = 95.0,
                windowWidth = 1520.0,
                windowHeight = 910.0,
                windowMaximized = true
            )
        )

        val reloaded = store.load()
        assertEquals(customOutputDir.absoluteFile, reloaded.outputDirectory.absoluteFile)
        assertEquals(workspaceDir.absoluteFile, reloaded.lastWorkspaceDirectory?.absoluteFile)
        assertEquals(secondHttp.absoluteFile, reloaded.lastSelectedFile?.absoluteFile)
        assertEquals(140.0, reloaded.windowX)
        assertEquals(95.0, reloaded.windowY)
        assertEquals(1520.0, reloaded.windowWidth)
        assertEquals(910.0, reloaded.windowHeight)
        assertTrue(reloaded.windowMaximized)

        // Verify tree item lookup finds the saved selected file in the restored workspace tree
        val treeRoot = WorkspaceFileTreeHelper.buildFileTree(reloaded.lastWorkspaceDirectory!!)
        val selectedItem = reloaded.lastSelectedFile?.let {
            WorkspaceFileTreeHelper.findItemByFile(treeRoot, it)
        }
        assertNotNull(selectedItem)
        assertEquals(secondHttp.absoluteFile, selectedItem.value.file.absoluteFile)
        assertTrue( firstHttp.absoluteFile != selectedItem.value.file.absoluteFile)
    }

    @Test
    fun `formats single request and multi-request load test summaries as markdown`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Request-Id", "req-single-123")
                .setBody("""{"user":"alice","active":true}""")
        )

        val baseUrl = server.url("/v1/users").toString()
        val httpText = """
            ### Fetch User Profile
            GET $baseUrl?role=admin
            Accept: application/json
        """.trimIndent()

        val doc = HttpFileParser.parse(httpText)
        val executor = HttpExecutor(outputDirectory = tempDir.toFile())
        val singleResult = executor.execute(doc.requests.first(), doc.variables)

        val singleMd = ResponseViewerPane.formatSingleRequestMarkdown(singleResult)
        assertTrue(singleMd.contains("# HTTP Request Summary: Fetch User Profile"))
        assertTrue(singleMd.contains("## Overview"))
        assertTrue(singleMd.contains("GET"))
        assertTrue(singleMd.contains("$baseUrl?role=admin"))
        assertTrue(singleMd.contains("200"))
        assertTrue(singleMd.contains("## Timing Breakdown"))
        assertTrue(singleMd.contains("## Executed Request"))
        assertTrue(singleMd.contains("## Response Headers"))
        assertTrue(singleMd.contains("X-Request-Id"))
        assertTrue(singleMd.contains("req-single-123"))
        assertTrue(singleMd.contains("## Response Output (JSON)"))
        assertTrue(singleMd.contains("\"user\" : \"alice\""))
        assertTrue(singleMd.contains("## Full Wire Response"))

        // Now test multi-request load test markdown export
        val totalRequests = 6
        repeat(totalRequests) { idx ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"sample":$idx}""")
            )
        }

        val runner = LoadTestRunner(executor)
        val loadStats = runner.runLoadTest(
            spec = doc.requests.first(),
            documentVariables = doc.variables,
            config = LoadTestConfig(
                totalRequests = totalRequests,
                parallelism = 2,
                rampUpSeconds = 0.0,
                delayBetweenRequestsMs = 0L,
                reuseConnectionPool = true
            ),
            onProgress = { _, _ -> }
        )

        val loadMd = ResponseViewerPane.formatLoadTestMarkdown(loadStats)
        assertTrue(loadMd.contains("# Multi-Request / Load Test Summary: Fetch User Profile"))
        assertTrue(loadMd.contains("## Overview & Configuration"))
        assertTrue(loadMd.contains("COMPLETED"))
        assertTrue(loadMd.contains("6 / 6"))
        assertTrue(loadMd.contains("2 worker thread(s)"))
        assertTrue(loadMd.contains("## Statistical Distribution by HTTP Lifecycle Phase"))
        assertTrue(loadMd.contains("Total Roundtrip Duration"))
        assertTrue(loadMd.contains("Until Server Accepts (TTFB)"))
        assertTrue(loadMd.contains("## HTTP Status & Error Breakdown"))
        assertTrue(loadMd.contains("HTTP 200"))
        assertTrue(loadMd.contains("## Response Time Distribution Histogram"))
        assertTrue(loadMd.contains("## Individual Request Samples (6)"))
        assertTrue(loadMd.contains("## Sample #1 Request & Response Details"))
    }

    @Test
    fun `computes available PDF page width accounting for viewport and container padding`() {
        assertEquals(
            740.0,
            ResponseViewerPane.computePdfAvailablePageWidth(
                viewportWidth = 800.0,
                scrollPaneWidth = 820.0,
                containerWidth = 840.0
            )
        )
        assertEquals(
            624.0,
            ResponseViewerPane.computePdfAvailablePageWidth(
                viewportWidth = 0.0,
                scrollPaneWidth = 700.0,
                containerWidth = 720.0
            )
        )
        assertEquals(
            100.0,
            ResponseViewerPane.computePdfAvailablePageWidth(
                viewportWidth = 50.0
            )
        )
    }

    @Test
    fun `provides common user agents and applies selected or custom user agent in request spec`() {
        val agents = RequestEditorDialog.COMMON_USER_AGENTS
        assertTrue(agents.isNotEmpty())
        assertTrue(agents.any { it.contains("Chrome") })
        assertTrue(agents.any { it.contains("Firefox") })
        assertTrue(agents.any { it.contains("Safari") })
        assertTrue(agents.any { it.contains("curl") })

        val initialSpec = HttpFileParser.parse(
            """
            ### User Agent Test
            GET https://httpbin.org/user-agent
            User-Agent: CustomInitial/1.0
            Accept: application/json
            """.trimIndent()
        ).requests.first()

        assertEquals("CustomInitial/1.0", RequestEditorDialog.extractUserAgent(initialSpec.headers))

        // Select a common user agent from the list
        val chromeUa = agents.first { it.contains("Chrome") }
        RequestEditorDialog.applyUserAgentHeader(initialSpec.headers, chromeUa)
        assertEquals(chromeUa, RequestEditorDialog.extractUserAgent(initialSpec.headers))

        // Edit the value to a custom user agent
        RequestEditorDialog.applyUserAgentHeader(initialSpec.headers, "MyEditedCustomAgent/2.5")
        assertEquals("MyEditedCustomAgent/2.5", RequestEditorDialog.extractUserAgent(initialSpec.headers))

        val serialized = HttpFileParser.serializeRequestBlock(initialSpec)
        assertTrue(serialized.contains("User-Agent: MyEditedCustomAgent/2.5"))

        // Clear the user agent
        RequestEditorDialog.applyUserAgentHeader(initialSpec.headers, "   ")
        assertEquals("", RequestEditorDialog.extractUserAgent(initialSpec.headers))
        assertFalse(HttpFileParser.serializeRequestBlock(initialSpec).contains("User-Agent:"))
    }

    @Test
    fun `detects first launch when settings file does not exist or has no saved workspace`() {
        val settingsFile = tempDir.resolve("first-launch-config/settings.properties").toFile()
        val store = AppSettingsStore(settingsFile)

        val initialLoad = store.load()
        assertTrue(initialLoad.isFirstLaunch)

        val workspaceDir = tempDir.resolve("selected-workspace").toFile().apply { mkdirs() }
        store.save(
            AppSettings(
                outputDirectory = initialLoad.outputDirectory,
                lastWorkspaceDirectory = workspaceDir,
                lastSelectedFile = null
            )
        )

        val secondLoad = store.load()
        assertFalse(secondLoad.isFirstLaunch)
        assertEquals(workspaceDir.absoluteFile, secondLoad.lastWorkspaceDirectory?.absoluteFile)
    }

    @Test
    fun `stops descending into dot directories and beyond 4 levels deep until manually expanded and updates parent counts`() {
        val rootDir = tempDir.resolve("deep-workspace").toFile().apply { mkdirs() }

        // 1. Create a shallow .http file at depth 2 (root=0, apis=1, shallow.http=2)
        val apisDir = rootDir.resolve("apis").apply { mkdirs() }
        apisDir.resolve("shallow.http").writeText("GET https://httpbin.org/get\n")

        // 2. Create a hidden dot directory at depth 1 containing an .http file
        val hiddenDir = rootDir.resolve(".hidden-config").apply { mkdirs() }
        hiddenDir.resolve("secret.http").writeText("GET https://httpbin.org/headers\n")

        // 3. Create a deep directory hierarchy:
        // root (0) -> d1 (1) -> d2 (2) -> d3 (3) -> d4 (4, stopped here!) -> d5 (5) -> deep.http (6)
        val d1 = rootDir.resolve("d1").apply { mkdirs() }
        val d2 = d1.resolve("d2").apply { mkdirs() }
        val d3 = d2.resolve("d3").apply { mkdirs() }
        val d4 = d3.resolve("d4").apply { mkdirs() }
        val d5 = d4.resolve("d5").apply { mkdirs() }
        val deepHttpFile = d5.resolve("deep.http").apply {
            writeText("POST https://httpbin.org/post\n")
        }

        val treeRoot = WorkspaceFileTreeHelper.buildFileTree(rootDir)

        // Initially only shallow.http (1) was scanned; hiddenDir and d4 were not descended into
        assertEquals(1, treeRoot.value.httpFileCountRecursively)
        assertTrue(treeRoot.value.containsHttpFilesRecursively)

        // Hidden dot directory is present in the file tree, not scanned, collapsed, and expandable (has placeholder child)
        val hiddenNode = treeRoot.children.first { it.value.file.name == ".hidden-config" }
        assertTrue(hiddenNode.value.isDirectory)
        assertFalse(hiddenNode.value.isScanned)
        assertFalse(hiddenNode.isExpanded)
        assertEquals(0, hiddenNode.value.httpFileCountRecursively)
        assertFalse(hiddenNode.children.isEmpty())

        // Expand the hidden dot directory -> scans its contents and updates parent (root) http count to 2
        hiddenNode.isExpanded = true
        assertTrue(hiddenNode.value.isScanned)
        assertEquals(1, hiddenNode.value.httpFileCountRecursively)
        assertTrue(hiddenNode.value.containsHttpFilesRecursively)
        assertEquals("secret.http", hiddenNode.children.first().value.file.name)
        assertEquals(2, treeRoot.value.httpFileCountRecursively)

        // Check depth limit: d1(1) -> d2(2) -> d3(3) -> d4(4)
        val d1Node = treeRoot.children.first { it.value.file.name == "d1" }
        val d2Node = d1Node.children.first { it.value.file.name == "d2" }
        val d3Node = d2Node.children.first { it.value.file.name == "d3" }
        val d4Node = d3Node.children.first { it.value.file.name == "d4" }

        assertTrue(d1Node.value.isScanned)
        assertTrue(d2Node.value.isScanned)
        assertTrue(d3Node.value.isScanned)
        // d4 is at depth 4, so it was not descended into initially
        assertFalse(d4Node.value.isScanned)
        assertFalse(d4Node.isExpanded)
        assertEquals(0, d4Node.value.httpFileCountRecursively)
        assertEquals(0, d3Node.value.httpFileCountRecursively)
        assertEquals(0, d2Node.value.httpFileCountRecursively)
        assertEquals(0, d1Node.value.httpFileCountRecursively)
        assertFalse(d4Node.children.isEmpty())

        // Manually expand d4 -> scans d4 (and up to 4 more levels, discovering d5/deep.http)
        // and updates counts in d4, d3, d2, d1, and root!
        d4Node.isExpanded = true
        assertTrue(d4Node.value.isScanned)
        assertEquals(1, d4Node.value.httpFileCountRecursively)
        assertTrue(d4Node.value.containsHttpFilesRecursively)

        val d5Node = d4Node.children.first { it.value.file.name == "d5" }
        assertEquals(1, d5Node.value.httpFileCountRecursively)
        assertEquals(deepHttpFile.canonicalFile, d5Node.children.first().value.file.canonicalFile)

        // Verify all ancestor directory counts were updated
        assertEquals(1, d3Node.value.httpFileCountRecursively)
        assertTrue(d3Node.value.containsHttpFilesRecursively)
        assertEquals(1, d2Node.value.httpFileCountRecursively)
        assertTrue(d2Node.value.containsHttpFilesRecursively)
        assertEquals(1, d1Node.value.httpFileCountRecursively)
        assertTrue(d1Node.value.containsHttpFilesRecursively)
        assertEquals(3, treeRoot.value.httpFileCountRecursively)
    }

    @Test
    fun `manually scans entire selected directory structure for http files regardless of depth`() {
        val rootDir = tempDir.resolve("manual-scan-workspace").toFile().apply { mkdirs() }
        val serviceDir = rootDir.resolve("services").apply { mkdirs() }

        // Build a deep nested structure beyond DEFAULT_MAX_DEPTH (6) inside 'services':
        // root (0) -> services (1) -> l2 (2) -> l3 (3) -> l4 (4) -> l5 (5) -> l6 (6, deferred!) -> l7 (7) -> l8 (8) -> l9 (9) -> ultra-deep.http
        val l9 = serviceDir.resolve("l2/l3/l4/l5/l6/l7/l8/l9").apply { mkdirs() }
        val ultraDeepHttp = l9.resolve("ultra-deep.http").apply {
            writeText("GET https://httpbin.org/anything\n")
        }

        // Initial build stops at DEFAULT_MAX_DEPTH (6), so ultra-deep.http is not discovered yet
        val treeRoot = WorkspaceFileTreeHelper.buildFileTree(rootDir)
        val servicesNode = treeRoot.children.first { it.value.file.name == "services" }
        assertEquals(0, treeRoot.value.httpFileCountRecursively)
        assertEquals(0, servicesNode.value.httpFileCountRecursively)
        assertFalse(servicesNode.value.containsHttpFilesRecursively)

        // Manually invoke full recursive scan on 'services' directory node
        WorkspaceFileTreeHelper.scanEntireNodeSync(servicesNode)

        assertTrue(servicesNode.value.isScanned)
        assertTrue(servicesNode.value.containsHttpFilesRecursively)
        assertEquals(1, servicesNode.value.httpFileCountRecursively)
        assertEquals(1, treeRoot.value.httpFileCountRecursively)
        assertTrue(treeRoot.value.containsHttpFilesRecursively)

        val foundItem = WorkspaceFileTreeHelper.findItemByFile(servicesNode, ultraDeepHttp)
        assertNotNull(foundItem)
        assertTrue(foundItem.value.isHttpFile)
    }
}

