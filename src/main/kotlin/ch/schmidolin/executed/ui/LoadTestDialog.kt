package ch.schmidolin.executed.ui

import ch.schmidolin.executed.model.HttpRequestSpec
import ch.schmidolin.executed.model.LoadTestConfig
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window

class LoadTestDialog(
    owner: Window?,
    requestSpec: HttpRequestSpec,
    initialConfig: LoadTestConfig = LoadTestConfig()
) : Dialog<LoadTestConfig>() {

    private val totalRequestsSpinner = Spinner<Int>(1, 100_000, initialConfig.totalRequests, 10).apply {
        isEditable = true
        prefWidth = 150.0
    }

    private val parallelismSpinner = Spinner<Int>(1, 512, initialConfig.parallelism, 1).apply {
        isEditable = true
        prefWidth = 150.0
    }

    private val rampUpSpinner = Spinner<Double>(0.0, 600.0, initialConfig.rampUpSeconds, 1.0).apply {
        isEditable = true
        prefWidth = 150.0
    }

    private val delaySpinner = Spinner<Int>(0, 60_000, initialConfig.delayBetweenRequestsMs.toInt(), 50).apply {
        isEditable = true
        prefWidth = 150.0
    }

    private val reusePoolCheck = CheckBox("Reuse HTTP connection pool across worker threads (Keep-Alive)").apply {
        isSelected = initialConfig.reuseConnectionPool
    }

    private val summaryPreviewLabel = Label()

    init {
        title = "Run Load / Multi-Request Test — ${requestSpec.displayTitle}"
        headerText = "Configure request count, thread parallelism, and ramp-up speed for '${requestSpec.method.name} ${requestSpec.baseUrlWithoutQuery()}'"
        initOwner(owner)
        isResizable = true

        owner?.scene?.stylesheets?.let { dialogPane.stylesheets.addAll(it) }
        (dialogPane.scene?.window as? Stage)?.let { stage ->
            (owner as? Stage)?.icons?.let { stage.icons.setAll(it) }
        }

        val presetBar = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            val lbl = Label("Quick Presets:").apply { styleClass.add("section-header") }
            val p10 = Button("10 req / 2 threads").apply {
                styleClass.add("action-button")
                setOnAction { applyPreset(10, 2, 0.0, 0) }
            }
            val p50 = Button("50 req / 5 threads").apply {
                styleClass.add("action-button")
                setOnAction { applyPreset(50, 5, 1.0, 0) }
            }
            val p100 = Button("100 req / 10 threads").apply {
                styleClass.add("action-button")
                setOnAction { applyPreset(100, 10, 2.0, 0) }
            }
            val p500 = Button("500 req / 25 threads").apply {
                styleClass.add("action-button")
                setOnAction { applyPreset(500, 25, 5.0, 0) }
            }
            children.addAll(lbl, p10, p50, p100, p500)
        }

        val grid = GridPane().apply {
            hgap = 16.0
            vgap = 12.0
            padding = Insets(12.0)
            styleClass.add("timing-card")
        }

        grid.add(Label("Total Requests to Execute:").apply { style = "-fx-font-weight: bold;" }, 0, 0)
        grid.add(totalRequestsSpinner, 1, 0)
        grid.add(Label("Total number of HTTP calls to send (e.g. 100)").apply { style = "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;" }, 2, 0)

        grid.add(Label("Parallelism (Worker Threads):").apply { style = "-fx-font-weight: bold;" }, 0, 1)
        grid.add(parallelismSpinner, 1, 1)
        grid.add(Label("Number of concurrent threads executing requests simultaneously").apply { style = "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;" }, 2, 1)

        grid.add(Label("Ramp-Up Duration (seconds):").apply { style = "-fx-font-weight: bold;" }, 0, 2)
        grid.add(rampUpSpinner, 1, 2)
        grid.add(Label("Gradually start worker threads over this time (0 = all threads start immediately)").apply { style = "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;" }, 2, 2)

        grid.add(Label("Pacing Delay per Thread (ms):").apply { style = "-fx-font-weight: bold;" }, 0, 3)
        grid.add(delaySpinner, 1, 3)
        grid.add(Label("Optional pause between consecutive requests on each worker thread").apply { style = "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;" }, 2, 3)

        grid.add(Label("Connection Strategy:").apply { style = "-fx-font-weight: bold;" }, 0, 4)
        grid.add(reusePoolCheck, 1, 4, 2, 1)

        summaryPreviewLabel.style = "-fx-text-fill: #38bdf8; -fx-font-family: monospace; -fx-font-size: 12px;"
        val summaryCard = HBox(10.0, summaryPreviewLabel).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("timing-card")
        }

        totalRequestsSpinner.valueProperty().addListener { _, _, _ -> updateSummary() }
        parallelismSpinner.valueProperty().addListener { _, _, _ -> updateSummary() }
        rampUpSpinner.valueProperty().addListener { _, _, _ -> updateSummary() }
        delaySpinner.valueProperty().addListener { _, _, _ -> updateSummary() }
        updateSummary()

        val root = VBox(14.0, presetBar, grid, summaryCard).apply {
            padding = Insets(14.0)
            prefWidth = 680.0
        }
        dialogPane.content = root

        val startBtnType = ButtonType("▶▶ Start Load Test", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(startBtnType, ButtonType.CANCEL)

        val startBtn = dialogPane.lookupButton(startBtnType) as? Button
        startBtn?.styleClass?.add("primary-button")

        setResultConverter { buttonType ->
            if (buttonType == startBtnType) {
                commitSpinnerEditors()
                val total = (totalRequestsSpinner.value ?: 100).coerceAtLeast(1)
                val threads = (parallelismSpinner.value ?: 10).coerceIn(1, total)
                val rampUp = (rampUpSpinner.value ?: 0.0).coerceAtLeast(0.0)
                val delay = (delaySpinner.value ?: 0).toLong().coerceAtLeast(0L)
                LoadTestConfig(
                    totalRequests = total,
                    parallelism = threads,
                    rampUpSeconds = rampUp,
                    delayBetweenRequestsMs = delay,
                    reuseConnectionPool = reusePoolCheck.isSelected
                )
            } else {
                null
            }
        }
    }

    private fun applyPreset(total: Int, threads: Int, rampUp: Double, delayMs: Int) {
        totalRequestsSpinner.valueFactory.value = total
        parallelismSpinner.valueFactory.value = threads
        rampUpSpinner.valueFactory.value = rampUp
        delaySpinner.valueFactory.value = delayMs
        updateSummary()
    }

    private fun commitSpinnerEditors() {
        listOf(totalRequestsSpinner, parallelismSpinner, rampUpSpinner, delaySpinner).forEach { spinner ->
            try {
                spinner.increment(0)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateSummary() {
        val total = totalRequestsSpinner.value ?: 100
        val threads = (parallelismSpinner.value ?: 10).coerceAtMost(total)
        val rampUp = rampUpSpinner.value ?: 0.0
        val delay = delaySpinner.value ?: 0
        val reqPerThread = String.format("%.1f", total.toDouble() / threads.coerceAtLeast(1))
        val rampRate = if (rampUp > 0 && threads > 1) {
            String.format("%.1f threads/sec over %.1fs", threads / rampUp, rampUp)
        } else {
            "Instant start (0s ramp-up)"
        }
        summaryPreviewLabel.text =
            "Plan: $total requests across $threads thread(s) (~$reqPerThread req/thread)  |  Ramp-up: $rampRate  |  Delay: ${delay}ms"
    }
}
