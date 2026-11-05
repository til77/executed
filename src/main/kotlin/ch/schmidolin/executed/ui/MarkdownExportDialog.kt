package ch.schmidolin.executed.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import javafx.stage.Window
import java.io.File

class MarkdownExportDialog(
    owner: Window?,
    titleSuffix: String,
    markdownSource: String,
    private val initialOutputDirectory: File? = null
) : Dialog<String>() {

    val markdownTextArea = TextArea(markdownSource).apply {
        isEditable = true
        isWrapText = false
        style = "-fx-font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', 'DejaVu Sans Mono', monospace; -fx-font-size: 13px;"
        VBox.setVgrow(this, Priority.ALWAYS)
    }

    private val statusLabel = Label(formatStatsText(markdownSource)).apply {
        style = "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;"
    }

    init {
        title = if (titleSuffix.isNotBlank()) {
            "Export as Markdown — $titleSuffix"
        } else {
            "Export as Markdown"
        }
        headerText = "Summarized Markdown source — review, copy to clipboard, or save as a .md file"
        initOwner(owner)
        isResizable = true

        owner?.scene?.stylesheets?.let { dialogPane.stylesheets.addAll(it) }
        (dialogPane.scene?.window as? Stage)?.let { stage ->
            (owner as? Stage)?.icons?.let { stage.icons.setAll(it) }
        }

        val wrapCheck = CheckBox("Wrap Lines").apply {
            isSelected = markdownTextArea.isWrapText
            selectedProperty().addListener { _, _, wrap ->
                markdownTextArea.isWrapText = wrap
            }
        }

        val copyBtn = Button("Copy Markdown").apply {
            styleClass.add("action-button")
            setOnAction {
                copyToClipboard(markdownTextArea.text)
                statusLabel.text = "Copied to clipboard (${formatStatsText(markdownTextArea.text)})"
            }
        }

        val saveFileBtn = Button("Save .md File...").apply {
            styleClass.add("action-button")
            setOnAction {
                saveMarkdownToFile(owner, titleSuffix)
            }
        }

        markdownTextArea.textProperty().addListener { _, _, newText ->
            statusLabel.text = formatStatsText(newText.orEmpty())
        }

        val toolbar = HBox(
            10.0,
            statusLabel,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            wrapCheck,
            copyBtn,
            saveFileBtn
        ).apply {
            alignment = Pos.CENTER_LEFT
        }

        val root = VBox(10.0, toolbar, markdownTextArea).apply {
            padding = Insets(14.0)
            prefWidth = 840.0
            prefHeight = 620.0
        }

        dialogPane.content = root

        val copyAndCloseType = ButtonType("Copy & Close", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(copyAndCloseType, ButtonType.CLOSE)

        val primaryBtn = dialogPane.lookupButton(copyAndCloseType) as? Button
        primaryBtn?.styleClass?.add("primary-button")

        setResultConverter { buttonType ->
            if (buttonType == copyAndCloseType) {
                val text = markdownTextArea.text
                copyToClipboard(text)
                text
            } else {
                null
            }
        }
    }

    private fun formatStatsText(text: String): String {
        val lineCount = if (text.isEmpty()) 0 else text.lines().size
        return "$lineCount lines  |  ${text.length} chars"
    }

    private fun copyToClipboard(text: String) {
        val content = ClipboardContent()
        content.putString(text)
        Clipboard.getSystemClipboard().setContent(content)
    }

    private fun saveMarkdownToFile(owner: Window?, titleSuffix: String) {
        val sanitizedName = titleSuffix
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "response-summary" }

        val chooser = FileChooser().apply {
            title = "Save Markdown Summary"
            initialFileName = "$sanitizedName.md"
            extensionFilters.addAll(
                FileChooser.ExtensionFilter("Markdown Files (*.md)", "*.md"),
                FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
            )
            val startDir = initialOutputDirectory?.takeIf { it.exists() && it.isDirectory }
                ?: File(System.getProperty("user.dir")).takeIf { it.exists() && it.isDirectory }
            if (startDir != null) {
                initialDirectory = startDir
            }
        }

        val targetFile = chooser.showSaveDialog(dialogPane.scene?.window ?: owner)
        if (targetFile != null) {
            targetFile.writeText(markdownTextArea.text, Charsets.UTF_8)
            statusLabel.text = "Saved to ${targetFile.absolutePath}"
        }
    }
}
