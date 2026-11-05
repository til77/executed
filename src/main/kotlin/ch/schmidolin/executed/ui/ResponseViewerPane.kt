package ch.schmidolin.executed.ui

import ch.schmidolin.executed.model.*
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.embed.swing.SwingFXUtils
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.chart.*
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.util.Callback
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

class ResponseViewerPane(
    var outputDirectoryProvider: () -> File = { File(System.getProperty("user.dir"), "output") }
) : BorderPane() {

    private val statusBadge = Label("Ready")
    private val urlLabel = Label("")
    private val sizeBadge = Label("")
    private val timeBadge = Label("")
    private val redirectBadge = Label("")
    val exportMarkdownButton = Button("Export as Markdown").apply {
        styleClass.add("action-button")
        tooltip = Tooltip("Summarize all request/response or load test information as Markdown source")
        isDisable = true
        setOnAction { openExportMarkdownDialog() }
    }

    private var currentSingleResult: HttpResponseResult? = null
    private var currentLoadTestStats: LoadTestStatistics? = null

    // Quick Timing Bar inside header
    private val acceptTimingLabel = Label("Server Accept (TTFB): —")
    private val responseTimingLabel = Label("Response Transfer: —")
    private val totalTimingLabel = Label("Total: —")

    private val mainTabPane = TabPane().apply {
        tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
    }

    private val outputCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }
    private val rawCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }
    private val requestCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }
    private val fullResponseCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }

    private val previewContainer = BorderPane()
    private val requestContainer = BorderPane()
    private val fullResponseContainer = BorderPane()
    private val headersTable = TableView<Pair<String, String>>()
    private val timingsBox = VBox(12.0).apply { padding = Insets(14.0) }
    private val loadTestBox = VBox(14.0).apply { padding = Insets(14.0) }

    private val requestTab = Tab("Executed Request", requestContainer)
    private val fullResponseTab = Tab("Full Response", fullResponseContainer)
    private val previewTab = Tab("Response Output", previewContainer)
    private val rawTab = Tab("Raw Text / Hex", VirtualizedScrollPane(rawCodeArea))
    private val headersTab = Tab("Headers (0)", headersTable)
    private val timingsTab = Tab("Timing Breakdown", ScrollPane(timingsBox).apply { isFitToWidth = true })
    private val loadTestTab = Tab("Load Test & Statistics", ScrollPane(loadTestBox).apply { isFitToWidth = true })
    private val singleRequestTabs = listOf(requestTab, fullResponseTab, previewTab, rawTab, headersTab, timingsTab)

    // Embedded Single-Sample Detail Components (inside Load Test & Statistics tab)
    private val sampleOutputCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }
    private val sampleRawCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }
    private val sampleRequestCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }
    private val sampleFullResponseCodeArea = CodeArea().apply {
        isEditable = false
        isWrapText = false
    }
    private val samplePreviewContainer = BorderPane()
    private val sampleRequestContainer = BorderPane()
    private val sampleFullResponseContainer = BorderPane()
    private val sampleHeadersTable = TableView<Pair<String, String>>()
    private val sampleTimingsBox = VBox(12.0).apply { padding = Insets(14.0) }
    private val sampleRequestTab = Tab("Executed Request", sampleRequestContainer)
    private val sampleFullResponseTab = Tab("Full Response", sampleFullResponseContainer)
    private val samplePreviewTab = Tab("Response Output", samplePreviewContainer)
    private val sampleRawTab = Tab("Raw Text / Hex", VirtualizedScrollPane(sampleRawCodeArea))
    private val sampleHeadersTab = Tab("Headers (0)", sampleHeadersTable)
    private val sampleTimingsTab = Tab("Timing Breakdown", ScrollPane(sampleTimingsBox).apply { isFitToWidth = true })
    private val sampleDetailTabPane = TabPane(
        sampleRequestTab,
        sampleFullResponseTab,
        samplePreviewTab,
        sampleRawTab,
        sampleHeadersTab,
        sampleTimingsTab
    ).apply {
        tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        prefHeight = 360.0
        minHeight = 300.0
    }
    private val sampleDetailTitleLabel = Label("Selected Sample Details").apply {
        styleClass.add("section-header")
    }
    private val sampleDetailStatusBadge = Label("")
    private val sampleDetailMetaLabel = Label("").apply {
        style = "-fx-font-family: monospace; -fx-text-fill: #94a3b8;"
    }
    private val sampleDetailHeaderRow = HBox(10.0, sampleDetailTitleLabel, sampleDetailStatusBadge, sampleDetailMetaLabel).apply {
        alignment = Pos.CENTER_LEFT
    }
    private val sampleDetailContainer = VBox(8.0, sampleDetailHeaderRow, sampleDetailTabPane)

    // Live Load Test Chart & UI components
    private val loadProgressBar = ProgressBar(0.0).apply { prefWidth = 220.0 }
    private val loadProgressLabel = Label("0 / 0")
    private val loadStopButton = Button("■ Stop").apply {
        styleClass.add("badge-error")
        style = "-fx-cursor: hand;"
        isVisible = false
        isManaged = false
    }
    private val liveXAxis = NumberAxis().apply {
        label = "Request #"
        isForceZeroInRange = false
        isAutoRanging = true
    }
    private val liveYAxis = NumberAxis().apply {
        label = "Duration (ms)"
        isForceZeroInRange = true
        isAutoRanging = true
    }
    private val totalMsSeries = XYChart.Series<Number, Number>().apply { name = "Total Roundtrip (ms)" }
    private val ttfbMsSeries = XYChart.Series<Number, Number>().apply { name = "TTFB / Server Accept (ms)" }
    private val movingAvgSeries = XYChart.Series<Number, Number>().apply { name = "Moving Average (ms)" }
    private val liveTimingChart = LineChart(liveXAxis, liveYAxis).apply {
        title = "Live Request Timing Over Execution"
        animated = false
        createSymbols = false
        prefHeight = 270.0
        minHeight = 230.0
        data.addAll(totalMsSeries, ttfbMsSeries, movingAvgSeries)
    }

    private val loadKpiRow = HBox(12.0)
    private val loadElaborateResultsBox = VBox(14.0)
    private val loadSamplesItems = FXCollections.observableArrayList<LoadSampleResult>()
    private val loadSamplesTable = TableView(loadSamplesItems).apply {
        columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        prefHeight = 220.0
    }
    private val loadSamplesSectionLabel = Label("Individual Request Samples (0 recorded — click a row to inspect):").apply {
        styleClass.add("section-header")
    }
    private val loadSamplesAndDetailBox = VBox(10.0)
    private var hasAutoSelectedFirstSample = false

    init {
        padding = Insets(8.0)

        statusBadge.styleClass.add("badge-neutral")
        sizeBadge.styleClass.add("badge-neutral")
        timeBadge.styleClass.add("badge-neutral")
        redirectBadge.styleClass.add("badge-redirect")
        redirectBadge.isVisible = false
        redirectBadge.isManaged = false

        sizeBadge.isVisible = false
        sizeBadge.isManaged = false
        timeBadge.isVisible = false
        timeBadge.isManaged = false

        val topStatusRow = HBox(
            10.0,
            statusBadge,
            timeBadge,
            sizeBadge,
            redirectBadge,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            exportMarkdownButton
        ).apply {
            alignment = Pos.CENTER_LEFT
        }

        val urlRow = HBox(10.0, urlLabel).apply {
            alignment = Pos.CENTER_LEFT
        }

        val quickTimingRow = HBox(18.0, acceptTimingLabel, responseTimingLabel, totalTimingLabel).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("timing-card")
        }

        val headerBox = VBox(8.0, topStatusRow, urlRow, quickTimingRow).apply {
            padding = Insets(0.0, 0.0, 8.0, 0.0)
        }
        top = headerBox

        setupHeadersTable(headersTable)
        setupHeadersTable(sampleHeadersTable)
        setupLoadSamplesTable()
        setupLoadTestPlaceholder()
        previewContainer.center = VirtualizedScrollPane(outputCodeArea)
        requestContainer.center = VirtualizedScrollPane(requestCodeArea)
        fullResponseContainer.center = VirtualizedScrollPane(fullResponseCodeArea)
        samplePreviewContainer.center = VirtualizedScrollPane(sampleOutputCodeArea)
        sampleRequestContainer.center = VirtualizedScrollPane(sampleRequestCodeArea)
        sampleFullResponseContainer.center = VirtualizedScrollPane(sampleFullResponseCodeArea)
        mainTabPane.tabs.setAll(singleRequestTabs)
        center = mainTabPane
    }

    private fun showSingleRequestTabs() {
        if (mainTabPane.tabs != singleRequestTabs) {
            mainTabPane.tabs.setAll(singleRequestTabs)
            mainTabPane.selectionModel.select(requestTab)
        }
    }

    private fun showLoadTestTabOnly() {
        if (mainTabPane.tabs.size != 1 || mainTabPane.tabs.firstOrNull() != loadTestTab) {
            mainTabPane.tabs.setAll(loadTestTab)
        }
        mainTabPane.selectionModel.select(loadTestTab)
    }

    fun showLoading(requestTitle: String, url: String) {
        currentSingleResult = null
        exportMarkdownButton.isDisable = true
        resetLoadTestView()
        showSingleRequestTabs()

        statusBadge.text = "EXECUTING..."
        statusBadge.styleClass.setAll("badge-redirect")
        urlLabel.text = "$requestTitle  →  $url"
        sizeBadge.isVisible = false
        sizeBadge.isManaged = false
        timeBadge.isVisible = false
        timeBadge.isManaged = false
        redirectBadge.isVisible = false
        redirectBadge.isManaged = false

        acceptTimingLabel.text = "Server Accept (TTFB): measuring..."
        responseTimingLabel.text = "Response Transfer: measuring..."
        totalTimingLabel.text = "Total: ..."

        val spinner = ProgressIndicator().apply {
            maxWidth = 48.0
            maxHeight = 48.0
        }
        val box = VBox(12.0, spinner, Label("Sending HTTP request to $url ...")).apply {
            alignment = Pos.CENTER
        }
        previewContainer.center = box
        requestContainer.top = null
        requestCodeArea.replaceText("Executing HTTP request to $url ...")
        requestContainer.center = VirtualizedScrollPane(requestCodeArea)
        fullResponseContainer.top = null
        fullResponseCodeArea.replaceText("Waiting for HTTP response stream from $url ...")
        fullResponseContainer.center = VirtualizedScrollPane(fullResponseCodeArea)
    }

    fun displayResult(result: HttpResponseResult) {
        currentSingleResult = result
        currentLoadTestStats = null
        exportMarkdownButton.isDisable = false
        showSingleRequestTabs()

        // 1. Update status badge
        if (result.errorMessage != null) {
            statusBadge.text = "ERROR"
            statusBadge.styleClass.setAll("badge-error")
        } else {
            statusBadge.text = "${result.protocol} ${result.statusCode} ${result.statusMessage}"
            val badgeStyle = when (result.statusCode) {
                in 200..299 -> "badge-success"
                in 300..399 -> "badge-redirect"
                else -> "badge-error"
            }
            statusBadge.styleClass.setAll(badgeStyle)
        }

        urlLabel.text = "${result.requestSpec.method.name} ${result.finalUrl}"

        sizeBadge.text = "Size: ${result.bodySizeFormatted}"
        sizeBadge.isVisible = true
        sizeBadge.isManaged = true

        timeBadge.text = String.format("Total: %.1f ms", result.timings.totalDurationMs)
        timeBadge.isVisible = true
        timeBadge.isManaged = true

        if (result.redirectCount > 0) {
            redirectBadge.text = "${result.redirectCount} Redirect(s)"
            redirectBadge.isVisible = true
            redirectBadge.isManaged = true
        } else {
            redirectBadge.isVisible = false
            redirectBadge.isManaged = false
        }

        // 2. Quick timings bar
        val t = result.timings
        acceptTimingLabel.text = String.format(
            "TTFB: %.2f ms (Connect: %.2f ms, Wait: %.2f ms)",
            t.timeUntilServerAcceptsMs,
            t.dnsDurationMs + t.tcpConnectDurationMs + t.tlsHandshakeDurationMs,
            t.serverAcceptAndWaitDurationMs
        )
        responseTimingLabel.text = String.format("Response Transfer: %.2f ms", t.responseTransferDurationMs)
        totalTimingLabel.text = String.format("Total Duration: %.2f ms", t.totalDurationMs)

        // 3. Populate single-response tabs
        populateResponseViews(
            result = result,
            targetPreviewTab = previewTab,
            targetPreviewContainer = previewContainer,
            targetOutputCodeArea = outputCodeArea,
            targetRawTab = rawTab,
            targetRawCodeArea = rawCodeArea,
            targetHeadersTab = headersTab,
            targetHeadersTable = headersTable,
            targetRequestCodeArea = requestCodeArea,
            targetRequestContainer = requestContainer,
            targetFullResponseCodeArea = fullResponseCodeArea,
            targetFullResponseContainer = fullResponseContainer,
            targetTimingsBox = timingsBox,
            targetTabPane = mainTabPane
        )
    }

    private fun populateResponseViews(
        result: HttpResponseResult,
        targetPreviewTab: Tab,
        targetPreviewContainer: BorderPane,
        targetOutputCodeArea: CodeArea,
        targetRawTab: Tab,
        targetRawCodeArea: CodeArea,
        targetHeadersTab: Tab,
        targetHeadersTable: TableView<Pair<String, String>>,
        targetRequestCodeArea: CodeArea,
        targetRequestContainer: BorderPane,
        targetFullResponseCodeArea: CodeArea,
        targetFullResponseContainer: BorderPane,
        targetTimingsBox: VBox,
        targetTabPane: TabPane
    ) {
        val flatHeaders = mutableListOf<Pair<String, String>>()
        for ((k, values) in result.headers) {
            for (v in values) {
                flatHeaders.add(k to v)
            }
        }
        targetHeadersTable.items = FXCollections.observableArrayList(flatHeaders)
        targetHeadersTab.text = "Headers (${flatHeaders.size})"

        populateExecutedRequestView(result, targetRequestCodeArea, targetRequestContainer)
        populateFullResponseView(result, targetFullResponseCodeArea, targetFullResponseContainer)
        populateTimingsBreakdown(result, targetTimingsBox)
        populateRawView(result, targetRawCodeArea)

        when (result.category) {
            ResponseContentCategory.JSON,
            ResponseContentCategory.XML,
            ResponseContentCategory.HTML,
            ResponseContentCategory.TEXT -> {
                targetPreviewTab.text = "Response Output (${result.category.name})"
                showTextOutput(result, targetOutputCodeArea, targetPreviewContainer)
            }
            ResponseContentCategory.IMAGE -> {
                targetPreviewTab.text = "Inline Image Preview"
                showInlineImage(result, targetPreviewContainer, targetRawTab, targetTabPane)
            }
            ResponseContentCategory.PDF -> {
                targetPreviewTab.text = "Inline PDF Viewer"
                showInlinePdf(result, targetPreviewContainer)
            }
            ResponseContentCategory.BINARY -> {
                targetPreviewTab.text = "Binary File Saved"
                showSavedBinaryCard(result, targetPreviewContainer, targetRawTab, targetTabPane)
            }
        }
    }

    private fun showTextOutput(
        result: HttpResponseResult,
        targetCodeArea: CodeArea = outputCodeArea,
        targetPreviewContainer: BorderPane = previewContainer
    ) {
        val rawStr = String(result.bodyBytes, StandardCharsets.UTF_8)
        val formatted = if (result.category == ResponseContentCategory.JSON) {
            prettyPrintJson(rawStr)
        } else {
            rawStr
        }

        val finalDisplay = buildString {
            if (result.errorMessage != null) {
                append("// ERROR: ").append(result.errorMessage).append("\n\n")
            }
            if (result.savedFile != null) {
                append("// Saved response copy to: ").append(result.savedFile.absolutePath).append("\n\n")
            }
            if (result.scriptOutput.isNotEmpty()) {
                append("// Response Script Output:\n")
                result.scriptOutput.forEach { append("//   ").append(it).append("\n") }
                append("\n")
            }
            append(formatted)
        }

        targetCodeArea.replaceText(finalDisplay)
        targetCodeArea.setStyleSpans(0, HttpSyntaxHighlighter.computeOutputHighlighting(finalDisplay))
        targetCodeArea.moveTo(0)
        targetCodeArea.requestFollowCaret()

        val toolbar = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 8.0, 6.0, 8.0)
            val wrapCheck = CheckBox("Wrap Lines").apply {
                isSelected = targetCodeArea.isWrapText
                selectedProperty().addListener { _, _, wrap -> targetCodeArea.isWrapText = wrap }
            }
            val copyBtn = Button("Copy Response").apply {
                styleClass.add("action-button")
                setOnAction {
                    val content = ClipboardContent()
                    content.putString(targetCodeArea.text)
                    Clipboard.getSystemClipboard().setContent(content)
                }
            }
            children.addAll(
                Label("Content-Type: ${result.contentType}"),
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                wrapCheck,
                copyBtn
            )
        }

        targetPreviewContainer.top = toolbar
        targetPreviewContainer.center = VirtualizedScrollPane(targetCodeArea)
    }

    private fun showInlineImage(
        result: HttpResponseResult,
        targetPreviewContainer: BorderPane = previewContainer,
        targetRawTab: Tab = rawTab,
        targetTabPane: TabPane = mainTabPane
    ) {
        val image = Image(ByteArrayInputStream(result.bodyBytes))
        if (image.isError) {
            showSavedBinaryCard(result, targetPreviewContainer, targetRawTab, targetTabPane)
            return
        }

        val imageView = ImageView(image).apply {
            isPreserveRatio = true
            isSmooth = true
        }

        val zoomSlider = Slider(0.1, 4.0, 1.0).apply {
            prefWidth = 180.0
        }
        val zoomValueLabel = Label("100%")
        val baseWidth = image.width.coerceAtLeast(1.0)
        val baseHeight = image.height.coerceAtLeast(1.0)

        zoomSlider.valueProperty().addListener { _, _, newVal ->
            val scale = newVal.toDouble()
            zoomValueLabel.text = "${(scale * 100).toInt()}%"
            imageView.fitWidth = baseWidth * scale
            imageView.fitHeight = baseHeight * scale
        }

        val fitBtn = Button("Fit to Window").apply {
            styleClass.add("action-button")
            setOnAction {
                val targetW = (targetPreviewContainer.width - 40).coerceAtLeast(100.0)
                val scale = (targetW / baseWidth).coerceIn(0.1, 4.0)
                zoomSlider.value = scale
            }
        }

        val resetBtn = Button("100%").apply {
            styleClass.add("action-button")
            setOnAction { zoomSlider.value = 1.0 }
        }

        val saveBtn = Button("Save Image to Output Folder").apply {
            styleClass.add("action-button")
            setOnAction {
                val outDir = outputDirectoryProvider().apply { mkdirs() }
                val ext = when {
                    result.contentType.contains("png", ignoreCase = true) -> "png"
                    result.contentType.contains("jpeg", ignoreCase = true) || result.contentType.contains("jpg", ignoreCase = true) -> "jpg"
                    result.contentType.contains("gif", ignoreCase = true) -> "gif"
                    else -> "png"
                }
                val outFile = File(outDir, "image_${System.currentTimeMillis()}.$ext")
                outFile.writeBytes(result.bodyBytes)
                statusBadge.text = "SAVED: ${outFile.name}"
            }
        }

        val infoLabel = Label("${image.width.toInt()} × ${image.height.toInt()} px  |  ${result.contentType}")
        val toolbar = HBox(10.0, infoLabel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, Label("Zoom:"), zoomSlider, zoomValueLabel, fitBtn, resetBtn, saveBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
        }

        val imageHolder = StackPane(imageView).apply {
            padding = Insets(20.0)
            style = "-fx-background-color: #10121a;"
        }
        val scrollPane = ScrollPane(imageHolder).apply {
            isFitToWidth = true
            isFitToHeight = true
        }

        targetPreviewContainer.top = toolbar
        targetPreviewContainer.center = scrollPane
    }

    private fun showInlinePdf(
        result: HttpResponseResult,
        targetPreviewContainer: BorderPane = previewContainer
    ) {
        val pagesBox = VBox(18.0).apply {
            alignment = Pos.TOP_CENTER
            padding = Insets(18.0)
            style = "-fx-background-color: #10121a;"
        }
        val scrollPane = ScrollPane(pagesBox).apply {
            isFitToWidth = true
        }

        val dpiCombo = ComboBox(FXCollections.observableArrayList(110f, 150f, 200f)).apply {
            value = 150f
        }
        val pageCountLabel = Label("Rendering PDF pages...")
        val pageImageViews = mutableListOf<ImageView>()
        var scaleToAvailableWidth = false

        fun applyScaleToAvailableWidth() {
            val targetWidth = computePdfAvailablePageWidth(
                viewportWidth = scrollPane.viewportBounds.width,
                scrollPaneWidth = scrollPane.width,
                containerWidth = targetPreviewContainer.width
            )
            pageImageViews.forEach { view ->
                view.fitWidth = targetWidth
            }
            pagesBox.requestLayout()
        }

        val fitWidthBtn = Button("Fit to Width").apply {
            styleClass.add("action-button")
            tooltip = Tooltip("Scale PDF document pages to the available width")
            setOnAction {
                scaleToAvailableWidth = true
                applyScaleToAvailableWidth()
            }
        }

        val savePdfBtn = Button("Save PDF to Output Folder").apply {
            styleClass.add("action-button")
            setOnAction {
                val outDir = outputDirectoryProvider().apply { mkdirs() }
                val outFile = File(outDir, "document_${System.currentTimeMillis()}.pdf")
                outFile.writeBytes(result.bodyBytes)
                pageCountLabel.text = "Saved to ${outFile.absolutePath}"
            }
        }

        scrollPane.viewportBoundsProperty().addListener { _, _, _ ->
            if (scaleToAvailableWidth) {
                applyScaleToAvailableWidth()
            }
        }
        targetPreviewContainer.widthProperty().addListener { _, _, _ ->
            if (scaleToAvailableWidth) {
                applyScaleToAvailableWidth()
            }
        }

        val toolbar = HBox(
            12.0,
            Label("Inline PDF Viewer"),
            pageCountLabel,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            Label("Render DPI:"),
            dpiCombo,
            fitWidthBtn,
            savePdfBtn
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
        }

        fun renderPdfAtDpi(dpi: Float) {
            pagesBox.children.setAll(ProgressIndicator(), Label("Rendering PDF at ${dpi.toInt()} DPI..."))
            val task = object : Task<List<Image>>() {
                override fun call(): List<Image> {
                    Loader.loadPDF(result.bodyBytes).use { doc ->
                        val renderer = PDFRenderer(doc)
                        val images = mutableListOf<Image>()
                        for (pageIdx in 0 until doc.numberOfPages) {
                            val buffered = renderer.renderImageWithDPI(pageIdx, dpi, ImageType.RGB)
                            images.add(SwingFXUtils.toFXImage(buffered, null))
                        }
                        return images
                    }
                }
            }
            task.setOnSucceeded {
                val renderedPages = task.value
                pageCountLabel.text = "${renderedPages.size} Page(s)  |  ${result.bodySizeFormatted}"
                pagesBox.children.clear()
                pageImageViews.clear()
                renderedPages.forEachIndexed { idx, img ->
                    val pageHeader = Label("Page ${idx + 1} of ${renderedPages.size}").apply {
                        styleClass.add("section-header")
                    }
                    val view = ImageView(img).apply {
                        isPreserveRatio = true
                        isSmooth = true
                    }
                    pageImageViews.add(view)
                    val pageCard = VBox(6.0, pageHeader, view).apply {
                        alignment = Pos.CENTER
                        padding = Insets(10.0)
                        style = "-fx-background-color: #1b1f2e; -fx-border-color: #2f364f; -fx-border-radius: 6; -fx-background-radius: 6;"
                    }
                    pagesBox.children.add(pageCard)
                }
                if (scaleToAvailableWidth) {
                    applyScaleToAvailableWidth()
                }
            }
            task.setOnFailed {
                val ex = task.exception
                pagesBox.children.setAll(Label("Failed to render PDF inline: ${ex?.message}").apply {
                    styleClass.add("badge-error")
                })
            }
            Thread(task, "pdf-inline-renderer").apply { isDaemon = true }.start()
        }

        dpiCombo.valueProperty().addListener { _, _, newDpi ->
            if (newDpi != null) {
                scaleToAvailableWidth = false
                renderPdfAtDpi(newDpi)
            }
        }

        targetPreviewContainer.top = toolbar
        targetPreviewContainer.center = scrollPane
        renderPdfAtDpi(dpiCombo.value ?: 150f)
    }

    private fun showSavedBinaryCard(
        result: HttpResponseResult,
        targetPreviewContainer: BorderPane = previewContainer,
        targetRawTab: Tab = rawTab,
        targetTabPane: TabPane = mainTabPane
    ) {
        targetPreviewContainer.top = null
        val saved = result.savedFile
        val title = Label("Binary Response Stored Locally").apply {
            style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #38bdf8;"
        }
        val pathText = saved?.absolutePath ?: "Not saved"
        val details = GridPane().apply {
            hgap = 14.0
            vgap = 8.0
            alignment = Pos.CENTER
            add(Label("Content-Type:"), 0, 0)
            add(Label(result.contentType), 1, 0)
            add(Label("Payload Size:"), 0, 1)
            add(Label("${result.bodySizeFormatted} (${result.bodyBytes.size} bytes)"), 1, 1)
            add(Label("Saved File Path:"), 0, 2)
            val pathField = TextField(pathText).apply {
                isEditable = false
                prefWidth = 480.0
                style = "-fx-font-family: monospace;"
            }
            add(pathField, 1, 2)
        }

        val openFolderBtn = Button("Open Output Folder").apply {
            styleClass.add("primary-button")
            setOnAction {
                val folder = saved?.parentFile ?: outputDirectoryProvider()
                openInDesktop(folder)
            }
        }
        val viewHexBtn = Button("Inspect Hex Dump").apply {
            styleClass.add("action-button")
            setOnAction {
                targetTabPane.selectionModel.select(targetRawTab)
            }
        }

        val card = VBox(16.0, title, details, HBox(12.0, openFolderBtn, viewHexBtn).apply { alignment = Pos.CENTER }).apply {
            alignment = Pos.CENTER
            padding = Insets(28.0)
            maxWidth = 680.0
            maxHeight = 320.0
            styleClass.add("timing-card")
        }

        targetPreviewContainer.center = StackPane(card)
    }

    private fun populateRawView(result: HttpResponseResult, targetRawCodeArea: CodeArea = rawCodeArea) {
        val text = if (result.category in setOf(ResponseContentCategory.BINARY, ResponseContentCategory.IMAGE, ResponseContentCategory.PDF)) {
            formatHexDump(result.bodyBytes)
        } else {
            String(result.bodyBytes, StandardCharsets.UTF_8)
        }
        targetRawCodeArea.replaceText(text)
        targetRawCodeArea.moveTo(0)
    }

    private fun populateExecutedRequestView(
        result: HttpResponseResult,
        targetRequestCodeArea: CodeArea = requestCodeArea,
        targetRequestContainer: BorderPane = requestContainer
    ) {
        val wireText = result.formatWireRequestText().replace("\r\n", "\n").replace("\r", "\n")
        targetRequestCodeArea.replaceText(wireText)
        val currentText = targetRequestCodeArea.text ?: ""
        if (currentText.isNotEmpty()) {
            try {
                val spans = HttpSyntaxHighlighter.computeHttpHighlighting(currentText)
                if (spans.length() == targetRequestCodeArea.length) {
                    targetRequestCodeArea.setStyleSpans(0, spans)
                }
            } catch (_: Exception) {
            }
        }
        targetRequestCodeArea.moveTo(0)
        targetRequestCodeArea.requestFollowCaret()

        val execReq = result.executedRequest
        val metaText = if (execReq != null) {
            val hopsInfo = if (result.executedRequests.size > 1) "Hops: ${result.executedRequests.size}" else ""
            hopsInfo
        } else {
            "${result.requestSpec.method.name} ${result.finalUrl.ifBlank { result.resolvedUrl }}"
        }

        val toolbar = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 8.0, 6.0, 8.0)
            val wrapCheck = CheckBox("Wrap Lines").apply {
                isSelected = targetRequestCodeArea.isWrapText
                selectedProperty().addListener { _, _, wrap -> targetRequestCodeArea.isWrapText = wrap }
            }
            val copyBtn = Button("Copy Request").apply {
                styleClass.add("action-button")
                setOnAction {
                    val content = ClipboardContent()
                    content.putString(targetRequestCodeArea.text)
                    Clipboard.getSystemClipboard().setContent(content)
                }
            }
            children.addAll(
                Label(metaText),
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                wrapCheck,
                copyBtn
            )
        }

        targetRequestContainer.top = toolbar
        targetRequestContainer.center = VirtualizedScrollPane(targetRequestCodeArea)
    }

    private fun populateFullResponseView(
        result: HttpResponseResult,
        targetFullResponseCodeArea: CodeArea = fullResponseCodeArea,
        targetFullResponseContainer: BorderPane = fullResponseContainer
    ) {
        val wireText = result.formatWireResponseText().replace("\r\n", "\n").replace("\r", "\n")
        targetFullResponseCodeArea.replaceText(wireText)
        val currentText = targetFullResponseCodeArea.text ?: ""
        if (currentText.isNotEmpty()) {
            try {
                val spans = HttpSyntaxHighlighter.computeHttpHighlighting(currentText)
                if (spans.length() == targetFullResponseCodeArea.length) {
                    targetFullResponseCodeArea.setStyleSpans(0, spans)
                }
            } catch (_: Exception) {
            }
        }
        targetFullResponseCodeArea.moveTo(0)
        targetFullResponseCodeArea.requestFollowCaret()

        val execResp = result.executedResponse
        val metaText = if (execResp != null) {
            val hopsInfo = if (result.executedResponses.size > 1) "Hops: ${result.executedResponses.size}" else ""
            hopsInfo
        } else {
            ""
        }

        val toolbar = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 8.0, 6.0, 8.0)
            val wrapCheck = CheckBox("Wrap Lines").apply {
                isSelected = targetFullResponseCodeArea.isWrapText
                selectedProperty().addListener { _, _, wrap -> targetFullResponseCodeArea.isWrapText = wrap }
            }
            val copyBtn = Button("Copy Full Response").apply {
                styleClass.add("action-button")
                setOnAction {
                    val content = ClipboardContent()
                    content.putString(targetFullResponseCodeArea.text)
                    Clipboard.getSystemClipboard().setContent(content)
                }
            }
            children.addAll(
                Label(metaText),
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                wrapCheck,
                copyBtn
            )
        }

        targetFullResponseContainer.top = toolbar
        targetFullResponseContainer.center = VirtualizedScrollPane(targetFullResponseCodeArea)
    }

    private fun populateTimingsBreakdown(result: HttpResponseResult, targetTimingsBox: VBox = timingsBox) {
        targetTimingsBox.children.clear()
        val t = result.timings
        val total = t.totalDurationMs.coerceAtLeast(0.01)

        val header = Label("HTTP Request Lifecycle Timing Breakdown").apply {
            style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;"
        }

        val summaryCards = HBox(14.0).apply {
            children.addAll(
                createMetricCard(
                    "Until Server Accepts & Responds (TTFB)",
                    String.format("%.2f ms", t.timeUntilServerAcceptsMs),
                    "DNS + TCP Connect + TLS + Send + Server Processing",
                    "#38bdf8"
                ),
                createMetricCard(
                    "Server Processing / Acceptance Wait",
                    String.format("%.2f ms", t.serverAcceptAndWaitDurationMs),
                    "Time from request sent until first response byte",
                    "#fbbf24"
                ),
                createMetricCard(
                    "Response Body Download",
                    String.format("%.2f ms", t.responseTransferDurationMs),
                    "Reading ${result.bodySizeFormatted} from server",
                    "#34d399"
                ),
                createMetricCard(
                    "Total Roundtrip Duration",
                    String.format("%.2f ms", t.totalDurationMs),
                    if (result.tlsCipherSuite != null) "TLS: ${result.tlsCipherSuite}" else "Protocol: ${result.protocol}",
                    "#a855f7"
                )
            )
        }

        val phasesGrid = GridPane().apply {
            hgap = 16.0
            vgap = 10.0
            padding = Insets(12.0)
            styleClass.add("timing-card")
        }

        data class PhaseRow(val label: String, val ms: Double, val colorHex: String, val description: String)

        val phases = listOf(
            PhaseRow("1. DNS Lookup", t.dnsDurationMs, "#60a5fa", "Resolving hostname to IP address"),
            PhaseRow("2. TCP Connection", t.tcpConnectDurationMs, "#818cf8", "Establishing TCP socket handshake"),
            PhaseRow("3. TLS Handshake", t.tlsHandshakeDurationMs, "#c084fc", "Negotiating SSL/TLS encryption & certificates"),
            PhaseRow("4. Request Dispatch", t.requestSentDurationMs, "#f472b6", "Transmitting HTTP headers and request body"),
            PhaseRow("5. Server Accept & Processing (TTFB)", t.serverAcceptAndWaitDurationMs, "#fbbf24", "Waiting for server to accept and send response headers"),
            PhaseRow("6. Response Body Transfer", t.responseTransferDurationMs, "#34d399", "Downloading response payload stream")
        )

        phases.forEachIndexed { idx, phase ->
            val nameLbl = Label(phase.label).apply { style = "-fx-font-weight: bold;" }
            val descLbl = Label(phase.description).apply { style = "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;" }
            val barWidth = ((phase.ms / total) * 280.0).coerceIn(4.0, 280.0)
            val bar = Rectangle(barWidth, 12.0, Color.web(phase.colorHex)).apply {
                arcWidth = 6.0
                arcHeight = 6.0
            }
            val msLbl = Label(String.format("%.2f ms", phase.ms)).apply {
                style = "-fx-font-family: monospace; -fx-font-weight: bold;"
            }
            phasesGrid.add(VBox(2.0, nameLbl, descLbl), 0, idx)
            phasesGrid.add(bar, 1, idx)
            phasesGrid.add(msLbl, 2, idx)
        }

        targetTimingsBox.children.addAll(header, summaryCards, phasesGrid)
    }

    private fun createMetricCard(title: String, value: String, subtitle: String, accentHex: String): VBox {
        val titleLbl = Label(title).apply { style = "-fx-font-size: 11.5px; -fx-text-fill: #94a3b8;" }
        val valueLbl = Label(value).apply {
            style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: $accentHex; -fx-font-family: monospace;"
        }
        val subLbl = Label(subtitle).apply { style = "-fx-font-size: 10.5px; -fx-text-fill: #64748b;" }
        return VBox(4.0, titleLbl, valueLbl, subLbl).apply {
            styleClass.add("timing-card")
            HBox.setHgrow(this, Priority.ALWAYS)
        }
    }

    private fun setupHeadersTable(table: TableView<Pair<String, String>>) {
        table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        val nameCol = TableColumn<Pair<String, String>, String>("Header Name").apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.first) }
            minWidth = 180.0
            maxWidth = 280.0
        }
        val valCol = TableColumn<Pair<String, String>, String>("Header Value").apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.second) }
        }
        table.columns.setAll(nameCol, valCol)
    }

    private fun setupLoadSamplesTable() {
        val idxCol = TableColumn<LoadSampleResult, String>("#").apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.requestIndex.toString()) }
            maxWidth = 60.0
            style = "-fx-font-family: monospace;"
        }
        val threadCol = TableColumn<LoadSampleResult, String>("Thread").apply {
            cellValueFactory = Callback { SimpleStringProperty("T-${it.value.threadId}") }
            maxWidth = 75.0
            style = "-fx-font-family: monospace;"
        }
        val statusCol = TableColumn<LoadSampleResult, String>("Status").apply {
            cellValueFactory = Callback {
                val s = it.value
                SimpleStringProperty(if (s.statusCode > 0) "HTTP ${s.statusCode}" else "ERR")
            }
            maxWidth = 95.0
            style = "-fx-font-family: monospace; -fx-font-weight: bold;"
        }
        val startCol = TableColumn<LoadSampleResult, String>("Started At").apply {
            cellValueFactory = Callback { SimpleStringProperty(String.format("+%.0f ms", it.value.startOffsetMs)) }
            style = "-fx-alignment: CENTER-RIGHT; -fx-font-family: monospace;"
        }
        val ttfbCol = TableColumn<LoadSampleResult, String>("TTFB").apply {
            cellValueFactory = Callback { SimpleStringProperty(String.format("%.2f ms", it.value.timings.timeUntilServerAcceptsMs)) }
            style = "-fx-alignment: CENTER-RIGHT; -fx-font-family: monospace;"
        }
        val waitCol = TableColumn<LoadSampleResult, String>("Server Wait").apply {
            cellValueFactory = Callback { SimpleStringProperty(String.format("%.2f ms", it.value.timings.serverAcceptAndWaitDurationMs)) }
            style = "-fx-alignment: CENTER-RIGHT; -fx-font-family: monospace;"
        }
        val transferCol = TableColumn<LoadSampleResult, String>("Transfer").apply {
            cellValueFactory = Callback { SimpleStringProperty(String.format("%.2f ms", it.value.timings.responseTransferDurationMs)) }
            style = "-fx-alignment: CENTER-RIGHT; -fx-font-family: monospace;"
        }
        val totalCol = TableColumn<LoadSampleResult, String>("Total Duration").apply {
            cellValueFactory = Callback { SimpleStringProperty(String.format("%.2f ms", it.value.timings.totalDurationMs)) }
            style = "-fx-alignment: CENTER-RIGHT; -fx-font-family: monospace; -fx-font-weight: bold;"
        }
        val bytesCol = TableColumn<LoadSampleResult, String>("Size").apply {
            cellValueFactory = Callback { SimpleStringProperty("${it.value.payloadBytes} B") }
            style = "-fx-alignment: CENTER-RIGHT; -fx-font-family: monospace;"
        }

        loadSamplesTable.columns.setAll(idxCol, threadCol, statusCol, startCol, ttfbCol, waitCol, transferCol, totalCol, bytesCol)
        loadSamplesTable.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            if (selected != null) {
                displaySelectedLoadSample(selected)
            }
        }
    }

    private fun displaySelectedLoadSample(sample: LoadSampleResult) {
        sampleDetailTitleLabel.text = "Selected Request Sample #${sample.requestIndex} (Thread T-${sample.threadId}):"
        val res = sample.responseResult
        if (res != null) {
            if (res.errorMessage != null) {
                sampleDetailStatusBadge.text = "ERROR"
                sampleDetailStatusBadge.styleClass.setAll("badge-error")
            } else {
                sampleDetailStatusBadge.text = "${res.protocol} ${res.statusCode} ${res.statusMessage}"
                val badgeStyle = when (res.statusCode) {
                    in 200..299 -> "badge-success"
                    in 300..399 -> "badge-redirect"
                    else -> "badge-error"
                }
                sampleDetailStatusBadge.styleClass.setAll(badgeStyle)
            }
            sampleDetailMetaLabel.text = String.format(
                "Started +%.0f ms  |  Total: %.2f ms  |  TTFB: %.2f ms  |  Size: %s",
                sample.startOffsetMs,
                sample.timings.totalDurationMs,
                sample.timings.timeUntilServerAcceptsMs,
                res.bodySizeFormatted
            )
            populateResponseViews(
                result = res,
                targetPreviewTab = samplePreviewTab,
                targetPreviewContainer = samplePreviewContainer,
                targetOutputCodeArea = sampleOutputCodeArea,
                targetRawTab = sampleRawTab,
                targetRawCodeArea = sampleRawCodeArea,
                targetHeadersTab = sampleHeadersTab,
                targetHeadersTable = sampleHeadersTable,
                targetRequestCodeArea = sampleRequestCodeArea,
                targetRequestContainer = sampleRequestContainer,
                targetFullResponseCodeArea = sampleFullResponseCodeArea,
                targetFullResponseContainer = sampleFullResponseContainer,
                targetTimingsBox = sampleTimingsBox,
                targetTabPane = sampleDetailTabPane
            )
        } else {
            sampleDetailStatusBadge.text = if (sample.statusCode > 0) "HTTP ${sample.statusCode}" else "ERR"
            sampleDetailStatusBadge.styleClass.setAll(if (sample.isSuccess) "badge-success" else "badge-error")
            sampleDetailMetaLabel.text = String.format(
                "Started +%.0f ms  |  Total: %.2f ms  |  TTFB: %.2f ms  |  Size: %d B",
                sample.startOffsetMs,
                sample.timings.totalDurationMs,
                sample.timings.timeUntilServerAcceptsMs,
                sample.payloadBytes
            )
            val fallbackResult = HttpResponseResult(
                requestSpec = HttpRequestSpec(id = "sample-${sample.requestIndex}"),
                resolvedUrl = "",
                finalUrl = "",
                statusCode = sample.statusCode,
                statusMessage = if (sample.isSuccess) "OK" else "Error",
                protocol = "HTTP",
                headers = emptyMap(),
                bodyBytes = (sample.errorMessage ?: "").toByteArray(StandardCharsets.UTF_8),
                contentType = "text/plain",
                category = ResponseContentCategory.TEXT,
                timings = sample.timings,
                errorMessage = sample.errorMessage
            )
            populateResponseViews(
                result = fallbackResult,
                targetPreviewTab = samplePreviewTab,
                targetPreviewContainer = samplePreviewContainer,
                targetOutputCodeArea = sampleOutputCodeArea,
                targetRawTab = sampleRawTab,
                targetRawCodeArea = sampleRawCodeArea,
                targetHeadersTab = sampleHeadersTab,
                targetHeadersTable = sampleHeadersTable,
                targetRequestCodeArea = sampleRequestCodeArea,
                targetRequestContainer = sampleRequestContainer,
                targetFullResponseCodeArea = sampleFullResponseCodeArea,
                targetFullResponseContainer = sampleFullResponseContainer,
                targetTimingsBox = sampleTimingsBox,
                targetTabPane = sampleDetailTabPane
            )
        }
    }

    private fun clearSampleDetailView() {
        sampleDetailTitleLabel.text = "Selected Request Sample Details:"
        sampleDetailStatusBadge.text = "None Selected"
        sampleDetailStatusBadge.styleClass.setAll("badge-neutral")
        sampleDetailMetaLabel.text = "Select a request sample above to inspect its executed request, full response, response output, raw payload, headers, and timing breakdown."
        samplePreviewTab.text = "Response Output"
        sampleHeadersTab.text = "Headers (0)"
        sampleHeadersTable.items.clear()
        sampleOutputCodeArea.clear()
        sampleRawCodeArea.clear()
        sampleRequestCodeArea.clear()
        sampleFullResponseCodeArea.clear()
        sampleTimingsBox.children.clear()
        samplePreviewContainer.top = null
        samplePreviewContainer.center = VirtualizedScrollPane(sampleOutputCodeArea)
        sampleRequestContainer.top = null
        sampleRequestContainer.center = VirtualizedScrollPane(sampleRequestCodeArea)
        sampleFullResponseContainer.top = null
        sampleFullResponseContainer.center = VirtualizedScrollPane(sampleFullResponseCodeArea)
    }

    private fun setupLoadTestPlaceholder() {
        loadTestBox.children.setAll(
            Label("No Multi-Request Load Test Executed Yet").apply {
                style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;"
            }
        )
    }

    fun resetLoadTestView() {
        currentLoadTestStats = null
        hasAutoSelectedFirstSample = false
        loadStopButton.setOnAction(null)
        loadStopButton.isVisible = false
        loadStopButton.isManaged = false
        loadProgressBar.progress = 0.0
        loadProgressLabel.text = "0 / 0"
        totalMsSeries.data.clear()
        ttfbMsSeries.data.clear()
        movingAvgSeries.data.clear()
        loadKpiRow.children.clear()
        loadElaborateResultsBox.children.clear()
        loadSamplesItems.clear()
        clearSampleDetailView()
        setupLoadTestPlaceholder()
    }

    fun startLoadTestView(spec: HttpRequestSpec, config: LoadTestConfig, resolvedUrl: String, onStopRequested: () -> Unit) {
        currentSingleResult = null
        currentLoadTestStats = null
        exportMarkdownButton.isDisable = true
        hasAutoSelectedFirstSample = false
        showLoadTestTabOnly()

        statusBadge.text = "LOAD TEST (0/${config.totalRequests})"
        statusBadge.styleClass.setAll("badge-redirect")
        urlLabel.text = "${spec.method.name} $resolvedUrl  [${config.parallelism} threads, ${config.rampUpSeconds}s ramp-up]"

        sizeBadge.isVisible = false
        sizeBadge.isManaged = false
        timeBadge.isVisible = false
        timeBadge.isManaged = false
        redirectBadge.isVisible = false
        redirectBadge.isManaged = false

        loadStopButton.setOnAction { onStopRequested() }
        loadStopButton.isVisible = true
        loadStopButton.isManaged = true

        totalMsSeries.data.clear()
        ttfbMsSeries.data.clear()
        movingAvgSeries.data.clear()

        loadProgressBar.progress = 0.0
        loadProgressLabel.text = "0 / ${config.totalRequests} (0%)"

        val topProgressRow = HBox(
            12.0,
            Label("Live Execution Progress:").apply { style = "-fx-font-weight: bold;" },
            loadProgressBar,
            loadProgressLabel,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            loadStopButton
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("timing-card")
        }

        loadKpiRow.children.clear()
        loadElaborateResultsBox.children.clear()
        loadSamplesItems.clear()
        loadSamplesSectionLabel.text = "Individual Request Samples (0 recorded — click a row to inspect):"
        clearSampleDetailView()
        loadSamplesAndDetailBox.children.setAll(
            Separator(),
            loadSamplesSectionLabel,
            loadSamplesTable,
            sampleDetailContainer
        )

        loadTestBox.children.setAll(
            topProgressRow,
            loadKpiRow,
            liveTimingChart,
            loadElaborateResultsBox,
            loadSamplesAndDetailBox
        )
    }

    fun updateLoadTestProgress(stats: LoadTestStatistics) {
        currentLoadTestStats = stats
        currentSingleResult = null
        exportMarkdownButton.isDisable = false

        val pct = if (stats.totalPlanned > 0) stats.completedCount.toDouble() / stats.totalPlanned else 0.0
        loadProgressBar.progress = pct.coerceIn(0.0, 1.0)
        loadProgressLabel.text = String.format(
            "%d / %d (%d%%)  |  Active Threads: %d  |  Elapsed: %.2f s",
            stats.completedCount,
            stats.totalPlanned,
            (pct * 100).toInt(),
            stats.activeThreads,
            stats.elapsedDurationMs / 1000.0
        )

        // Update top header bar
        if (stats.isFinished) {
            loadStopButton.isVisible = false
            loadStopButton.isManaged = false
            val badgeClass = when {
                stats.isCancelled -> "badge-redirect"
                stats.failureCount == 0 && stats.completedCount > 0 -> "badge-success"
                stats.successCount > 0 -> "badge-redirect"
                else -> "badge-error"
            }
            val stateText = if (stats.isCancelled) "CANCELLED" else "COMPLETED"
            statusBadge.text = "LOAD $stateText (${stats.successCount}/${stats.completedCount} OK)"
            statusBadge.styleClass.setAll(badgeClass)
        } else {
            statusBadge.text = "RUNNING (${stats.completedCount}/${stats.totalPlanned})"
            statusBadge.styleClass.setAll("badge-redirect")
        }

        timeBadge.text = String.format("RPS: %.1f req/s", stats.requestsPerSecond)
        timeBadge.isVisible = true
        timeBadge.isManaged = true

        sizeBadge.text = String.format("Mean: %.1f ms | P95: %.1f ms", stats.totalDurationStats.meanMs, stats.totalDurationStats.p95Ms)
        sizeBadge.isVisible = true
        sizeBadge.isManaged = true

        acceptTimingLabel.text = String.format(
            "Avg TTFB: %.2f ms (P95: %.2f ms)",
            stats.ttfbStats.meanMs,
            stats.ttfbStats.p95Ms
        )
        responseTimingLabel.text = String.format(
            "Avg Transfer: %.2f ms  |  Throughput: %s/s",
            stats.transferStats.meanMs,
            formatBytes(stats.throughputBytesPerSecond.toLong())
        )
        totalTimingLabel.text = String.format(
            "Avg Total: %.2f ms (Min: %.1f, Median: %.1f, P99: %.1f, Max: %.1f ms)",
            stats.totalDurationStats.meanMs,
            stats.totalDurationStats.minMs,
            stats.totalDurationStats.medianMs,
            stats.totalDurationStats.p99Ms,
            stats.totalDurationStats.maxMs
        )

        // Update KPI summary cards
        val successRate = if (stats.completedCount > 0) (stats.successCount * 100.0 / stats.completedCount) else 0.0
        loadKpiRow.children.setAll(
            createMetricCard(
                "Throughput (RPS)",
                String.format("%.2f req/s", stats.requestsPerSecond),
                "${stats.completedCount}/${stats.totalPlanned} completed in ${String.format("%.2f s", stats.elapsedDurationMs / 1000.0)}",
                "#38bdf8"
            ),
            createMetricCard(
                "Success / Error Rate",
                String.format("%.1f%% OK", successRate),
                "${stats.successCount} succeeded, ${stats.failureCount} failed",
                if (stats.failureCount == 0) "#34d399" else "#f87171"
            ),
            createMetricCard(
                "Mean / Median Latency",
                String.format("%.1f / %.1f ms", stats.totalDurationStats.meanMs, stats.totalDurationStats.medianMs),
                String.format("Min: %.1f ms | Max: %.1f ms | σ: %.1f ms", stats.totalDurationStats.minMs, stats.totalDurationStats.maxMs, stats.totalDurationStats.stdDevMs),
                "#fbbf24"
            ),
            createMetricCard(
                "Tail Latency (P90 / P95 / P99)",
                String.format("%.0f / %.0f / %.0f ms", stats.totalDurationStats.p90Ms, stats.totalDurationStats.p95Ms, stats.totalDurationStats.p99Ms),
                "Bandwidth: ${formatBytes(stats.throughputBytesPerSecond.toLong())}/s (${formatBytes(stats.totalBytesReceived)} total)",
                "#c084fc"
            )
        )

        // Update Live Line Chart (downsample if > 250 points for smooth rendering)
        updateLiveTimingChart(stats.samples)

        // Update Individual Request Samples table & preserve selection
        updateLoadSamplesTable(stats.samples)

        // If finished, build the elaborate post-run statistical analysis section
        if (stats.isFinished) {
            populateElaborateLoadTestStatistics(stats)
        }
    }

    private fun updateLoadSamplesTable(samples: List<LoadSampleResult>) {
        val ordered = samples.sortedBy { it.requestIndex }
        loadSamplesSectionLabel.text = "Individual Request Samples (${ordered.size} recorded — click a row to inspect):"
        val previousSelectedReqIdx = loadSamplesTable.selectionModel.selectedItem?.requestIndex
        loadSamplesItems.setAll(ordered)

        if (previousSelectedReqIdx != null) {
            val match = ordered.firstOrNull { it.requestIndex == previousSelectedReqIdx }
            if (match != null) {
                loadSamplesTable.selectionModel.select(match)
            }
        } else if (!hasAutoSelectedFirstSample && ordered.isNotEmpty()) {
            hasAutoSelectedFirstSample = true
            loadSamplesTable.selectionModel.select(ordered.first())
        }
    }

    private fun updateLiveTimingChart(samples: List<LoadSampleResult>) {
        if (samples.isEmpty()) return
        val ordered = samples.sortedBy { it.requestIndex }
        val maxPoints = 220
        val step = (ordered.size / maxPoints).coerceAtLeast(1)

        val totalPoints = mutableListOf<XYChart.Data<Number, Number>>()
        val ttfbPoints = mutableListOf<XYChart.Data<Number, Number>>()
        val movingAvgPoints = mutableListOf<XYChart.Data<Number, Number>>()

        val windowSize = 10
        val recentWindow = ArrayDeque<Double>()

        for ((idx, s) in ordered.withIndex()) {
            val dur = s.timings.totalDurationMs
            recentWindow.addLast(dur)
            if (recentWindow.size > windowSize) {
                recentWindow.removeFirst()
            }
            val movAvg = recentWindow.average()

            if (idx % step == 0 || idx == ordered.size - 1) {
                totalPoints.add(XYChart.Data(s.requestIndex, dur))
                ttfbPoints.add(XYChart.Data(s.requestIndex, s.timings.timeUntilServerAcceptsMs))
                movingAvgPoints.add(XYChart.Data(s.requestIndex, movAvg))
            }
        }

        totalMsSeries.data.setAll(totalPoints)
        ttfbMsSeries.data.setAll(ttfbPoints)
        movingAvgSeries.data.setAll(movingAvgPoints)
    }

    private fun populateElaborateLoadTestStatistics(stats: LoadTestStatistics) {
        loadElaborateResultsBox.children.clear()

        val sectionTitle = Label("Elaborate Load Test Results & Statistical Breakdown").apply {
            style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;"
        }

        // 1. Detailed Phase Percentile Matrix Table (Total, TTFB, Server Wait, Response Transfer, Connect+TLS)
        data class PhaseStatRow(val phaseName: String, val m: MetricStats)

        val phaseRows = FXCollections.observableArrayList(
            PhaseStatRow("Total Roundtrip Duration", stats.totalDurationStats),
            PhaseStatRow("Until Server Accepts (TTFB)", stats.ttfbStats),
            PhaseStatRow("Server Accept & Processing Wait", stats.serverWaitStats),
            PhaseStatRow("Response Body Transfer", stats.transferStats),
            PhaseStatRow("Connection & TLS Handshake", stats.connectStats)
        )

        val percentileTable = TableView(phaseRows).apply {
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            prefHeight = 185.0
            minHeight = 165.0
        }

        fun fmtCol(header: String, extractor: (MetricStats) -> Double): TableColumn<PhaseStatRow, String> {
            return TableColumn<PhaseStatRow, String>(header).apply {
                cellValueFactory = Callback {
                    SimpleStringProperty(String.format("%.2f ms", extractor(it.value.m)))
                }
                style = "-fx-alignment: CENTER-RIGHT; -fx-font-family: monospace;"
            }
        }

        val phaseCol = TableColumn<PhaseStatRow, String>("Timing Metric / Phase").apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.phaseName) }
            minWidth = 210.0
            style = "-fx-font-weight: bold;"
        }

        percentileTable.columns.addAll(
            phaseCol,
            fmtCol("Min") { it.minMs },
            fmtCol("Mean (Avg)") { it.meanMs },
            fmtCol("StdDev (σ)") { it.stdDevMs },
            fmtCol("P50 (Median)") { it.p50Ms },
            fmtCol("P75") { it.p75Ms },
            fmtCol("P90") { it.p90Ms },
            fmtCol("P95") { it.p95Ms },
            fmtCol("P99") { it.p99Ms },
            fmtCol("Max") { it.maxMs }
        )

        // 2. Latency Distribution Histogram BarChart + Status Code / Error Breakdown side-by-side
        val histXAxis = CategoryAxis().apply { label = "Latency Bucket (ms)" }
        val histYAxis = NumberAxis().apply { label = "Requests"; isForceZeroInRange = true }
        val histChart = BarChart(histXAxis, histYAxis).apply {
            title = "Response Time Distribution Histogram"
            animated = false
            isLegendVisible = false
            prefHeight = 230.0
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val histSeries = XYChart.Series<String, Number>()
        for (bucket in stats.histogram) {
            histSeries.data.add(XYChart.Data(bucket.rangeLabel, bucket.count))
        }
        histChart.data.add(histSeries)

        val statusBreakdownBox = VBox(8.0).apply {
            styleClass.add("timing-card")
            prefWidth = 310.0
            minWidth = 260.0
            children.add(Label("HTTP Status & Error Breakdown").apply {
                style = "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #f8fafc;"
            })
        }

        for ((code, count) in stats.statusCodeCounts) {
            val pct = if (stats.completedCount > 0) (count * 100.0 / stats.completedCount) else 0.0
            val codeText = if (code == 0) "Network / Timeout Error" else "HTTP $code"
            val color = when (code) {
                in 200..299 -> "#34d399"
                in 300..399 -> "#fbbf24"
                else -> "#f87171"
            }
            val row = HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                val badge = Label(codeText).apply {
                    style = "-fx-text-fill: $color; -fx-font-weight: bold; -fx-font-family: monospace;"
                }
                val countLbl = Label(String.format("%d req  (%.1f%%)", count, pct)).apply {
                    style = "-fx-font-family: monospace;"
                }
                children.addAll(badge, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, countLbl)
            }
            statusBreakdownBox.children.add(row)
        }

        if (stats.errorCounts.isNotEmpty()) {
            statusBreakdownBox.children.add(Separator())
            statusBreakdownBox.children.add(Label("Top Errors:").apply { styleClass.add("section-header") })
            for ((err, count) in stats.errorCounts.entries.take(5)) {
                statusBreakdownBox.children.add(
                    Label("${count}× $err").apply {
                        style = "-fx-text-fill: #f87171; -fx-font-size: 11px;"
                        isWrapText = true
                    }
                )
            }
        }

        statusBreakdownBox.children.add(Separator())
        statusBreakdownBox.children.addAll(
            Label("Configuration Summary:").apply { styleClass.add("section-header") },
            Label("Parallelism: ${stats.config.parallelism} worker threads").apply { style = "-fx-font-size: 11.5px;" },
            Label("Ramp-Up: ${stats.config.rampUpSeconds} s  |  Delay: ${stats.config.delayBetweenRequestsMs} ms").apply { style = "-fx-font-size: 11.5px;" },
            Label("Connection Pool Reuse: ${if (stats.config.reuseConnectionPool) "Enabled" else "Disabled"}").apply { style = "-fx-font-size: 11.5px;" }
        )

        val distributionRow = HBox(14.0, histChart, statusBreakdownBox)

        loadElaborateResultsBox.children.addAll(
            Separator(),
            sectionTitle,
            Label("Percentile & Statistical Distribution by HTTP Lifecycle Phase:").apply { styleClass.add("section-header") },
            percentileTable,
            distributionRow
        )
    }

    fun buildMarkdownSummary(): String {
        val stats = currentLoadTestStats
        if (stats != null) {
            val selectedSample = loadSamplesTable.selectionModel.selectedItem ?: stats.samples.firstOrNull()
            return formatLoadTestMarkdown(stats, selectedSample)
        }
        val single = currentSingleResult
        if (single != null) {
            return formatSingleRequestMarkdown(single)
        }
        return "# HTTP Response Summary\n\nNo request or load test has been executed yet.\n"
    }

    fun openExportMarkdownDialog(): MarkdownExportDialog {
        val markdown = buildMarkdownSummary()
        val titleSuffix = when {
            currentLoadTestStats != null -> "Load Test: ${currentLoadTestStats!!.requestSpec.displayTitle}"
            currentSingleResult != null -> currentSingleResult!!.requestSpec.displayTitle
            else -> "Summary"
        }
        val dialog = MarkdownExportDialog(
            owner = scene?.window,
            titleSuffix = titleSuffix,
            markdownSource = markdown,
            initialOutputDirectory = outputDirectoryProvider()
        )
        dialog.show()
        return dialog
    }

    private fun formatBytes(bytes: Long): String {
        return formatBytesStatic(bytes)
    }

    private fun prettyPrintJson(raw: String): String {
        return prettyPrintJsonStatic(raw)
    }

    private fun formatHexDump(bytes: ByteArray, maxBytes: Int = 16384): String {
        return formatHexDumpStatic(bytes, maxBytes)
    }

    private fun openInDesktop(file: File) {
        Thread {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        fun computePdfAvailablePageWidth(
            viewportWidth: Double,
            scrollPaneWidth: Double = 0.0,
            containerWidth: Double = 0.0
        ): Double {
            val effectiveViewportWidth = when {
                viewportWidth > 0.0 -> viewportWidth
                scrollPaneWidth > 0.0 -> scrollPaneWidth - 16.0
                containerWidth > 0.0 -> containerWidth - 16.0
                else -> 0.0
            }
            return (effectiveViewportWidth - 60.0).coerceAtLeast(100.0)
        }

        fun formatSingleRequestMarkdown(result: HttpResponseResult): String = buildString {
            val title = result.requestSpec.displayTitle
            val effectiveUrl = result.finalUrl.ifBlank { result.resolvedUrl.ifBlank { result.requestSpec.rawUrl } }
            val statusSummary = if (result.errorMessage != null) {
                "ERROR (${result.errorMessage})"
            } else {
                "${result.protocol} ${result.statusCode} ${result.statusMessage}".trim()
            }

            appendLine("# HTTP Request Summary: $title")
            appendLine()
            appendLine("## Overview")
            appendLine()
            appendLine("| Property | Value |")
            appendLine("| :--- | :--- |")
            appendLine("| **Request Name** | ${escapeMdTable(result.requestSpec.name.ifBlank { "—" })} |")
            appendLine("| **Method** | `${result.requestSpec.method.name}` |")
            appendLine("| **URL** | `${escapeMdTable(effectiveUrl)}` |")
            if (result.resolvedUrl.isNotBlank() && result.resolvedUrl != effectiveUrl) {
                appendLine("| **Initial URL** | `${escapeMdTable(result.resolvedUrl)}` |")
            }
            appendLine("| **Status** | `${escapeMdTable(statusSummary)}` |")
            appendLine("| **Protocol** | `${escapeMdTable(result.protocol)}` |")
            appendLine("| **Content-Type** | `${escapeMdTable(result.contentType.ifBlank { "—" })}` (${result.category.name}) |")
            appendLine("| **Payload Size** | ${result.bodySizeFormatted} (${result.bodyBytes.size} bytes) |")
            appendLine("| **Redirects** | ${result.redirectCount} |")
            if (!result.tlsCipherSuite.isNullOrBlank()) {
                appendLine("| **TLS Cipher Suite** | `${escapeMdTable(result.tlsCipherSuite)}` |")
            }
            if (result.savedFile != null) {
                appendLine("| **Saved File** | `${escapeMdTable(result.savedFile.absolutePath)}` |")
            }
            if (result.errorMessage != null) {
                appendLine("| **Error** | ${escapeMdTable(result.errorMessage)} |")
            }
            appendLine()

            val t = result.timings
            val totalMs = t.totalDurationMs.coerceAtLeast(0.0001)
            fun sharePct(ms: Double): String = String.format("%.1f%%", (ms / totalMs) * 100.0)

            appendLine("## Timing Breakdown")
            appendLine()
            appendLine("| Lifecycle Phase | Duration | Share |")
            appendLine("| :--- | ---: | ---: |")
            appendLine("| 1. DNS Lookup | ${String.format("%.2f ms", t.dnsDurationMs)} | ${sharePct(t.dnsDurationMs)} |")
            appendLine("| 2. TCP Connection | ${String.format("%.2f ms", t.tcpConnectDurationMs)} | ${sharePct(t.tcpConnectDurationMs)} |")
            appendLine("| 3. TLS Handshake | ${String.format("%.2f ms", t.tlsHandshakeDurationMs)} | ${sharePct(t.tlsHandshakeDurationMs)} |")
            appendLine("| 4. Request Dispatch | ${String.format("%.2f ms", t.requestSentDurationMs)} | ${sharePct(t.requestSentDurationMs)} |")
            appendLine("| 5. Server Accept & Processing (Wait) | ${String.format("%.2f ms", t.serverAcceptAndWaitDurationMs)} | ${sharePct(t.serverAcceptAndWaitDurationMs)} |")
            appendLine("| 6. Response Body Transfer | ${String.format("%.2f ms", t.responseTransferDurationMs)} | ${sharePct(t.responseTransferDurationMs)} |")
            appendLine("| **Until Server Accepts (TTFB)** | **${String.format("%.2f ms", t.timeUntilServerAcceptsMs)}** | **${sharePct(t.timeUntilServerAcceptsMs)}** |")
            appendLine("| **Total Roundtrip Duration** | **${String.format("%.2f ms", t.totalDurationMs)}** | **100.0%** |")
            appendLine()

            appendLine("## Executed Request")
            appendLine()
            appendLine("```http")
            appendLine(result.formatWireRequestText().replace("\r\n", "\n").replace("\r", "\n").trimEnd())
            appendLine("```")
            appendLine()

            appendLine("## Response Headers (${result.headers.values.sumOf { it.size }})")
            appendLine()
            if (result.headers.isEmpty()) {
                appendLine("_No response headers._")
            } else {
                appendLine("| Header Name | Header Value |")
                appendLine("| :--- | :--- |")
                for ((k, values) in result.headers) {
                    for (v in values) {
                        appendLine("| `${escapeMdTable(k)}` | `${escapeMdTable(v)}` |")
                    }
                }
            }
            appendLine()

            if (result.scriptOutput.isNotEmpty()) {
                appendLine("## Response Script Output")
                appendLine()
                for (line in result.scriptOutput) {
                    appendLine("- ${escapeMdTable(line)}")
                }
                appendLine()
            }

            appendLine("## Response Output (${result.category.name})")
            appendLine()
            when (result.category) {
                ResponseContentCategory.JSON -> {
                    val rawStr = String(result.bodyBytes, StandardCharsets.UTF_8)
                    val formatted = prettyPrintJsonStatic(rawStr)
                    appendLine("```json")
                    appendLine(formatted.trimEnd())
                    appendLine("```")
                }
                ResponseContentCategory.XML -> {
                    appendLine("```xml")
                    appendLine(String(result.bodyBytes, StandardCharsets.UTF_8).trimEnd())
                    appendLine("```")
                }
                ResponseContentCategory.HTML -> {
                    appendLine("```html")
                    appendLine(String(result.bodyBytes, StandardCharsets.UTF_8).trimEnd())
                    appendLine("```")
                }
                ResponseContentCategory.TEXT -> {
                    appendLine("```text")
                    appendLine(String(result.bodyBytes, StandardCharsets.UTF_8).trimEnd())
                    appendLine("```")
                }
                ResponseContentCategory.IMAGE,
                ResponseContentCategory.PDF,
                ResponseContentCategory.BINARY -> {
                    appendLine("- **Category**: ${result.category.name}")
                    appendLine("- **Content-Type**: `${result.contentType}`")
                    appendLine("- **Size**: ${result.bodySizeFormatted} (${result.bodyBytes.size} bytes)")
                    if (result.savedFile != null) {
                        appendLine("- **Saved File**: `${result.savedFile.absolutePath}`")
                    }
                    if (result.bodyBytes.isNotEmpty()) {
                        appendLine()
                        appendLine("### Hex Preview")
                        appendLine()
                        appendLine("```text")
                        appendLine(formatHexDumpStatic(result.bodyBytes, maxBytes = 512).trimEnd())
                        appendLine("```")
                    }
                }
            }
            appendLine()

            appendLine("## Full Wire Response")
            appendLine()
            appendLine("```http")
            appendLine(result.formatWireResponseText().replace("\r\n", "\n").replace("\r", "\n").trimEnd())
            appendLine("```")
        }

        fun formatLoadTestMarkdown(
            stats: LoadTestStatistics,
            selectedSample: LoadSampleResult? = stats.samples.firstOrNull()
        ): String = buildString {
            val title = stats.requestSpec.displayTitle
            val endpointUrl = stats.resolvedUrl.ifBlank { stats.requestSpec.rawUrl }
            val runState = when {
                stats.isCancelled -> "CANCELLED"
                stats.isFinished -> "COMPLETED"
                else -> "RUNNING"
            }
            val completionPct = if (stats.totalPlanned > 0) (stats.completedCount * 100.0 / stats.totalPlanned) else 0.0
            val successRate = if (stats.completedCount > 0) (stats.successCount * 100.0 / stats.completedCount) else 0.0
            val failureRate = if (stats.completedCount > 0) (stats.failureCount * 100.0 / stats.completedCount) else 0.0

            appendLine("# Multi-Request / Load Test Summary: $title")
            appendLine()
            appendLine("## Overview & Configuration")
            appendLine()
            appendLine("| Property | Value |")
            appendLine("| :--- | :--- |")
            appendLine("| **Request Name** | ${escapeMdTable(stats.requestSpec.name.ifBlank { "—" })} |")
            appendLine("| **Endpoint** | `${stats.requestSpec.method.name} ${escapeMdTable(endpointUrl)}` |")
            appendLine("| **Status** | `$runState` (${stats.successCount}/${stats.completedCount} OK) |")
            appendLine("| **Completed / Planned** | ${stats.completedCount} / ${stats.totalPlanned} (${String.format("%.1f%%", completionPct)}) |")
            appendLine("| **Success Rate** | ${String.format("%.1f%%", successRate)} (${stats.successCount} succeeded) |")
            appendLine("| **Failure Rate** | ${String.format("%.1f%%", failureRate)} (${stats.failureCount} failed) |")
            appendLine("| **Parallelism (Threads)** | ${stats.config.parallelism} worker thread(s) |")
            appendLine("| **Ramp-Up Duration** | ${stats.config.rampUpSeconds} s |")
            appendLine("| **Pacing Delay per Thread** | ${stats.config.delayBetweenRequestsMs} ms |")
            appendLine("| **Connection Pool Reuse** | ${if (stats.config.reuseConnectionPool) "Enabled (Keep-Alive)" else "Disabled"} |")
            appendLine("| **Elapsed Duration** | ${String.format("%.2f s (%.1f ms)", stats.elapsedDurationMs / 1000.0, stats.elapsedDurationMs)} |")
            appendLine("| **Throughput (RPS)** | **${String.format("%.2f req/s", stats.requestsPerSecond)}** |")
            appendLine("| **Bandwidth** | ${formatBytesStatic(stats.throughputBytesPerSecond.toLong())}/s (${formatBytesStatic(stats.totalBytesReceived)} total) |")
            appendLine()

            appendLine("## Statistical Distribution by HTTP Lifecycle Phase")
            appendLine()
            appendLine("| Timing Metric / Phase | Min (ms) | Mean (ms) | StdDev σ (ms) | P50 / Median (ms) | P75 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |")
            appendLine("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")

            val phases = listOf(
                "Total Roundtrip Duration" to stats.totalDurationStats,
                "Until Server Accepts (TTFB)" to stats.ttfbStats,
                "Server Accept & Processing Wait" to stats.serverWaitStats,
                "Response Body Transfer" to stats.transferStats,
                "Connection & TLS Handshake" to stats.connectStats
            )
            for ((phaseName, m) in phases) {
                appendLine(
                    String.format(
                        "| **%s** | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |",
                        phaseName,
                        m.minMs,
                        m.meanMs,
                        m.stdDevMs,
                        m.p50Ms,
                        m.p75Ms,
                        m.p90Ms,
                        m.p95Ms,
                        m.p99Ms,
                        m.maxMs
                    )
                )
            }
            appendLine()

            appendLine("## HTTP Status & Error Breakdown")
            appendLine()
            if (stats.statusCodeCounts.isEmpty()) {
                appendLine("_No status codes recorded._")
            } else {
                appendLine("| Status | Requests | Share |")
                appendLine("| :--- | ---: | ---: |")
                for ((code, count) in stats.statusCodeCounts) {
                    val pct = if (stats.completedCount > 0) (count * 100.0 / stats.completedCount) else 0.0
                    val codeLabel = if (code == 0) "Network / Timeout Error" else "HTTP $code"
                    appendLine(String.format("| `%s` | %d | %.1f%% |", codeLabel, count, pct))
                }
            }
            if (stats.errorCounts.isNotEmpty()) {
                appendLine()
                appendLine("### Top Errors")
                appendLine()
                for ((err, count) in stats.errorCounts) {
                    appendLine("- **${count}×** `${escapeMdTable(err)}`")
                }
            }
            appendLine()

            if (stats.histogram.isNotEmpty()) {
                appendLine("## Response Time Distribution Histogram")
                appendLine()
                appendLine("| Latency Bucket | Requests | Share |")
                appendLine("| :--- | ---: | ---: |")
                for (bucket in stats.histogram) {
                    appendLine(
                        String.format(
                            "| `%s` | %d | %.1f%% |",
                            escapeMdTable(bucket.rangeLabel),
                            bucket.count,
                            bucket.fraction * 100.0
                        )
                    )
                }
                appendLine()
            }

            val orderedSamples = stats.samples.sortedBy { it.requestIndex }
            appendLine("## Individual Request Samples (${orderedSamples.size})")
            appendLine()
            if (orderedSamples.isEmpty()) {
                appendLine("_No individual request samples recorded._")
            } else {
                appendLine("| # | Thread | Status | Started At | TTFB (ms) | Server Wait (ms) | Transfer (ms) | Total Duration (ms) | Size |")
                appendLine("| ---: | :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: |")
                for (s in orderedSamples) {
                    val statusText = if (s.statusCode > 0) "HTTP ${s.statusCode}" else "ERR"
                    appendLine(
                        String.format(
                            "| %d | T-%d | `%s` | +%.0f ms | %.2f | %.2f | %.2f | **%.2f** | %d B |",
                            s.requestIndex,
                            s.threadId,
                            statusText,
                            s.startOffsetMs,
                            s.timings.timeUntilServerAcceptsMs,
                            s.timings.serverAcceptAndWaitDurationMs,
                            s.timings.responseTransferDurationMs,
                            s.timings.totalDurationMs,
                            s.payloadBytes
                        )
                    )
                }
            }

            val representativeSample = selectedSample ?: orderedSamples.firstOrNull()
            val sampleResponse = representativeSample?.responseResult
            if (representativeSample != null && sampleResponse != null) {
                appendLine()
                appendLine("## Sample #${representativeSample.requestIndex} Request & Response Details (Thread T-${representativeSample.threadId})")
                appendLine()
                appendLine("### Executed Request")
                appendLine()
                appendLine("```http")
                appendLine(sampleResponse.formatWireRequestText().replace("\r\n", "\n").replace("\r", "\n").trimEnd())
                appendLine("```")
                appendLine()
                appendLine("### Full Response")
                appendLine()
                appendLine("```http")
                appendLine(sampleResponse.formatWireResponseText().replace("\r\n", "\n").replace("\r", "\n").trimEnd())
                appendLine("```")
            }
        }

        private fun escapeMdTable(text: String): String {
            return text.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ")
        }

        private fun formatBytesStatic(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            }
        }

        internal fun prettyPrintJsonStatic(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return raw

            val sb = StringBuilder(trimmed.length + 64)
            var indent = 0
            var inString = false
            var escaped = false
            var i = 0

            fun appendIndent() {
                repeat(indent) { sb.append("  ") }
            }
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (inString) {
                    sb.append(c)
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                } else {
                    when (c) {
                        '"' -> {
                            inString = true
                            sb.append(c)
                        }
                        '{', '[' -> {
                            sb.append(c)
                            var next = i + 1
                            while (next < trimmed.length && trimmed[next].isWhitespace()) next++
                            val expectedClose = if (c == '{') '}' else ']'
                            if (next < trimmed.length && trimmed[next] == expectedClose) {
                                sb.append(' ').append(expectedClose)
                                i = next
                            } else {
                                indent++
                                sb.append('\n')
                                appendIndent()
                            }
                        }
                        '}', ']' -> {
                            indent = (indent - 1).coerceAtLeast(0)
                            sb.append('\n')
                            appendIndent()
                            sb.append(c)
                        }
                        ',' -> {
                            sb.append(",\n")
                            appendIndent()
                        }
                        ':' -> sb.append(" : ")
                        ' ', '\t', '\r', '\n' -> {}
                        else -> sb.append(c)
                    }
                }
                i++
            }
            return if (inString || indent != 0) raw else sb.toString()
        }

        private fun formatHexDumpStatic(bytes: ByteArray, maxBytes: Int = 16384): String {
            val sb = StringBuilder()
            val limit = minOf(bytes.size, maxBytes)
            sb.append(" Payload Size: ${bytes.size} bytes")
            if (bytes.size > maxBytes) {
                sb.append(" (showing first $maxBytes bytes)")
            }
            sb.append("\n\n")

            var offset = 0
            while (offset < limit) {
                sb.append(String.format("%08X  ", offset))
                val ascii = StringBuilder()
                for (i in 0 until 16) {
                    if (offset + i < limit) {
                        val b = bytes[offset + i].toInt() and 0xFF
                        sb.append(String.format("%02X ", b))
                        ascii.append(if (b in 32..126) b.toChar() else '.')
                    } else {
                        sb.append("   ")
                    }
                    if (i == 7) sb.append(" ")
                }
                sb.append(" |").append(ascii).append("|\n")
                offset += 16
            }
            return sb.toString()
        }
    }
}
