package ch.schmidolin.executed.ui

import ch.schmidolin.executed.http.HttpExecutor
import ch.schmidolin.executed.http.LoadTestRunner
import ch.schmidolin.executed.model.*
import ch.schmidolin.executed.parser.HttpFileParser
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.*
import javafx.scene.shape.SVGPath
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.util.Callback
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import java.io.File
import java.util.function.IntFunction

class MainView(
    private val primaryStage: Stage,
    private val settingsStore: AppSettingsStore = AppSettingsStore()
) : BorderPane() {

    private val httpCodeArea = CodeArea()
    private val executor = HttpExecutor()
    private val responseViewer = ResponseViewerPane(outputDirectoryProvider = { executor.outputDirectory })
    private var activeLoadTestRunner: LoadTestRunner? = null
    private var lastLoadTestConfig = LoadTestConfig()

    private val fileTreeView = TreeView<FileTreeNodeData>()
    private val rootDirPathField = TextField()
    private val requestListItems = FXCollections.observableArrayList<HttpRequestSpec>()
    private val requestListView = ListView(requestListItems)
    private val variablesLabel = Label("Variables: 0")
    private val currentFileLabel = Label("sample-requests.http")
    private val outputDirLabel = Label("Output: ${executor.outputDirectory.absolutePath}")
    private val treeScanSpinner = ProgressIndicator().apply {
        prefWidth = 14.0
        prefHeight = 14.0
        maxWidth = 14.0
        maxHeight = 14.0
    }
    private val treeScanStatusLabel = Label("Scanning files...").apply {
        style = "-fx-text-fill: #38bdf8; -fx-font-size: 11px;"
    }
    private val treeScanProgressBox = HBox(6.0, treeScanSpinner, treeScanStatusLabel).apply {
        alignment = Pos.CENTER_LEFT
        padding = Insets(2.0, 10.0, 4.0, 10.0)
        isVisible = false
        isManaged = false
    }

    private var rootDirectory: File = resolveInitialRootDirectory()
    private var currentFile: File? = null
    private var isFirstLaunch: Boolean = false
    private var activeScanGeneration: Long = 0L
    private var activeScanTasksCount: Int = 0
    private var lastNormalWindowX: Double? = null
    private var lastNormalWindowY: Double? = null
    private var lastNormalWindowWidth: Double? = null
    private var lastNormalWindowHeight: Double? = null
    private var isWindowMaximized: Boolean = false
    private var isDirty: Boolean = false
    private var suppressDirtyTracking: Boolean = false
    private var suppressTreeSelectionEvent: Boolean = false
    private var currentDocument: HttpDocument = HttpDocument(emptyMap(), emptyList())
    private var requestsByLine: Map<Int, HttpRequestSpec> = emptyMap()

    init {
        val loadedSettings = settingsStore.load(
            defaultOutputDir = executor.outputDirectory,
            defaultWorkspaceDir = rootDirectory
        )
        executor.outputDirectory = loadedSettings.outputDirectory
        outputDirLabel.text = "Output: ${executor.outputDirectory.absolutePath}"
        rootDirectory = loadedSettings.lastWorkspaceDirectory ?: rootDirectory
        currentFile = loadedSettings.lastSelectedFile
        isFirstLaunch = loadedSettings.isFirstLaunch
        lastNormalWindowX = loadedSettings.windowX
        lastNormalWindowY = loadedSettings.windowY
        lastNormalWindowWidth = loadedSettings.windowWidth
        lastNormalWindowHeight = loadedSettings.windowHeight
        isWindowMaximized = loadedSettings.windowMaximized

        setupWindowStateTracking()

        top = buildTopToolbar()
        center = buildMainSplitContent()
        bottom = buildStatusBar()

        setupCodeArea()
        setupKeyboardShortcuts()
        sceneProperty().addListener { _, _, newScene ->
            if (newScene != null) {
                Platform.runLater { applySyntaxHighlightingToCodeArea() }
            }
        }
        if (isFirstLaunch) {
            rootDirPathField.text = rootDirectory.absolutePath
            loadDefaultSample()
        } else {
            loadWorkspaceDirectory(rootDirectory, autoSelectFirstHttp = true)
        }
    }

    fun onApplicationShown() {
        if (isFirstLaunch) {
            isFirstLaunch = false
            Platform.runLater {
                promptInitialWorkspaceDirectory()
            }
        }
    }

    private fun promptInitialWorkspaceDirectory() {
        val chooser = DirectoryChooser().apply {
            title = "Open Directory Containing HTTP Files"
            if (rootDirectory.exists() && rootDirectory.isDirectory) {
                initialDirectory = rootDirectory
            }
        }
        val selected = chooser.showDialog(primaryStage)
        val dirToLoad = selected ?: rootDirectory
        loadWorkspaceDirectory(dirToLoad, autoSelectFirstHttp = true)
    }

    private fun resolveInitialRootDirectory(): File {
        return AppSettingsStore.resolveDefaultWorkspaceDirectory()
    }

    private fun buildTopToolbar(): Node {
        val settingsItem = MenuItem("Settings...").apply {
            setOnAction { openSettingsDialog() }
        }
        val fileMenu = Menu("File", null, settingsItem)
        val menuBar = MenuBar(fileMenu).apply {
            styleClass.add("app-menu-bar")
        }
        return menuBar
    }

    private fun buildMainSplitContent(): SplitPane {
        // 1. Left Sidebar Top: Workspace Root Directory Selector & Expandable File Tree
        fileTreeView.styleClass.add("sidebar-tree")
        fileTreeView.isShowRoot = true
        fileTreeView.cellFactory = WorkspaceFileTreeHelper.createTreeCellFactory(
            onCreateHttpFileInFolder = { folder -> createNewHttpFileInWorkspace(folder) },
            onDeleteFile = { file -> deleteFileFromWorkspace(file) },
            onScanDirectoryForHttpFiles = { dirItem -> scanEntireDirectoryNodeInBackground(dirItem) }
        )

        val deleteFileBtn = Button("🗑").apply {
            styleClass.add("action-button")
            style = "-fx-padding: 2 6 2 6; -fx-font-size: 11px;"
            tooltip = Tooltip("Delete Selected File (Del)")
            isDisable = true
            setOnAction {
                val nodeData = fileTreeView.selectionModel.selectedItem?.value
                if (nodeData != null && !nodeData.isDirectory) {
                    deleteFileFromWorkspace(nodeData.file)
                }
            }
        }

        fileTreeView.selectionModel.selectedItemProperty().addListener { _, _, selectedItem ->
            val nodeData = selectedItem?.value
            deleteFileBtn.isDisable = nodeData == null || nodeData.isDirectory
            if (suppressTreeSelectionEvent || nodeData == null) return@addListener
            if (!nodeData.isDirectory && nodeData.file.exists() && nodeData.file.isFile) {
                openFileInEditor(nodeData.file)
            }
        }

        fileTreeView.setOnKeyPressed { event ->
            if (event.code == KeyCode.DELETE) {
                val nodeData = fileTreeView.selectionModel.selectedItem?.value
                if (nodeData != null && !nodeData.isDirectory) {
                    deleteFileFromWorkspace(nodeData.file)
                    event.consume()
                }
            }
        }

        rootDirPathField.promptText = "Enter or browse root directory path..."
        rootDirPathField.style = "-fx-font-family: monospace; -fx-font-size: 11.5px;"
        HBox.setHgrow(rootDirPathField, Priority.ALWAYS)
        rootDirPathField.setOnAction {
            val candidate = File(rootDirPathField.text.trim())
            if (candidate.exists() && candidate.isDirectory) {
                loadWorkspaceDirectory(candidate, autoSelectFirstHttp = true)
            }
        }

        val browseRootBtn = Button("Browse...").apply {
            styleClass.add("action-button")
            style = "-fx-padding: 4 8 4 8; -fx-font-size: 11px;"
            tooltip = Tooltip("Choose Root / Base Directory")
            setOnAction { chooseRootDirectory() }
        }

        val refreshTreeBtn = Button("⟳").apply {
            styleClass.add("action-button")
            style = "-fx-padding: 4 8 4 8; -fx-font-size: 11px;"
            tooltip = Tooltip("Refresh File Tree")
            setOnAction { refreshWorkspaceFileTree() }
        }

        val expandAllBtn = Button("⊞").apply {
            styleClass.add("action-button")
            style = "-fx-padding: 2 6 2 6; -fx-font-size: 11px;"
            tooltip = Tooltip("Expand All Folders")
            setOnAction { fileTreeView.root?.let { setTreeExpansionRecursive(it, true) } }
        }

        val collapseAllBtn = Button("⊟").apply {
            styleClass.add("action-button")
            style = "-fx-padding: 2 6 2 6; -fx-font-size: 11px;"
            tooltip = Tooltip("Collapse Folders")
            setOnAction {
                fileTreeView.root?.let { root ->
                    root.children.forEach { setTreeExpansionRecursive(it, false) }
                    root.isExpanded = true
                }
            }
        }

        val treeTitleRow = HBox(
            6.0,
            Label("WORKSPACE FILES").apply { styleClass.add("section-header") },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            deleteFileBtn,
            expandAllBtn,
            collapseAllBtn,
            refreshTreeBtn
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0, 8.0, 4.0, 10.0)
        }

        val rootPathRow = HBox(6.0, rootDirPathField, browseRootBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(2.0, 8.0, 6.0, 8.0)
        }

        VBox.setVgrow(fileTreeView, Priority.ALWAYS)
        val fileTreePane = VBox(0.0, treeTitleRow, rootPathRow, treeScanProgressBox, fileTreeView)

        // 2. Left Sidebar Bottom: Request outline list in current file
        requestListView.styleClass.add("sidebar-list")
        requestListView.cellFactory = Callback {
            object : ListCell<HttpRequestSpec>() {
                override fun updateItem(item: HttpRequestSpec?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                    } else {
                        val methodBadge = Label(item.method.name).apply {
                            minWidth = 54.0
                            alignment = Pos.CENTER
                            style = when (item.method) {
                                HttpMethod.GET -> "-fx-text-fill: #34d399; -fx-font-weight: bold; -fx-font-family: monospace;"
                                HttpMethod.POST -> "-fx-text-fill: #60a5fa; -fx-font-weight: bold; -fx-font-family: monospace;"
                                HttpMethod.PUT -> "-fx-text-fill: #fbbf24; -fx-font-weight: bold; -fx-font-family: monospace;"
                                HttpMethod.DELETE -> "-fx-text-fill: #f87171; -fx-font-weight: bold; -fx-font-family: monospace;"
                                else -> "-fx-text-fill: #c084fc; -fx-font-weight: bold; -fx-font-family: monospace;"
                            }
                        }
                        val titleLbl = Label(item.displayTitle).apply {
                            HBox.setHgrow(this, Priority.ALWAYS)
                            maxWidth = Double.MAX_VALUE
                        }
                        val quickRun = createRunGutterButton(
                            "Execute ${item.displayTitle}"
                        ) { executeRequest(item) }
                        val quickLoad = createLoadGutterButton(
                            "Run '${item.displayTitle}' multiple times with parallelism & live timing graph"
                        ) { openLoadTestDialog(item) }
                        val quickEdit = createEditGutterButton(
                            "Edit options, headers, URL params, auth, redirects & SSL"
                        ) { openRequestEditorDialog(item) }
                        val actionsBox = HBox(2.0, quickRun, quickLoad, quickEdit).apply {
                            alignment = Pos.CENTER_LEFT
                        }
                        graphic = HBox(4.0, actionsBox, methodBadge, titleLbl).apply {
                            alignment = Pos.CENTER_LEFT
                        }
                        setOnMouseClicked {
                            val currentItem = this.item
                            if (currentItem != null) {
                                focusRequestVerbInEditor(currentItem)
                            }
                        }
                    }
                }
            }
        }

        requestListView.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            if (selected != null) {
                focusRequestVerbInEditor(selected)
            }
        }

        val requestsHeader = HBox(8.0, Label("REQUESTS IN FILE").apply { styleClass.add("section-header") }).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0, 10.0, 6.0, 10.0)
        }
        VBox.setVgrow(requestListView, Priority.ALWAYS)
        val requestsOutlinePane = VBox(0.0, requestsHeader, requestListView)

        val leftSidebarSplit = SplitPane(fileTreePane, requestsOutlinePane).apply {
            orientation = Orientation.VERTICAL
            setDividerPositions(0.62)
            minWidth = 250.0
            prefWidth = 310.0
        }

        // Center: .http Editor with Gutter (Line number + ▶ Run + ⚡ Load Test + ✎ Edit buttons)
        val editorHeader = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0, 12.0, 6.0, 12.0)
            children.addAll(
                Label("").apply { styleClass.add("section-header") },
                currentFileLabel.apply { style = "-fx-text-fill: #38bdf8; -fx-font-weight: bold;" }
            )
        }

        val codeScroll = VirtualizedScrollPane(httpCodeArea)
        VBox.setVgrow(codeScroll, Priority.ALWAYS)
        val editorPane = VBox(0.0, editorHeader, codeScroll)

        return SplitPane(leftSidebarSplit, editorPane, responseViewer).apply {
            orientation = Orientation.HORIZONTAL
            setDividerPositions(0.22, 0.58)
        }
    }

    private fun buildStatusBar(): HBox {
        return HBox(16.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 14.0, 6.0, 14.0)
            style = "-fx-background-color: #12141c; -fx-border-color: #252a3d; -fx-border-width: 1 0 0 0;"
            children.addAll(
                variablesLabel,
                Separator(Orientation.VERTICAL),
                outputDirLabel,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                Label("Shortcuts: Ctrl+Enter to Execute  |  Ctrl+S to Save")
            )
        }
    }

    private fun setupCodeArea() {
        httpCodeArea.paragraphGraphicFactory = buildGutterFactory()

        httpCodeArea.plainTextChanges().subscribe {
            if (!suppressDirtyTracking && currentFile != null) {
                isDirty = true
                updateCurrentFileHeaderLabel()
            }
            Platform.runLater {
                reparseDocument(httpCodeArea.text ?: "")
            }
        }

        httpCodeArea.focusedProperty().addListener { _, wasFocused, isNowFocused ->
            if (wasFocused && !isNowFocused) {
                maybeAutoSaveDirtyFile()
            }
        }
    }

    private fun buildGutterFactory(): IntFunction<Node> {
        return IntFunction { lineIndex ->
            val lineNoLabel = Label(String.format("%3d", lineIndex + 1)).apply {
                styleClass.add("gutter-line-number")
                minWidth = 28.0
                alignment = Pos.CENTER_RIGHT
            }

            val reqAtLine = requestsByLine[lineIndex]
            val actionHolder = HBox(2.0).apply {
                alignment = Pos.CENTER_LEFT
                minWidth = 48.0
            }

            if (reqAtLine != null) {
                val runBtn = createRunGutterButton("Execute '${reqAtLine.displayTitle}'") {
                    executeRequest(reqAtLine)
                }
                val loadBtn = createLoadGutterButton(
                    "Execute '${reqAtLine.displayTitle}' multiple times (Load Test with parallelism & timing stats)"
                ) {
                    openLoadTestDialog(reqAtLine)
                }
                val editBtn = createEditGutterButton(
                    "Edit URL params, headers, auth, redirects & SSL for '${reqAtLine.displayTitle}'"
                ) {
                    openRequestEditorDialog(reqAtLine)
                }
                actionHolder.children.addAll(runBtn, loadBtn, editBtn)
            }

            HBox(5.0, lineNoLabel, actionHolder).apply {
                alignment = Pos.CENTER_LEFT
                styleClass.add("gutter-box")
            }
        }
    }

    private fun createRunGutterButton(tooltipText: String, onClick: () -> Unit): Button {
        return createGutterIconButton(
            styleClassName = "gutter-run-btn",
            svgContent = "M 0,0 L 8,5 L 0,10 Z",
            tooltipText = tooltipText,
            onClick = onClick
        )
    }

    private fun createLoadGutterButton(tooltipText: String, onClick: () -> Unit): Button {
        return createGutterIconButton(
            styleClassName = "gutter-load-btn",
            svgContent = "M 5,0 L 0.5,5.5 L 4,5.5 L 2.8,10 L 8.5,4.2 L 4.8,4.2 Z",
            tooltipText = tooltipText,
            onClick = onClick
        )
    }

    private fun createEditGutterButton(tooltipText: String, onClick: () -> Unit): Button {
        return createGutterIconButton(
            styleClassName = "gutter-edit-btn",
            svgContent = "M 7,0 L 10,3 L 3,10 L 0,10 L 0,7 Z",
            tooltipText = tooltipText,
            onClick = onClick
        )
    }

    private fun createGutterIconButton(
        styleClassName: String,
        svgContent: String,
        tooltipText: String,
        onClick: () -> Unit
    ): Button {
        val iconShape = SVGPath().apply {
            content = svgContent
            styleClass.add("gutter-icon")
        }
        return Button().apply {
            graphic = iconShape
            contentDisplay = ContentDisplay.GRAPHIC_ONLY
            isFocusTraversable = false
            styleClass.addAll("gutter-icon-btn", styleClassName)
            tooltip = Tooltip(tooltipText)
            setOnAction { onClick() }
        }
    }

    private fun reparseDocument(text: String) {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        currentDocument = HttpFileParser.parse(normalized)
        requestsByLine = currentDocument.requests.associateBy { it.requestLine }
        requestListItems.setAll(currentDocument.requests)

        val varSummary = if (currentDocument.variables.isEmpty()) {
            "Variables: none"
        } else {
            "Variables (${currentDocument.variables.size}): " +
                currentDocument.variables.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
        variablesLabel.text = varSummary

        applySyntaxHighlightingToCodeArea()

        // Refresh gutter graphics so ▶, ⚡, and ✎ buttons stay aligned with request lines
        httpCodeArea.paragraphGraphicFactory = buildGutterFactory()
    }

    private fun applySyntaxHighlightingToCodeArea() {
        val currentText = httpCodeArea.text ?: ""
        if (currentText.isEmpty()) return
        try {
            val spans = HttpSyntaxHighlighter.computeHttpHighlighting(currentText)
            if (spans.length() == httpCodeArea.length) {
                httpCodeArea.setStyleSpans(0, spans)
            }
        } catch (_: Exception) {
        }
    }

    private fun focusRequestVerbInEditor(spec: HttpRequestSpec) {
        if (httpCodeArea.paragraphs.isEmpty()) return
        val targetLine = spec.requestLine.coerceIn(0, (httpCodeArea.paragraphs.size - 1).coerceAtLeast(0))
        val lineLength = httpCodeArea.getParagraphLength(targetLine)
        httpCodeArea.selectRange(targetLine, 0, targetLine, lineLength)
        httpCodeArea.requestFollowCaret()
        httpCodeArea.requestFocus()
    }

    private fun findRequestAtCursor(): HttpRequestSpec? {
        val currentParagraph = httpCodeArea.currentParagraph
        return currentDocument.requests.find { currentParagraph in it.startLine..it.endLine }
            ?: currentDocument.requests.minByOrNull { kotlin.math.abs(it.requestLine - currentParagraph) }
    }

    private fun openRequestEditorDialog(spec: HttpRequestSpec) {
        val dialog = RequestEditorDialog(primaryStage, spec)
        val updatedOpt = dialog.showAndWait()
        if (updatedOpt.isPresent) {
            val updatedSpec = updatedOpt.get()
            val newText = HttpFileParser.replaceRequestBlockInText(httpCodeArea.text, updatedSpec)
            httpCodeArea.replaceText(newText)
            reparseDocument(httpCodeArea.text)
            val targetLine = updatedSpec.startLine.coerceIn(0, (httpCodeArea.paragraphs.size - 1).coerceAtLeast(0))
            httpCodeArea.moveTo(targetLine, 0)
            httpCodeArea.requestFollowCaret()
            Platform.runLater { applySyntaxHighlightingToCodeArea() }
        }
    }

    fun openLoadTestDialog(spec: HttpRequestSpec) {
        val dialog = LoadTestDialog(primaryStage, spec, lastLoadTestConfig)
        val configOpt = dialog.showAndWait()
        if (configOpt.isPresent) {
            val config = configOpt.get()
            lastLoadTestConfig = config
            executeLoadTest(spec, config)
        }
    }

    fun executeLoadTest(spec: HttpRequestSpec, config: LoadTestConfig) {
        maybeAutoSaveDirtyFile()
        activeLoadTestRunner?.cancel()

        val vars = currentDocument.variables
        val runner = LoadTestRunner(executor)
        activeLoadTestRunner = runner
        val previewUrl = executor.resolvePreviewUrl(spec, vars)

        responseViewer.startLoadTestView(
            spec = spec,
            config = config,
            resolvedUrl = previewUrl,
            onStopRequested = { runner.cancel() }
        )

        val task = object : Task<LoadTestStatistics>() {
            override fun call(): LoadTestStatistics {
                return runner.runLoadTest(
                    spec = spec,
                    documentVariables = vars,
                    config = config,
                    onProgress = { statsSnapshot, _ ->
                        Platform.runLater {
                            responseViewer.updateLoadTestProgress(statsSnapshot)
                        }
                    }
                )
            }
        }
        Thread(task, "executed-load-test-coordinator").apply { isDaemon = true }.start()
    }

    fun executeRequest(spec: HttpRequestSpec) {
        maybeAutoSaveDirtyFile()
        val vars = currentDocument.variables
        val previewUrl = HttpFileParser.resolveVariables(spec.rawUrl, vars)
        responseViewer.showLoading(spec.displayTitle, previewUrl)

        val task = object : Task<HttpResponseResult>() {
            override fun call(): HttpResponseResult {
                return executor.execute(spec, vars)
            }
        }
        task.setOnSucceeded {
            responseViewer.displayResult(task.value)
        }
        task.setOnFailed {
            val ex = task.exception
            val errResult = HttpResponseResult(
                requestSpec = spec,
                resolvedUrl = previewUrl,
                finalUrl = previewUrl,
                statusCode = 0,
                statusMessage = "Error",
                protocol = "HTTP",
                headers = emptyMap(),
                bodyBytes = (ex?.stackTraceToString() ?: "Unknown error").toByteArray(),
                contentType = "text/plain",
                category = ResponseContentCategory.TEXT,
                timings = RequestTimings(),
                errorMessage = ex?.message ?: "Request execution failed"
            )
            responseViewer.displayResult(errResult)
        }
        Thread(task, "executed-http-runner").apply { isDaemon = true }.start()
    }

    private fun beginScanProgress(statusText: String = "Scanning files...") {
        activeScanTasksCount++
        treeScanStatusLabel.text = statusText
        treeScanProgressBox.isVisible = true
        treeScanProgressBox.isManaged = true
    }

    private fun endScanProgress() {
        activeScanTasksCount = (activeScanTasksCount - 1).coerceAtLeast(0)
        if (activeScanTasksCount == 0) {
            treeScanProgressBox.isVisible = false
            treeScanProgressBox.isManaged = false
        }
    }

    private fun expandDirectoryNodeInBackground(dirItem: TreeItem<FileTreeNodeData>) {
        val currentData = dirItem.value ?: return
        if (!currentData.isDirectory || currentData.isScanned || currentData.isLoading) return

        val generation = activeScanGeneration
        dirItem.value = currentData.copy(isLoading = true)
        beginScanProgress("Scanning ${currentData.file.name}...")

        val task = object : Task<Pair<List<TreeItem<FileTreeNodeData>>, Int>>() {
            override fun call(): Pair<List<TreeItem<FileTreeNodeData>>, Int> {
                return WorkspaceFileTreeHelper.scanDirectorySubtree(
                    dirFile = currentData.file,
                    baseDepth = currentData.depth,
                    showHiddenFiles = false,
                    maxDepth = WorkspaceFileTreeHelper.DEFAULT_MAX_DEPTH,
                    onDirectoryExpandRequested = { childDirItem -> expandDirectoryNodeInBackground(childDirItem) },
                    isCancelled = { activeScanGeneration != generation }
                )
            }
        }
        task.setOnSucceeded {
            endScanProgress()
            if (activeScanGeneration == generation) {
                val (children, httpCount) = task.value
                WorkspaceFileTreeHelper.applyScannedSubtreeAndPropagateCounts(dirItem, children, httpCount)
            }
        }
        task.setOnFailed {
            endScanProgress()
            if (activeScanGeneration == generation) {
                dirItem.value = currentData.copy(isLoading = false)
            }
        }
        task.setOnCancelled {
            endScanProgress()
        }
        Thread(task, "executed-dir-expand-scanner").apply { isDaemon = true }.start()
    }

    private fun scanEntireDirectoryNodeInBackground(dirItem: TreeItem<FileTreeNodeData>) {
        val currentData = dirItem.value ?: return
        if (!currentData.isDirectory || currentData.isLoading) return

        val generation = activeScanGeneration
        dirItem.value = currentData.copy(isLoading = true)
        val dirName = currentData.file.name.ifEmpty { currentData.file.absolutePath }
        beginScanProgress("Scanning $dirName for HTTP files...")

        val task = object : Task<Pair<List<TreeItem<FileTreeNodeData>>, Int>>() {
            override fun call(): Pair<List<TreeItem<FileTreeNodeData>>, Int> {
                return WorkspaceFileTreeHelper.scanEntireDirectorySubtree(
                    dirFile = currentData.file,
                    baseDepth = currentData.depth,
                    showHiddenFiles = false,
                    onDirectoryExpandRequested = { childDirItem -> expandDirectoryNodeInBackground(childDirItem) },
                    isCancelled = { activeScanGeneration != generation }
                )
            }
        }
        task.setOnSucceeded {
            endScanProgress()
            if (activeScanGeneration == generation) {
                val (children, httpCount) = task.value
                WorkspaceFileTreeHelper.applyScannedSubtreeAndPropagateCounts(dirItem, children, httpCount)
                if (httpCount > 0 || dirItem.parent == null) {
                    dirItem.isExpanded = true
                }
            }
        }
        task.setOnFailed {
            endScanProgress()
            if (activeScanGeneration == generation) {
                dirItem.value = currentData.copy(isLoading = false)
            }
        }
        task.setOnCancelled {
            endScanProgress()
        }
        Thread(task, "executed-dir-full-scanner").apply { isDaemon = true }.start()
    }

    fun loadWorkspaceDirectory(
        dir: File,
        autoSelectFirstHttp: Boolean,
        preferredFileToSelect: File? = null
    ) {
        if (!dir.exists() || !dir.isDirectory) return
        // Auto-save current modified file before switching directory if applicable
        maybeAutoSaveDirtyFile()

        rootDirectory = dir.canonicalFile
        rootDirPathField.text = rootDirectory.absolutePath
        persistSettings()

        val generation = ++activeScanGeneration
        beginScanProgress("Scanning ${rootDirectory.name.ifEmpty { rootDirectory.absolutePath }}...")

        val task = object : Task<TreeItem<FileTreeNodeData>>() {
            override fun call(): TreeItem<FileTreeNodeData> {
                return WorkspaceFileTreeHelper.buildFileTree(
                    rootDir = rootDirectory,
                    showHiddenFiles = false,
                    maxDepth = WorkspaceFileTreeHelper.DEFAULT_MAX_DEPTH,
                    onDirectoryExpandRequested = { dirItem -> expandDirectoryNodeInBackground(dirItem) },
                    isCancelled = { activeScanGeneration != generation }
                )
            }
        }
        task.setOnSucceeded {
            endScanProgress()
            if (activeScanGeneration != generation) return@setOnSucceeded
            val rootTreeItem = task.value
            fileTreeView.root = rootTreeItem

            val fileToFind = preferredFileToSelect ?: currentFile
            if (autoSelectFirstHttp) {
                val targetItem = (fileToFind?.let { WorkspaceFileTreeHelper.findItemByFile(rootTreeItem, it) })
                    ?: WorkspaceFileTreeHelper.findFirstHttpFileItem(rootTreeItem)

                if (targetItem != null) {
                    selectAndOpenTreeItem(targetItem)
                } else if (fileToFind != null && fileToFind.exists() && fileToFind.isFile) {
                    openFileInEditor(fileToFind)
                } else {
                    loadDefaultSample()
                }
            } else if (fileToFind != null) {
                val item = WorkspaceFileTreeHelper.findItemByFile(rootTreeItem, fileToFind)
                if (item != null) {
                    suppressTreeSelectionEvent = true
                    try {
                        fileTreeView.selectionModel.select(item)
                    } finally {
                        suppressTreeSelectionEvent = false
                    }
                }
            }
            persistSettings()
        }
        task.setOnFailed {
            endScanProgress()
        }
        task.setOnCancelled {
            endScanProgress()
        }
        Thread(task, "executed-workspace-tree-scanner").apply { isDaemon = true }.start()
    }

    private fun refreshWorkspaceFileTree(preferredFileToOpen: File? = null) {
        if (preferredFileToOpen != null) {
            openFileInEditor(preferredFileToOpen)
        }
        loadWorkspaceDirectory(
            dir = rootDirectory,
            autoSelectFirstHttp = preferredFileToOpen != null,
            preferredFileToSelect = preferredFileToOpen ?: currentFile
        )
    }

    private fun selectAndOpenTreeItem(item: TreeItem<FileTreeNodeData>) {
        var parent = item.parent
        while (parent != null) {
            parent.isExpanded = true
            parent = parent.parent
        }
        fileTreeView.selectionModel.select(item)
        val rowIdx = fileTreeView.getRow(item)
        if (rowIdx >= 0) {
            fileTreeView.scrollTo(rowIdx)
        }
        val targetFile = item.value?.file
        if (targetFile != null && !item.value.isDirectory && targetFile.exists() && targetFile.isFile) {
            openFileInEditor(targetFile)
        }
    }

    private fun openFileInEditor(file: File) {
        if (currentFile?.absoluteFile == file.absoluteFile && !isDirty && httpCodeArea.text.isNotEmpty()) return
        maybeAutoSaveDirtyFile()

        try {
            val content = file.readText().replace("\r\n", "\n").replace("\r", "\n")
            suppressDirtyTracking = true
            try {
                currentFile = file
                isDirty = false
                executor.workingDirectory = file.parentFile ?: rootDirectory
                httpCodeArea.replaceText(content)
                reparseDocument(httpCodeArea.text)
                httpCodeArea.moveTo(0)
                updateCurrentFileHeaderLabel()
                persistSettings()
                Platform.runLater { applySyntaxHighlightingToCodeArea() }
            } finally {
                suppressDirtyTracking = false
            }
        } catch (e: Exception) {
            currentFileLabel.text = "${file.name} (Unable to read file: ${e.message})"
        }
    }

    private fun maybeAutoSaveDirtyFile() {
        val prev = currentFile
        if (isDirty && prev != null && prev.exists() && prev.canWrite()) {
            try {
                prev.writeText(httpCodeArea.text)
                isDirty = false
                updateCurrentFileHeaderLabel()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateCurrentFileHeaderLabel() {
        val file = currentFile
        if (file == null) {
            currentFileLabel.text = "sample-requests.http (unsaved sample)"
            return
        }
        val relPath = try {
            val rel = rootDirectory.toPath().relativize(file.toPath()).toString()
            if (rel.startsWith("..")) file.name else rel
        } catch (_: Exception) {
            file.name
        }
        currentFileLabel.text = if (isDirty) "$relPath *" else relPath
    }

    private fun chooseRootDirectory() {
        val chooser = DirectoryChooser().apply {
            title = "Select Workspace Root / Base Directory for HTTP Files"
            if (rootDirectory.exists() && rootDirectory.isDirectory) {
                initialDirectory = rootDirectory
            }
        }
        val selected = chooser.showDialog(primaryStage) ?: return
        loadWorkspaceDirectory(selected, autoSelectFirstHttp = true)
    }

    private fun createNewHttpFileInWorkspace(explicitTargetFolder: File? = null) {
        val targetFolder = if (explicitTargetFolder != null && explicitTargetFolder.isDirectory) {
            explicitTargetFolder
        } else {
            val selectedNode = fileTreeView.selectionModel.selectedItem?.value
            when {
                selectedNode == null -> rootDirectory
                selectedNode.isDirectory -> selectedNode.file
                else -> selectedNode.file.parentFile ?: rootDirectory
            }
        }

        val dialog = TextInputDialog("requests.http").apply {
            title = "New .http File"
            headerText = "Create a new HTTP Request file in:\n${targetFolder.absolutePath}"
            contentText = "File name:"
            initOwner(primaryStage)
            primaryStage.scene?.stylesheets?.let { dialogPane.stylesheets.addAll(it) }
            (dialogPane.scene?.window as? Stage)?.icons?.setAll(primaryStage.icons)
        }
        val nameOpt = dialog.showAndWait()
        if (!nameOpt.isPresent) return
        var fileName = nameOpt.get().trim()
        if (fileName.isEmpty()) return
        if (!fileName.endsWith(".http", ignoreCase = true) && !fileName.endsWith(".rest", ignoreCase = true)) {
            fileName += ".http"
        }

        val newFile = File(targetFolder, fileName)
        if (!newFile.exists()) {
            newFile.parentFile?.mkdirs()
            newFile.writeText(
                """
                # HTTP Requests — ${newFile.name}
                @baseUrl = https://httpbin.org

                ### Sample GET Request
                GET {{baseUrl}}/get
                Accept: application/json
                """.trimIndent() + "\n"
            )
        }
        refreshWorkspaceFileTree(preferredFileToOpen = newFile)
    }

    private fun deleteFileFromWorkspace(targetFile: File) {
        if (!targetFile.exists() || !targetFile.isFile) return

        val confirmAlert = Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "Delete File"
            headerText = "Delete '${targetFile.name}'?"
            contentText = "Are you sure you want to permanently delete:\n${targetFile.absolutePath}"
            initOwner(primaryStage)
            primaryStage.scene?.stylesheets?.let { dialogPane.stylesheets.addAll(it) }
            (dialogPane.scene?.window as? Stage)?.icons?.setAll(primaryStage.icons)
        }

        val result = confirmAlert.showAndWait()
        if (!result.isPresent || result.get() != ButtonType.OK) return

        val wasCurrentFile = currentFile?.canonicalFile == targetFile.canonicalFile
        if (!WorkspaceFileTreeHelper.deleteFile(targetFile)) {
            Alert(Alert.AlertType.ERROR).apply {
                title = "Delete Failed"
                headerText = "Could not delete '${targetFile.name}'"
                contentText = "Failed to delete file at:\n${targetFile.absolutePath}"
                initOwner(primaryStage)
                primaryStage.scene?.stylesheets?.let { dialogPane.stylesheets.addAll(it) }
                (dialogPane.scene?.window as? Stage)?.icons?.setAll(primaryStage.icons)
            }.showAndWait()
            return
        }

        if (wasCurrentFile) {
            currentFile = null
            isDirty = false
        }

        loadWorkspaceDirectory(
            dir = rootDirectory,
            autoSelectFirstHttp = wasCurrentFile,
            preferredFileToSelect = currentFile
        )
    }

    private fun saveHttpFile() {
        var target = currentFile
        if (target == null) {
            val chooser = FileChooser().apply {
                title = "Save .http Request File"
                initialFileName = currentFile?.name ?: "requests.http"
                val initDir = currentFile?.parentFile ?: rootDirectory
                if (initDir.exists() && initDir.isDirectory) {
                    initialDirectory = initDir
                }
                extensionFilters.add(FileChooser.ExtensionFilter("HTTP Request Files (*.http, *.rest)", "*.http", "*.rest"))
            }
            target = chooser.showSaveDialog(primaryStage) ?: return
            currentFile = target
            executor.workingDirectory = target.parentFile ?: rootDirectory
        }
        target.writeText(httpCodeArea.text)
        isDirty = false
        updateCurrentFileHeaderLabel()
        refreshWorkspaceFileTree()
        persistSettings()
    }

    fun openSettingsDialog() {
        val dialog = SettingsDialog(primaryStage, executor.outputDirectory)
        val selectedOpt = dialog.showAndWait()
        if (selectedOpt.isPresent) {
            setOutputDirectory(selectedOpt.get())
        }
    }

    fun setOutputDirectory(selected: File) {
        executor.outputDirectory = selected
        outputDirLabel.text = "Output: ${selected.absolutePath}"
        persistSettings()
    }

    private fun setupWindowStateTracking() {
        primaryStage.xProperty().addListener { _, _, newVal ->
            val v = newVal.toDouble()
            if (!primaryStage.isMaximized && !primaryStage.isIconified && v.isFinite()) {
                lastNormalWindowX = v
            }
        }
        primaryStage.yProperty().addListener { _, _, newVal ->
            val v = newVal.toDouble()
            if (!primaryStage.isMaximized && !primaryStage.isIconified && v.isFinite()) {
                lastNormalWindowY = v
            }
        }
        primaryStage.widthProperty().addListener { _, _, newVal ->
            val v = newVal.toDouble()
            if (!primaryStage.isMaximized && !primaryStage.isIconified && v.isFinite() && v > 0.0) {
                lastNormalWindowWidth = v
            }
        }
        primaryStage.heightProperty().addListener { _, _, newVal ->
            val v = newVal.toDouble()
            if (!primaryStage.isMaximized && !primaryStage.isIconified && v.isFinite() && v > 0.0) {
                lastNormalWindowHeight = v
            }
        }
        primaryStage.maximizedProperty().addListener { _, _, maximized ->
            isWindowMaximized = maximized
        }
    }

    fun restoreWindowState() {
        val w = lastNormalWindowWidth?.coerceAtLeast(primaryStage.minWidth.coerceAtLeast(100.0))
        val h = lastNormalWindowHeight?.coerceAtLeast(primaryStage.minHeight.coerceAtLeast(100.0))
        if (w != null && w > 0.0) {
            primaryStage.width = w
        }
        if (h != null && h > 0.0) {
            primaryStage.height = h
        }
        val x = lastNormalWindowX
        val y = lastNormalWindowY
        if (x != null && y != null && isPositionVisibleOnAnyScreen(x, y, w ?: 960.0, h ?: 620.0)) {
            primaryStage.x = x
            primaryStage.y = y
        }
        if (isWindowMaximized) {
            primaryStage.isMaximized = true
        }
    }

    private fun isPositionVisibleOnAnyScreen(x: Double, y: Double, width: Double, height: Double): Boolean {
        val screens = try {
            Screen.getScreensForRectangle(x, y, width.coerceAtLeast(100.0), height.coerceAtLeast(100.0))
        } catch (_: Exception) {
            emptyList()
        }
        return screens.isNotEmpty()
    }

    private fun captureCurrentWindowState() {
        if (primaryStage.isShowing) {
            isWindowMaximized = primaryStage.isMaximized
            if (!primaryStage.isMaximized && !primaryStage.isIconified) {
                if (primaryStage.x.isFinite()) lastNormalWindowX = primaryStage.x
                if (primaryStage.y.isFinite()) lastNormalWindowY = primaryStage.y
                if (primaryStage.width.isFinite() && primaryStage.width > 0.0) lastNormalWindowWidth = primaryStage.width
                if (primaryStage.height.isFinite() && primaryStage.height > 0.0) lastNormalWindowHeight = primaryStage.height
            }
        }
    }

    fun persistSettings() {
        captureCurrentWindowState()
        settingsStore.save(
            AppSettings(
                outputDirectory = executor.outputDirectory,
                lastWorkspaceDirectory = rootDirectory,
                lastSelectedFile = currentFile,
                windowX = lastNormalWindowX,
                windowY = lastNormalWindowY,
                windowWidth = lastNormalWindowWidth,
                windowHeight = lastNormalWindowHeight,
                windowMaximized = isWindowMaximized
            )
        )
    }

    fun shutdown() {
        maybeAutoSaveDirtyFile()
        persistSettings()
    }

    private fun setTreeExpansionRecursive(item: TreeItem<FileTreeNodeData>, expanded: Boolean) {
        val nodeData = item.value
        if (nodeData?.isDirectory == true) {
            if (!expanded || nodeData.isScanned) {
                item.isExpanded = expanded
            }
        }
        for (child in item.children) {
            setTreeExpansionRecursive(child, expanded)
        }
    }

    private fun setupKeyboardShortcuts() {
        val runCombo = KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN)
        val saveCombo = KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN)
        httpCodeArea.setOnKeyPressed { event ->
            when {
                runCombo.match(event) -> {
                    val spec = findRequestAtCursor() ?: currentDocument.requests.firstOrNull()
                    if (spec != null) executeRequest(spec)
                    event.consume()
                }
                saveCombo.match(event) -> {
                    saveHttpFile()
                    event.consume()
                }
            }
        }
    }

    private fun loadDefaultSample() {
        val sampleText = try {
            javaClass.getResourceAsStream("/samples/sample-requests.http")?.bufferedReader()?.readText()
        } catch (_: Exception) {
            null
        } ?: """
            ### Sample GET Request
            GET https://httpbin.org/get
            Accept: application/json
        """.trimIndent()

        suppressDirtyTracking = true
        try {
            currentFile = null
            isDirty = false
            val normalizedSample = sampleText.replace("\r\n", "\n").replace("\r", "\n")
            httpCodeArea.replaceText(normalizedSample)
            reparseDocument(httpCodeArea.text)
            httpCodeArea.moveTo(0)
            updateCurrentFileHeaderLabel()
            Platform.runLater { applySyntaxHighlightingToCodeArea() }
        } finally {
            suppressDirtyTracking = false
        }
    }
}

