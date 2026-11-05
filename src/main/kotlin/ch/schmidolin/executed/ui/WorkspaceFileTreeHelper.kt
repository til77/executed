package ch.schmidolin.executed.ui

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.shape.SVGPath
import java.io.File

data class FileTreeNodeData(
    val file: File,
    val isDirectory: Boolean,
    val isHttpFile: Boolean,
    val containsHttpFilesRecursively: Boolean,
    val httpFileCountRecursively: Int = 0,
    val depth: Int = 0,
    val isScanned: Boolean = true,
    val isLoading: Boolean = false
) {
    override fun toString(): String = file.name.ifEmpty { file.absolutePath }
}

object WorkspaceFileTreeHelper {

    const val DEFAULT_MAX_DEPTH = 6

    private val HTTP_EXTENSIONS = setOf("http", "rest")

    private val IGNORED_DIRS = setOf(
        ".git", ".gradle", ".idea", "build", "node_modules", "out", "target"
    )

    fun isHttpFile(file: File): Boolean {
        return file.isFile && file.extension.lowercase() in HTTP_EXTENSIONS
    }

    fun buildFileTree(
        rootDir: File,
        showHiddenFiles: Boolean = false,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onDirectoryExpandRequested: ((TreeItem<FileTreeNodeData>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): TreeItem<FileTreeNodeData> {
        return buildNodeRecursive(
            file = rootDir,
            depth = 0,
            showHiddenFiles = showHiddenFiles,
            maxDepth = maxDepth,
            forceScanSelf = true,
            onDirectoryExpandRequested = onDirectoryExpandRequested,
            isCancelled = isCancelled
        ).first
    }

    private fun buildPlaceholderChild(parentFile: File, depth: Int): TreeItem<FileTreeNodeData> {
        return TreeItem(
            FileTreeNodeData(
                file = File(parentFile, "..."),
                isDirectory = false,
                isHttpFile = false,
                containsHttpFilesRecursively = false,
                httpFileCountRecursively = 0,
                depth = depth,
                isScanned = false,
                isLoading = true
            )
        )
    }

    private fun attachExpansionListener(
        dirItem: TreeItem<FileTreeNodeData>,
        onDirectoryExpandRequested: ((TreeItem<FileTreeNodeData>) -> Unit)?
    ) {
        dirItem.expandedProperty().addListener { _, wasExpanded, isNowExpanded ->
            if (!wasExpanded && isNowExpanded) {
                val currentData = dirItem.value
                if (currentData != null && currentData.isDirectory && !currentData.isScanned && !currentData.isLoading) {
                    if (onDirectoryExpandRequested != null) {
                        onDirectoryExpandRequested(dirItem)
                    } else {
                        expandUnscannedNodeSync(
                            dirItem = dirItem,
                            showHiddenFiles = false,
                            maxDepth = DEFAULT_MAX_DEPTH,
                            onDirectoryExpandRequested = null
                        )
                    }
                }
            }
        }
    }

    private fun buildNodeRecursive(
        file: File,
        depth: Int,
        showHiddenFiles: Boolean,
        maxDepth: Int,
        forceScanSelf: Boolean,
        onDirectoryExpandRequested: ((TreeItem<FileTreeNodeData>) -> Unit)?,
        isCancelled: () -> Boolean
    ): Pair<TreeItem<FileTreeNodeData>, Int> {
        if (isCancelled()) {
            val cancelledItem = TreeItem(
                FileTreeNodeData(
                    file = file,
                    isDirectory = file.isDirectory,
                    isHttpFile = false,
                    containsHttpFilesRecursively = false,
                    httpFileCountRecursively = 0,
                    depth = depth,
                    isScanned = false
                )
            )
            return cancelledItem to 0
        }

        if (!file.isDirectory) {
            val isHttp = isHttpFile(file)
            val count = if (isHttp) 1 else 0
            val item = TreeItem(
                FileTreeNodeData(
                    file = file,
                    isDirectory = false,
                    isHttpFile = isHttp,
                    containsHttpFilesRecursively = isHttp,
                    httpFileCountRecursively = count,
                    depth = depth,
                    isScanned = true
                )
            )
            return item to count
        }

        val isRoot = depth == 0
        val isHiddenDir = !isRoot && file.name.startsWith(".")
        val shouldDeferScan = !forceScanSelf && ((!showHiddenFiles && isHiddenDir) || depth >= maxDepth)

        if (shouldDeferScan) {
            val unscannedData = FileTreeNodeData(
                file = file,
                isDirectory = true,
                isHttpFile = false,
                containsHttpFilesRecursively = false,
                httpFileCountRecursively = 0,
                depth = depth,
                isScanned = false
            )
            val dirItem = TreeItem(unscannedData).apply {
                children.add(buildPlaceholderChild(file, depth + 1))
                isExpanded = false
            }
            attachExpansionListener(dirItem, onDirectoryExpandRequested)
            return dirItem to 0
        }

        val childrenFiles = listSortedDirectoryChildren(file, showHiddenFiles)

        val childItems = mutableListOf<TreeItem<FileTreeNodeData>>()
        var totalHttpDescendantCount = 0

        for (child in childrenFiles) {
            if (isCancelled()) break
            val (childItem, childHttpCount) = buildNodeRecursive(
                file = child,
                depth = depth + 1,
                showHiddenFiles = showHiddenFiles,
                maxDepth = maxDepth,
                forceScanSelf = false,
                onDirectoryExpandRequested = onDirectoryExpandRequested,
                isCancelled = isCancelled
            )
            childItems.add(childItem)
            totalHttpDescendantCount += childHttpCount
        }

        val data = FileTreeNodeData(
            file = file,
            isDirectory = true,
            isHttpFile = false,
            containsHttpFilesRecursively = totalHttpDescendantCount > 0,
            httpFileCountRecursively = totalHttpDescendantCount,
            depth = depth,
            isScanned = true
        )

        val dirItem = TreeItem(data).apply {
            children.addAll(childItems)
            isExpanded = isRoot || totalHttpDescendantCount > 0
        }
        attachExpansionListener(dirItem, onDirectoryExpandRequested)

        return dirItem to totalHttpDescendantCount
    }

    private fun listSortedDirectoryChildren(dir: File, showHiddenFiles: Boolean): List<File> {
        return dir.listFiles()
            ?.filter { child ->
                if (!showHiddenFiles && child.name.startsWith(".") && !child.isDirectory) return@filter false
                if (child.isDirectory && child.name in IGNORED_DIRS && !child.name.startsWith(".")) return@filter false
                true
            }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?: emptyList()
    }

    /**
     * Scans a deferred directory node (e.g. a dot-directory or a directory past the initial depth limit)
     * down to [maxDepth] additional levels and returns the scanned child items and total HTTP file count.
     */
    fun scanDirectorySubtree(
        dirFile: File,
        baseDepth: Int,
        showHiddenFiles: Boolean = false,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onDirectoryExpandRequested: ((TreeItem<FileTreeNodeData>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Pair<List<TreeItem<FileTreeNodeData>>, Int> {
        val effectiveMaxDepth = baseDepth + maxDepth
        val childrenFiles = listSortedDirectoryChildren(dirFile, showHiddenFiles)
        val childItems = mutableListOf<TreeItem<FileTreeNodeData>>()
        var totalHttpDescendantCount = 0

        for (child in childrenFiles) {
            if (isCancelled()) break
            val (childItem, childHttpCount) = buildNodeRecursive(
                file = child,
                depth = baseDepth + 1,
                showHiddenFiles = showHiddenFiles,
                maxDepth = effectiveMaxDepth,
                forceScanSelf = false,
                onDirectoryExpandRequested = onDirectoryExpandRequested,
                isCancelled = isCancelled
            )
            childItems.add(childItem)
            totalHttpDescendantCount += childHttpCount
        }
        return childItems to totalHttpDescendantCount
    }

    /**
     * Populates a previously unscanned directory [dirItem] with its scanned [childItems] and
     * updates the HTTP file count in [dirItem] and all its ancestor directory nodes.
     */
    fun applyScannedSubtreeAndPropagateCounts(
        dirItem: TreeItem<FileTreeNodeData>,
        childItems: List<TreeItem<FileTreeNodeData>>,
        discoveredHttpCount: Int
    ) {
        val oldData = dirItem.value ?: return
        val delta = discoveredHttpCount - oldData.httpFileCountRecursively

        dirItem.children.setAll(childItems)
        dirItem.value = oldData.copy(
            containsHttpFilesRecursively = discoveredHttpCount > 0,
            httpFileCountRecursively = discoveredHttpCount,
            isScanned = true,
            isLoading = false
        )

        if (delta != 0) {
            var parent = dirItem.parent
            while (parent != null) {
                val parentData = parent.value
                if (parentData != null && parentData.isDirectory) {
                    val newCount = (parentData.httpFileCountRecursively + delta).coerceAtLeast(0)
                    parent.value = parentData.copy(
                        containsHttpFilesRecursively = newCount > 0,
                        httpFileCountRecursively = newCount
                    )
                }
                parent = parent.parent
            }
        }
    }

    /**
     * Scans the entire directory structure under [dirFile] without depth limits,
     * returning the scanned child items and total HTTP file count.
     */
    fun scanEntireDirectorySubtree(
        dirFile: File,
        baseDepth: Int,
        showHiddenFiles: Boolean = false,
        onDirectoryExpandRequested: ((TreeItem<FileTreeNodeData>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Pair<List<TreeItem<FileTreeNodeData>>, Int> {
        return scanDirectorySubtree(
            dirFile = dirFile,
            baseDepth = baseDepth,
            showHiddenFiles = showHiddenFiles,
            maxDepth = Int.MAX_VALUE - baseDepth - 1,
            onDirectoryExpandRequested = onDirectoryExpandRequested,
            isCancelled = isCancelled
        )
    }

    /**
     * Synchronously scans the entire directory subtree for [dirItem] without depth limits
     * and updates its children and ancestor HTTP file counts.
     */
    fun scanEntireNodeSync(
        dirItem: TreeItem<FileTreeNodeData>,
        showHiddenFiles: Boolean = false,
        onDirectoryExpandRequested: ((TreeItem<FileTreeNodeData>) -> Unit)? = null
    ) {
        val currentData = dirItem.value ?: return
        if (!currentData.isDirectory) return

        val (children, httpCount) = scanEntireDirectorySubtree(
            dirFile = currentData.file,
            baseDepth = currentData.depth,
            showHiddenFiles = showHiddenFiles,
            onDirectoryExpandRequested = onDirectoryExpandRequested
        )
        applyScannedSubtreeAndPropagateCounts(dirItem, children, httpCount)
        if (httpCount > 0 || dirItem.parent == null) {
            dirItem.isExpanded = true
        }
    }

    /**
     * Synchronously expands and scans an unscanned directory node, updating its children and parent counts.
     */
    fun expandUnscannedNodeSync(
        dirItem: TreeItem<FileTreeNodeData>,
        showHiddenFiles: Boolean = false,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onDirectoryExpandRequested: ((TreeItem<FileTreeNodeData>) -> Unit)? = null
    ) {
        val currentData = dirItem.value ?: return
        if (!currentData.isDirectory || currentData.isScanned) return

        val (children, httpCount) = scanDirectorySubtree(
            dirFile = currentData.file,
            baseDepth = currentData.depth,
            showHiddenFiles = showHiddenFiles,
            maxDepth = maxDepth,
            onDirectoryExpandRequested = onDirectoryExpandRequested
        )
        applyScannedSubtreeAndPropagateCounts(dirItem, children, httpCount)
        dirItem.isExpanded = true
    }

    fun findFirstHttpFileItem(root: TreeItem<FileTreeNodeData>): TreeItem<FileTreeNodeData>? {
        val data = root.value
        if (data != null && data.isHttpFile) return root
        for (child in root.children) {
            val found = findFirstHttpFileItem(child)
            if (found != null) return found
        }
        return null
    }

    fun findItemByFile(root: TreeItem<FileTreeNodeData>, target: File): TreeItem<FileTreeNodeData>? {
        val data = root.value
        if (data != null && data.file.absoluteFile == target.absoluteFile) return root
        for (child in root.children) {
            val found = findItemByFile(child, target)
            if (found != null) return found
        }
        return null
    }

    fun deleteFile(file: File): Boolean {
        return file.exists() && file.isFile && file.delete()
    }

    fun createTreeCellFactory(
        onCreateHttpFileInFolder: ((File) -> Unit)? = null,
        onDeleteFile: ((File) -> Unit)? = null,
        onScanDirectoryForHttpFiles: ((TreeItem<FileTreeNodeData>) -> Unit)? = null
    ): javafx.util.Callback<TreeView<FileTreeNodeData>, TreeCell<FileTreeNodeData>> {
        return javafx.util.Callback {
            object : TreeCell<FileTreeNodeData>() {
                override fun updateItem(item: FileTreeNodeData?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                        tooltip = null
                        contextMenu = null
                        styleClass.removeAll("tree-cell-http-file", "tree-cell-http-folder")
                        return
                    }

                    if (item.isLoading && !item.isDirectory) {
                        val spinner = ProgressIndicator().apply {
                            prefWidth = 14.0
                            prefHeight = 14.0
                            maxWidth = 14.0
                            maxHeight = 14.0
                        }
                        val loadingLabel = Label("Scanning...").apply {
                            style = "-fx-text-fill: #94a3b8; -fx-font-style: italic;"
                        }
                        graphic = HBox(7.0, spinner, loadingLabel).apply {
                            alignment = Pos.CENTER_LEFT
                        }
                        text = null
                        tooltip = null
                        contextMenu = null
                        return
                    }

                    val iconNode = buildIconForNode(item, treeItem?.isExpanded == true)
                    val nameLabel = Label(item.file.name.ifEmpty { item.file.absolutePath }).apply {
                        style = when {
                            item.isHttpFile -> "-fx-text-fill: #38bdf8; -fx-font-weight: bold;"
                            item.isDirectory && item.containsHttpFilesRecursively -> "-fx-text-fill: #f8fafc; -fx-font-weight: bold;"
                            item.isDirectory -> "-fx-text-fill: #94a3b8;"
                            else -> "-fx-text-fill: #64748b;"
                        }
                    }

                    val row = HBox(7.0, iconNode, nameLabel).apply {
                        alignment = Pos.CENTER_LEFT
                    }

                    if (item.isDirectory && item.containsHttpFilesRecursively) {
                        val badge = Label("${item.httpFileCountRecursively} .http").apply {
                            styleClass.add("tree-http-count-badge")
                        }
                        row.children.addAll(Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, badge)
                    } else if (item.isHttpFile) {
                        val badge = Label("HTTP").apply {
                            styleClass.add("tree-http-file-badge")
                        }
                        row.children.addAll(Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, badge)
                    }

                    val menuItems = mutableListOf<MenuItem>()
                    if (item.isDirectory) {
                        val currentTreeItem = treeItem
                        if (onScanDirectoryForHttpFiles != null && currentTreeItem != null) {
                            menuItems.add(
                                MenuItem("Scan Directory for HTTP Files").apply {
                                    isDisable = item.isLoading
                                    setOnAction { onScanDirectoryForHttpFiles(currentTreeItem) }
                                }
                            )
                        }
                        if (onCreateHttpFileInFolder != null) {
                            menuItems.add(
                                MenuItem("New .http File...").apply {
                                    setOnAction { onCreateHttpFileInFolder(item.file) }
                                }
                            )
                        }
                    } else if (!item.isDirectory && onCreateHttpFileInFolder != null) {
                        val parentDir = item.file.parentFile
                        if (parentDir != null && parentDir.isDirectory) {
                            menuItems.add(
                                MenuItem("New .http File...").apply {
                                    setOnAction { onCreateHttpFileInFolder(parentDir) }
                                }
                            )
                        }
                    }

                    if (!item.isDirectory && onDeleteFile != null) {
                        menuItems.add(
                            MenuItem("Delete File...").apply {
                                setOnAction { onDeleteFile(item.file) }
                            }
                        )
                    }

                    contextMenu = if (menuItems.isNotEmpty()) {
                        ContextMenu(*menuItems.toTypedArray())
                    } else {
                        null
                    }

                    text = null
                    graphic = row
                    tooltip = Tooltip(
                        buildString {
                            append(item.file.absolutePath)
                            if (item.isDirectory && item.containsHttpFilesRecursively) {
                                append("\nContains ${item.httpFileCountRecursively} HTTP request file(s)")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun buildIconForNode(node: FileTreeNodeData, isExpanded: Boolean): Node {
        return when {
            node.isDirectory && node.containsHttpFilesRecursively -> {
                // Special folder icon indicating it contains .http files recursively (Golden/Cyan folder with HTTP globe/lightning accent)
                val folderSvg = SVGPath().apply {
                    content = if (isExpanded) {
                        "M2 4 C2 2.9 2.9 2 4 2 L8 2 L10 4 L18 4 C19.1 4 20 4.9 20 6 L20 7 L4.5 7 L2.5 16 L2 16 Z M4.5 8.5 L21.5 8.5 L19.5 17 C19.3 17.6 18.7 18 18 18 L3 18 Z"
                    } else {
                        "M2 4 C2 2.9 2.9 2 4 2 L8 2 L10 4 L18 4 C19.1 4 20 4.9 20 6 L20 16 C20 17.1 19.1 18 18 18 L4 18 C2.9 18 2 17.1 2 16 Z"
                    }
                    fill = Color.web("#f59e0b")
                    scaleX = 0.82
                    scaleY = 0.82
                }
                val dotAccent = SVGPath().apply {
                    content = "M12 2 L7 9 L11 9 L9 15 L15 7 L11 7 Z"
                    fill = Color.web("#38bdf8")
                    scaleX = 0.65
                    scaleY = 0.65
                }
                HBox(1.0, folderSvg, dotAccent).apply {
                    alignment = Pos.CENTER_LEFT
                    minWidth = 28.0
                }
            }
            node.isDirectory -> {
                // Standard folder icon
                val folderSvg = SVGPath().apply {
                    content = "M2 4 C2 2.9 2.9 2 4 2 L8 2 L10 4 L18 4 C19.1 4 20 4.9 20 6 L20 16 C20 17.1 19.1 18 18 18 L4 18 C2.9 18 2 17.1 2 16 Z"
                    fill = Color.web("#64748b")
                    scaleX = 0.82
                    scaleY = 0.82
                }
                HBox(folderSvg).apply {
                    alignment = Pos.CENTER_LEFT
                    minWidth = 22.0
                }
            }
            node.isHttpFile -> {
                // Nice HTTP file icon: Globe/Network request badge
                val globeSvg = SVGPath().apply {
                    content = "M10 2 C5.58 2 2 5.58 2 10 C2 14.42 5.58 18 10 18 C14.42 18 18 14.42 18 10 C18 5.58 14.42 2 10 2 Z M15.9 8 L13.2 8 C12.9 6.3 12.3 4.8 11.4 3.7 C13.6 4.3 15.3 5.9 15.9 8 Z M10 3.6 C10.8 4.7 11.4 6.3 11.7 8 L8.3 8 C8.6 6.3 9.2 4.7 10 3.6 Z M3.8 11.5 C3.6 10.5 3.6 9.5 3.8 8.5 L6.7 8.5 C6.6 9.5 6.6 10.5 6.7 11.5 L3.8 11.5 Z M4.1 12 L6.8 12 C7.1 13.7 7.7 15.2 8.6 16.3 C6.4 15.7 4.7 14.1 4.1 12 Z M6.8 8 L4.1 8 C4.7 5.9 6.4 4.3 8.6 3.7 C7.7 4.8 7.1 6.3 6.8 8 Z M10 16.4 C9.2 15.3 8.6 13.7 8.3 12 L11.7 12 C11.4 13.7 10.8 15.3 10 16.4 Z M12.1 11.5 L7.9 11.5 C7.8 10.5 7.8 9.5 7.9 8.5 L12.1 8.5 C12.2 9.5 12.2 10.5 12.1 11.5 Z M11.4 16.3 C12.3 15.2 12.9 13.7 13.2 12 L15.9 12 C15.3 14.1 13.6 15.7 11.4 16.3 Z M13.3 11.5 C13.4 10.5 13.4 9.5 13.3 8.5 L16.2 8.5 C16.4 9.5 16.4 10.5 16.2 11.5 L13.3 11.5 Z"
                    fill = Color.web("#38bdf8")
                    scaleX = 0.88
                    scaleY = 0.88
                }
                HBox(globeSvg).apply {
                    alignment = Pos.CENTER_LEFT
                    minWidth = 22.0
                }
            }
            else -> {
                // Generic text/file document icon
                val docSvg = SVGPath().apply {
                    content = "M4 2 C3.45 2 3 2.45 3 3 L3 17 C3 17.55 3.45 18 4 18 L14 18 C14.55 18 15 17.55 15 17 L15 7 L10 2 L4 2 Z M9.5 3.5 L13.5 7.5 L9.5 7.5 L9.5 3.5 Z"
                    fill = Color.web("#64748b")
                    scaleX = 0.82
                    scaleY = 0.82
                }
                HBox(docSvg).apply {
                    alignment = Pos.CENTER_LEFT
                    minWidth = 22.0
                }
            }
        }
    }
}
