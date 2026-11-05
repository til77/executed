package ch.schmidolin.executed.ui

import ch.schmidolin.executed.model.*
import ch.schmidolin.executed.parser.HttpFileParser
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.stage.Window

class RequestEditorDialog(
    owner: Window?,
    private val originalSpec: HttpRequestSpec
) : Dialog<HttpRequestSpec>() {

    class EditableRow(key: String, value: String, enabled: Boolean = true) {
        val keyProperty = SimpleStringProperty(key)
        val valueProperty = SimpleStringProperty(value)
        val enabledProperty = SimpleBooleanProperty(enabled)

        fun toKeyValueItem() = KeyValueItem(
            key = keyProperty.get().orEmpty().trim(),
            value = valueProperty.get().orEmpty().trim(),
            enabled = enabledProperty.get()
        )
    }

    private val nameField = TextField(originalSpec.name)
    private val methodCombo = ComboBox(FXCollections.observableArrayList(HttpMethod.entries))
    private val baseUrlField = TextField(originalSpec.baseUrlWithoutQuery())
    private val fullUrlPreview = TextField()

    private val queryRows: ObservableList<EditableRow> = FXCollections.observableArrayList(
        originalSpec.queryParams.map { EditableRow(it.key, it.value, it.enabled) }
    )
    private val headerRows: ObservableList<EditableRow> = FXCollections.observableArrayList(
        originalSpec.headers.map { EditableRow(it.key, it.value, it.enabled) }
    )

    // Auth controls
    private val authTypeCombo = ComboBox(FXCollections.observableArrayList(AuthType.entries))
    private val basicUserField = TextField(originalSpec.auth.username)
    private val basicPassField = PasswordField().apply { text = originalSpec.auth.password }
    private val basicPassTextVisible = TextField(originalSpec.auth.password)
    private val showPassCheck = CheckBox("Show password")
    private val bearerTokenField = TextField(originalSpec.auth.token)
    private val apiKeyNameField = TextField(originalSpec.auth.apiKeyName)
    private val apiKeyValueField = TextField(originalSpec.auth.apiKeyValue)
    private val apiKeyLocationCombo = ComboBox(FXCollections.observableArrayList("Header", "Query Parameter"))

    // Body controls
    private val bodyArea = TextArea(originalSpec.body)
    private val bodyFileField = TextField(originalSpec.bodyFileReference.orEmpty())

    // Options / SSL / Redirects controls
    private val followRedirectsCheck = CheckBox("Follow HTTP Redirects (3xx)").apply {
        isSelected = originalSpec.options.followRedirects
    }
    private val verifySslCheck = CheckBox("Verify SSL/TLS Server Certificates (uncheck for self-signed / insecure)").apply {
        isSelected = originalSpec.options.verifySsl
    }
    private val noCookieJarCheck = CheckBox("Disable Cookie Jar (@no-cookie-jar)").apply {
        isSelected = originalSpec.options.noCookieJar
    }
    private val timeoutSpinner = Spinner<Int>(1, 600, (originalSpec.options.timeoutMillis / 1000L).toInt().coerceIn(1, 600), 5).apply {
        isEditable = true
    }
    private val httpVersionCombo = ComboBox(FXCollections.observableArrayList("Default (Auto)", "HTTP/1.1", "HTTP/2"))
    internal val userAgentCombo = ComboBox(FXCollections.observableArrayList(COMMON_USER_AGENTS)).apply {
        isEditable = true
        prefWidth = 360.0
        maxWidth = Double.MAX_VALUE
        editor.promptText = "Default (Executed-HTTP-Client/1.0) or custom User-Agent"
    }
    private val saveToFileField = TextField(originalSpec.options.saveToFile.orEmpty())
    private val overwriteFileCheck = CheckBox("Force overwrite existing file (>>!)").apply {
        isSelected = originalSpec.options.forceOverwriteFile
    }
    private var syncingUserAgent = false

    init {
        title = "Edit HTTP Request — ${originalSpec.displayTitle}"
        headerText = "Configure URL, Query Parameters, Headers, Authentication, Body & Connection Options"
        initOwner(owner)
        isResizable = true

        owner?.scene?.stylesheets?.let { dialogPane.stylesheets.addAll(it) }
        (dialogPane.scene?.window as? Stage)?.let { stage ->
            (owner as? Stage)?.icons?.let { stage.icons.setAll(it) }
        }

        methodCombo.value = originalSpec.method
        authTypeCombo.value = originalSpec.auth.type
        apiKeyLocationCombo.value = if (originalSpec.auth.apiKeyInQuery) "Query Parameter" else "Header"
        httpVersionCombo.value = originalSpec.httpVersion ?: "Default (Auto)"
        val initialUserAgent = extractUserAgent(originalSpec.headers)
        userAgentCombo.value = initialUserAgent
        userAgentCombo.editor.text = initialUserAgent

        basicPassField.textProperty().bindBidirectional(basicPassTextVisible.textProperty())

        val root = VBox(12.0).apply {
            padding = Insets(14.0)
            prefWidth = 760.0
            prefHeight = 560.0
        }

        // Top Request Name & Method + Base URL Bar
        val nameBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            val lbl = Label("Request Name:").apply { minWidth = 105.0 }
            nameField.promptText = "Optional descriptive title (e.g. Fetch User Profile)"
            HBox.setHgrow(nameField, Priority.ALWAYS)
            children.addAll(lbl, nameField)
        }

        val urlBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            methodCombo.prefWidth = 115.0
            baseUrlField.promptText = "https://api.example.com/v1/resource"
            HBox.setHgrow(baseUrlField, Priority.ALWAYS)
            children.addAll(Label("Endpoint:"), methodCombo, baseUrlField)
        }

        val previewBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            val lbl = Label("Effective URL:").apply { minWidth = 105.0 }
            fullUrlPreview.isEditable = false
            fullUrlPreview.style = "-fx-opacity: 0.88; -fx-font-family: monospace;"
            HBox.setHgrow(fullUrlPreview, Priority.ALWAYS)
            children.addAll(lbl, fullUrlPreview)
        }

        // Tabs for Query Params, Headers, Auth, Body, Options
        val tabPane = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val queryTab = Tab("Query Params (${queryRows.size})", buildKeyValueEditor(queryRows, "Parameter Name", "Parameter Value") {
            updateUrlPreview()
        })
        val headersTab = Tab("Headers (${headerRows.size})", buildKeyValueEditor(headerRows, "Header Name", "Header Value") {
            syncUserAgentComboFromHeaders()
        })
        val authTab = Tab("Authentication", buildAuthPane())
        val bodyTab = Tab("Request Body", buildBodyPane())
        val optionsTab = Tab("Redirects, SSL & Options", buildOptionsPane())

        tabPane.tabs.addAll(queryTab, headersTab, authTab, bodyTab, optionsTab)

        userAgentCombo.valueProperty().addListener { _, _, newVal ->
            if (newVal != null && userAgentCombo.editor.text != newVal) {
                userAgentCombo.editor.text = newVal
            }
            syncHeadersFromUserAgentCombo()
        }
        userAgentCombo.editor.textProperty().addListener { _, _, _ ->
            syncHeadersFromUserAgentCombo()
        }

        baseUrlField.textProperty().addListener { _, _, _ -> updateUrlPreview() }
        queryRows.addListener(ListChangeListener {
            queryTab.text = "Query Params (${queryRows.size})"
            updateUrlPreview()
        })
        headerRows.addListener(ListChangeListener {
            headersTab.text = "Headers (${headerRows.size})"
            syncUserAgentComboFromHeaders()
        })
        updateUrlPreview()

        root.children.addAll(nameBox, urlBox, previewBox, tabPane)
        dialogPane.content = root

        val saveBtnType = ButtonType("Apply to .http Editor", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(saveBtnType, ButtonType.CANCEL)

        val saveBtn = dialogPane.lookupButton(saveBtnType) as? Button
        saveBtn?.styleClass?.add("primary-button")

        setResultConverter { buttonType ->
            if (buttonType == saveBtnType) {
                buildUpdatedSpec(originalSpec)
            } else {
                null
            }
        }
    }

    private fun buildKeyValueEditor(
        rows: ObservableList<EditableRow>,
        keyHeader: String,
        valueHeader: String,
        onChanged: () -> Unit
    ): VBox {
        val table = TableView(rows).apply {
            isEditable = true
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val enabledCol = TableColumn<EditableRow, Boolean>("On").apply {
            cellValueFactory = javafx.util.Callback { it.value.enabledProperty }
            cellFactory = CheckBoxTableCell.forTableColumn(this)
            isEditable = true
            minWidth = 55.0
            maxWidth = 65.0
        }

        val keyCol = TableColumn<EditableRow, String>(keyHeader).apply {
            cellValueFactory = javafx.util.Callback { it.value.keyProperty }
            cellFactory = TextFieldTableCell.forTableColumn()
            isEditable = true
            setOnEditCommit { event ->
                event.rowValue.keyProperty.set(event.newValue)
                onChanged()
            }
        }

        val valCol = TableColumn<EditableRow, String>(valueHeader).apply {
            cellValueFactory = javafx.util.Callback { it.value.valueProperty }
            cellFactory = TextFieldTableCell.forTableColumn()
            isEditable = true
            setOnEditCommit { event ->
                event.rowValue.valueProperty.set(event.newValue)
                onChanged()
            }
        }

        table.columns.addAll(enabledCol, keyCol, valCol)

        rows.forEach { row ->
            row.keyProperty.addListener { _, _, _ -> onChanged() }
            row.valueProperty.addListener { _, _, _ -> onChanged() }
            row.enabledProperty.addListener { _, _, _ -> onChanged() }
        }

        val keyInput = TextField().apply { promptText = keyHeader; HBox.setHgrow(this, Priority.ALWAYS) }
        val valInput = TextField().apply { promptText = valueHeader; HBox.setHgrow(this, Priority.ALWAYS) }
        val addBtn = Button("+ Add").apply {
            styleClass.add("action-button")
            setOnAction {
                val k = keyInput.text.trim()
                val v = valInput.text.trim()
                val newRow = EditableRow(k.ifEmpty { "param_${rows.size + 1}" }, v, true)
                newRow.keyProperty.addListener { _, _, _ -> onChanged() }
                newRow.valueProperty.addListener { _, _, _ -> onChanged() }
                newRow.enabledProperty.addListener { _, _, _ -> onChanged() }
                rows.add(newRow)
                keyInput.clear()
                valInput.clear()
                onChanged()
            }
        }
        val removeBtn = Button("Remove Selected").apply {
            styleClass.add("action-button")
            setOnAction {
                val sel = table.selectionModel.selectedItem
                if (sel != null) {
                    rows.remove(sel)
                    onChanged()
                }
            }
        }

        val quickHeaderBox = HBox(8.0, keyInput, valInput, addBtn, removeBtn).apply {
            alignment = Pos.CENTER_LEFT
        }

        return VBox(8.0, Label("Double-click cells to edit existing values or add new rows below:"), table, quickHeaderBox).apply {
            padding = Insets(10.0)
        }
    }

    private fun buildAuthPane(): VBox {
        val container = VBox(14.0).apply { padding = Insets(14.0) }

        val typeRow = HBox(12.0, Label("Authentication Type:"), authTypeCombo).apply {
            alignment = Pos.CENTER_LEFT
        }

        val detailsBox = StackPane().apply {
            alignment = Pos.TOP_LEFT
        }

        val nonePane = VBox(8.0, Label("No authentication header or query parameter will be added automatically."))

        val basicPane = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            add(Label("Username:"), 0, 0)
            add(basicUserField.apply { prefWidth = 340.0; promptText = "Username or {{variable}}" }, 1, 0)
            add(Label("Password:"), 0, 1)
            val passStack = StackPane(basicPassField, basicPassTextVisible.apply { isVisible = false })
            showPassCheck.selectedProperty().addListener { _, _, show ->
                basicPassTextVisible.isVisible = show
                basicPassField.isVisible = !show
            }
            add(passStack, 1, 1)
            add(showPassCheck, 1, 2)
        }

        val bearerPane = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            add(Label("Bearer Token:"), 0, 0)
            add(bearerTokenField.apply { prefWidth = 420.0; promptText = "JWT / OAuth2 token or {{apiToken}}" }, 1, 0)
        }

        val apiKeyPane = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            add(Label("Key Name:"), 0, 0)
            add(apiKeyNameField.apply { prefWidth = 320.0; promptText = "X-API-Key or api_key" }, 1, 0)
            add(Label("Key Value:"), 0, 1)
            add(apiKeyValueField.apply { prefWidth = 320.0; promptText = "Secret key or {{apiKey}}" }, 1, 1)
            add(Label("Send In:"), 0, 2)
            add(apiKeyLocationCombo, 1, 2)
        }

        fun updateVisibleAuthPane(type: AuthType) {
            detailsBox.children.setAll(
                when (type) {
                    AuthType.NONE -> nonePane
                    AuthType.BASIC -> basicPane
                    AuthType.BEARER -> bearerPane
                    AuthType.API_KEY -> apiKeyPane
                }
            )
            updateUrlPreview()
        }

        authTypeCombo.valueProperty().addListener { _, _, newType ->
            if (newType != null) updateVisibleAuthPane(newType)
        }
        apiKeyLocationCombo.valueProperty().addListener { _, _, _ -> updateUrlPreview() }
        apiKeyNameField.textProperty().addListener { _, _, _ -> updateUrlPreview() }
        apiKeyValueField.textProperty().addListener { _, _, _ -> updateUrlPreview() }

        updateVisibleAuthPane(authTypeCombo.value ?: AuthType.NONE)

        container.children.addAll(typeRow, Separator(), detailsBox)
        return container
    }

    private fun buildBodyPane(): VBox {
        bodyArea.promptText = "{\n  \"key\": \"value\"\n}"
        bodyArea.style = "-fx-font-family: monospace;"
        VBox.setVgrow(bodyArea, Priority.ALWAYS)

        val presetBar = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            val jsonBtn = Button("Set Content-Type: application/json").apply {
                styleClass.add("action-button")
                setOnAction { setOrUpdateHeader("Content-Type", "application/json") }
            }
            val formBtn = Button("Set Content-Type: form-urlencoded").apply {
                styleClass.add("action-button")
                setOnAction { setOrUpdateHeader("Content-Type", "application/x-www-form-urlencoded") }
            }
            val xmlBtn = Button("Set Content-Type: application/xml").apply {
                styleClass.add("action-button")
                setOnAction { setOrUpdateHeader("Content-Type", "application/xml") }
            }
            children.addAll(jsonBtn, formBtn, xmlBtn)
        }

        val fileRefBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            bodyFileField.promptText = "Optional file path for '< ./payload.json' body input"
            HBox.setHgrow(bodyFileField, Priority.ALWAYS)
            children.addAll(Label("Body from file (<):"), bodyFileField)
        }

        return VBox(10.0, presetBar, bodyArea, fileRefBox).apply {
            padding = Insets(10.0)
        }
    }

    private fun buildOptionsPane(): VBox {
        val grid = GridPane().apply {
            hgap = 14.0
            vgap = 12.0
            padding = Insets(14.0)
        }

        grid.add(Label("Redirect Behavior:"), 0, 0)
        grid.add(followRedirectsCheck, 1, 0)

        grid.add(Label("TLS / Certificates:"), 0, 1)
        grid.add(verifySslCheck, 1, 1)

        grid.add(Label("Cookies:"), 0, 2)
        grid.add(noCookieJarCheck, 1, 2)

        grid.add(Label("Request Timeout (sec):"), 0, 3)
        grid.add(timeoutSpinner, 1, 3)

        grid.add(Label("HTTP Version:"), 0, 4)
        grid.add(httpVersionCombo, 1, 4)

        grid.add(Label("User-Agent:"), 0, 5)
        grid.add(userAgentCombo, 1, 5)
        GridPane.setHgrow(userAgentCombo, Priority.ALWAYS)

        grid.add(Label("Save Response (>>):"), 0, 6)
        saveToFileField.promptText = "Optional output path (e.g. ./output/custom-response.bin)"
        saveToFileField.prefWidth = 360.0
        grid.add(saveToFileField, 1, 6)

        grid.add(Label("Overwrite Mode:"), 0, 7)
        grid.add(overwriteFileCheck, 1, 7)

        return VBox(10.0, grid)
    }

    private fun currentUserAgentText(): String {
        val editorText = userAgentCombo.editor?.text
        return (editorText ?: userAgentCombo.value.orEmpty()).trim()
    }

    private fun syncHeadersFromUserAgentCombo() {
        if (syncingUserAgent) return
        syncingUserAgent = true
        try {
            val ua = currentUserAgentText()
            val existing = headerRows.find { it.keyProperty.get().equals("User-Agent", ignoreCase = true) }
            if (ua.isEmpty()) {
                if (existing != null) {
                    headerRows.remove(existing)
                }
            } else {
                if (existing != null) {
                    if (existing.valueProperty.get() != ua) {
                        existing.valueProperty.set(ua)
                    }
                    if (!existing.enabledProperty.get()) {
                        existing.enabledProperty.set(true)
                    }
                } else {
                    val newRow = EditableRow("User-Agent", ua, true)
                    newRow.keyProperty.addListener { _, _, _ -> syncUserAgentComboFromHeaders() }
                    newRow.valueProperty.addListener { _, _, _ -> syncUserAgentComboFromHeaders() }
                    newRow.enabledProperty.addListener { _, _, _ -> syncUserAgentComboFromHeaders() }
                    headerRows.add(newRow)
                }
            }
        } finally {
            syncingUserAgent = false
        }
    }

    private fun syncUserAgentComboFromHeaders() {
        if (syncingUserAgent) return
        syncingUserAgent = true
        try {
            val uaRow = headerRows.find { it.enabledProperty.get() && it.keyProperty.get().equals("User-Agent", ignoreCase = true) }
            val newUa = uaRow?.valueProperty?.get()?.trim().orEmpty()
            if (userAgentCombo.editor.text != newUa) {
                userAgentCombo.editor.text = newUa
            }
            if (userAgentCombo.value != newUa) {
                userAgentCombo.value = newUa
            }
        } finally {
            syncingUserAgent = false
        }
    }

    private fun setOrUpdateHeader(key: String, value: String) {
        val existing = headerRows.find { it.keyProperty.get().equals(key, ignoreCase = true) }
        if (existing != null) {
            existing.valueProperty.set(value)
            existing.enabledProperty.set(true)
        } else {
            headerRows.add(EditableRow(key, value, true))
        }
    }

    private fun updateUrlPreview() {
        val params = queryRows.map { it.toKeyValueItem() }.toMutableList()
        if (authTypeCombo.value == AuthType.API_KEY && apiKeyLocationCombo.value == "Query Parameter") {
            val k = apiKeyNameField.text.trim()
            val v = apiKeyValueField.text.trim()
            if (k.isNotEmpty()) {
                params.removeAll { it.key.equals(k, ignoreCase = true) }
                params.add(KeyValueItem(k, v, true))
            }
        }
        fullUrlPreview.text = HttpFileParser.buildUrlWithQueryParams(baseUrlField.text.trim(), params)
    }

    fun buildUpdatedSpec(original: HttpRequestSpec = originalSpec): HttpRequestSpec {
        syncHeadersFromUserAgentCombo()
        val params = queryRows.map { it.toKeyValueItem() }.filter { it.key.isNotBlank() }.toMutableList()
        val headers = headerRows.map { it.toKeyValueItem() }.filter { it.key.isNotBlank() }.toMutableList()
        applyUserAgentHeader(headers, currentUserAgentText())

        val auth = AuthConfig(
            type = authTypeCombo.value ?: AuthType.NONE,
            username = basicUserField.text.trim(),
            password = basicPassField.text,
            token = bearerTokenField.text.trim(),
            apiKeyName = apiKeyNameField.text.trim(),
            apiKeyValue = apiKeyValueField.text.trim(),
            apiKeyInQuery = apiKeyLocationCombo.value == "Query Parameter"
        )

        val effectiveUrl = HttpFileParser.buildUrlWithQueryParams(baseUrlField.text.trim(), params)
        val timeoutSec = timeoutSpinner.value ?: 30
        val httpVer = httpVersionCombo.value?.takeIf { it != "Default (Auto)" }

        val options = RequestOptions(
            followRedirects = followRedirectsCheck.isSelected,
            verifySsl = verifySslCheck.isSelected,
            timeoutMillis = timeoutSec * 1000L,
            saveToFile = saveToFileField.text.trim().takeIf { it.isNotEmpty() },
            forceOverwriteFile = overwriteFileCheck.isSelected,
            noCookieJar = noCookieJarCheck.isSelected
        )

        return original.copy(
            name = nameField.text.trim(),
            method = methodCombo.value ?: HttpMethod.GET,
            rawUrl = effectiveUrl,
            httpVersion = httpVer,
            headers = headers,
            queryParams = params,
            auth = auth,
            body = bodyArea.text,
            bodyFileReference = bodyFileField.text.trim().takeIf { it.isNotEmpty() },
            options = options
        )
    }

    companion object {
        val COMMON_USER_AGENTS: List<String> = listOf(
            "Executed-HTTP-Client/1.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.7; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.81 Mobile Safari/537.36",
            "curl/8.10.1",
            "PostmanRuntime/7.42.0",
            "insomnia/10.1.1",
            "HTTPie/3.2.4",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        )

        fun extractUserAgent(headers: List<KeyValueItem>): String =
            headers.firstOrNull { it.enabled && it.key.equals("User-Agent", ignoreCase = true) }?.value.orEmpty()

        fun applyUserAgentHeader(headers: MutableList<KeyValueItem>, userAgent: String) {
            val trimmed = userAgent.trim()
            val idx = headers.indexOfFirst { it.key.equals("User-Agent", ignoreCase = true) }
            if (trimmed.isEmpty()) {
                if (idx >= 0) headers.removeAt(idx)
            } else if (idx >= 0) {
                headers[idx] = headers[idx].copy(value = trimmed, enabled = true)
            } else {
                headers.add(KeyValueItem("User-Agent", trimmed, true))
            }
        }
    }
}
