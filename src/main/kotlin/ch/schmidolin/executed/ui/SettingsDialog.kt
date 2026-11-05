package ch.schmidolin.executed.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import javafx.stage.Window
import java.io.File

class SettingsDialog(
    owner: Window?,
    initialOutputDirectory: File
) : Dialog<File>() {

    private val outputDirField = TextField(initialOutputDirectory.absolutePath).apply {
        prefWidth = 420.0
        style = "-fx-font-family: monospace;"
    }

    init {
        title = "Settings"
        headerText = "Configure application settings"
        initOwner(owner)
        isResizable = true

        owner?.scene?.stylesheets?.let { dialogPane.stylesheets.addAll(it) }
        (dialogPane.scene?.window as? Stage)?.let { stage ->
            (owner as? Stage)?.icons?.let { stage.icons.setAll(it) }
        }

        val browseBtn = Button("Browse...").apply {
            styleClass.add("action-button")
            setOnAction {
                val chooser = DirectoryChooser().apply {
                    title = "Select Output Folder"
                    val current = File(outputDirField.text.trim())
                    val startDir = when {
                        current.exists() && current.isDirectory -> current
                        current.parentFile?.exists() == true && current.parentFile.isDirectory -> current.parentFile
                        initialOutputDirectory.exists() && initialOutputDirectory.isDirectory -> initialOutputDirectory
                        else -> File(System.getProperty("user.dir"))
                    }
                    if (startDir.exists() && startDir.isDirectory) {
                        initialDirectory = startDir
                    }
                }
                val selected = chooser.showDialog(dialogPane.scene?.window ?: owner)
                if (selected != null) {
                    outputDirField.text = selected.absolutePath
                }
            }
        }

        val pathBox = HBox(8.0, outputDirField, browseBtn).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(outputDirField, Priority.ALWAYS)
        }

        val grid = GridPane().apply {
            hgap = 14.0
            vgap = 10.0
            padding = Insets(14.0)
            styleClass.add("timing-card")
        }

        grid.add(Label("Output Directory:").apply { style = "-fx-font-weight: bold;" }, 0, 0)
        grid.add(pathBox, 1, 0)
        grid.add(
            Label("Folder where binary responses and saved outputs are stored").apply {
                style = "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;"
            },
            1,
            1
        )
        GridPane.setHgrow(pathBox, Priority.ALWAYS)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val okBtn = dialogPane.lookupButton(ButtonType.OK) as? Button
        okBtn?.styleClass?.add("primary-button")
        outputDirField.textProperty().addListener { _, _, newValue ->
            okBtn?.isDisable = newValue.isNullOrBlank()
        }

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val pathText = outputDirField.text.trim()
                if (pathText.isNotEmpty()) File(pathText) else initialOutputDirectory
            } else {
                null
            }
        }
    }
}
