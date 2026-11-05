package ch.schmidolin.executed

import ch.schmidolin.executed.ui.MainView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import java.awt.Taskbar
import java.awt.Toolkit
import java.io.File
import java.net.URL
import javax.imageio.ImageIO

class ExecutedApp : Application() {

    private var mainView: MainView? = null

    override fun start(primaryStage: Stage) {
        primaryStage.title = "Executed"

        configureApplicationIcons(primaryStage)

        val view = MainView(primaryStage)
        mainView = view
        val scene = Scene(view, 1360.0, 840.0)
        javaClass.getResource("/styles.css")?.toExternalForm()?.let {
            scene.stylesheets.add(it)
        }

        primaryStage.scene = scene
        primaryStage.minWidth = 960.0
        primaryStage.minHeight = 620.0
        view.restoreWindowState()
        primaryStage.setOnCloseRequest {
            view.shutdown()
        }
        primaryStage.show()
        view.onApplicationShown()
    }

    override fun stop() {
        mainView?.shutdown()
        super.stop()
    }

    companion object {
        const val APP_WM_CLASS = "ch.schmidolin.executed.ExecutedApp"

        val ICON_RESOURCE_PATHS = listOf(
            "/icons/executed-32.png",
            "/icons/executed-64.png",
            "/icons/executed-128.png",
            "/icons/executed-256.png",
            "/icons/executed.png"
        )

        fun loadStageIcons(): List<Image> {
            return ICON_RESOURCE_PATHS.mapNotNull { path ->
                ExecutedApp::class.java.getResourceAsStream(path)?.use { stream ->
                    Image(stream)
                }
            }
        }

        fun configureApplicationIcons(stage: Stage) {
            ensureLinuxDesktopEntryInstalled()
            val fxIcons = loadStageIcons()
            stage.icons.setAll(fxIcons)

            try {
                if (Taskbar.isTaskbarSupported()) {
                    val taskbar = Taskbar.getTaskbar()
                    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                        ExecutedApp::class.java.getResourceAsStream("/icons/executed-256.png")?.use { stream ->
                            val awtImg = ImageIO.read(stream)
                            if (awtImg != null) {
                                taskbar.iconImage = awtImg
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        fun configureWindowManagerAppName() {
            ensureLinuxDesktopEntryInstalled()
            try {
                System.setProperty("jdk.gtk.title", APP_WM_CLASS)
                val toolkit = Toolkit.getDefaultToolkit()
                val awtAppClassNameField = toolkit.javaClass.getDeclaredField("awtAppClassName")
                awtAppClassNameField.isAccessible = true
                awtAppClassNameField.set(toolkit, APP_WM_CLASS)
            } catch (_: Throwable) {
            }
        }

        /**
         * On Linux / Wayland / GNOME / KDE window managers, the Alt-Tab switcher and dock resolve
         * top-level window icons by matching the window's `WM_CLASS` / `app_id`
         * (`ch.schmidolin.executed.ExecutedApp` set by JavaFX Glass GTK) against a `.desktop` entry in
         * `~/.local/share/applications/`. Installing the icon and `.desktop` entries ensures
         * Alt-Tab displays the application icon on both X11 and Wayland window managers.
         */
        fun ensureLinuxDesktopEntryInstalled() {
            val osName = System.getProperty("os.name")?.lowercase().orEmpty()
            if (!osName.contains("linux")) return

            try {
                val userHome = System.getProperty("user.home") ?: return
                val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: "$userHome/.local/share"

                val iconsBaseDir = File(dataHome, "icons/hicolor")
                val sizeMapping = listOf(
                    32 to "/icons/executed-32.png",
                    64 to "/icons/executed-64.png",
                    128 to "/icons/executed-128.png",
                    256 to "/icons/executed-256.png",
                    512 to "/icons/executed.png"
                )

                var iconPath256: File? = null
                for ((size, resourcePath) in sizeMapping) {
                    val bytes = ExecutedApp::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                        ?: continue
                    val targetDir = File(iconsBaseDir, "${size}x${size}/apps").apply { mkdirs() }
                    for (iconName in listOf("executed.png", "$APP_WM_CLASS.png")) {
                        val iconFile = File(targetDir, iconName)
                        if (!iconFile.exists() || !iconFile.readBytes().contentEquals(bytes)) {
                            iconFile.writeBytes(bytes)
                        }
                        if (size == 256 && iconName == "executed.png") {
                            iconPath256 = iconFile
                        }
                    }
                }

                val iconRef = iconPath256?.absolutePath ?: "executed"
                val appsDir = File(dataHome, "applications").apply { mkdirs() }

                val desktopFileNames = listOf(
                    "$APP_WM_CLASS.desktop",
                    "executed.desktop"
                )

                val jarPath = resolveJarPath()
                val desktopContent = """
                    [Desktop Entry]
                    Version=1.0
                    Type=Application
                    Name=Executed
                    Comment=HTTP Request Client & .http Runner
                    Exec=java -jar "$jarPath"
                    Icon=$iconRef
                    StartupWMClass=$APP_WM_CLASS
                    StartupNotify=true
                    Terminal=false
                    Categories=Development;
                """.trimIndent() + "\n"

                for (desktopName in desktopFileNames) {
                    val desktopFile = File(appsDir, desktopName)
                    if (!desktopFile.exists() || desktopFile.readText() != desktopContent) {
                        desktopFile.writeText(desktopContent)
                    }
                }
                appsDir.setLastModified(System.currentTimeMillis())
            } catch (_: Throwable) {
            }
        }

        internal fun resolveJarPath(
            codeSourceLocation: URL? = ExecutedApp::class.java.protectionDomain?.codeSource?.location
        ): String {
            val codeSourceFile = runCatching {
                codeSourceLocation?.toURI()?.let(::File)
            }.getOrNull()

            if (codeSourceFile != null && codeSourceFile.extension.equals("jar", ignoreCase = true)) {
                return codeSourceFile.absolutePath
            }
            return File(System.getProperty("user.dir"), "build/libs/Executed.jar").absolutePath
        }
    }
}

object Launcher {
    @JvmStatic
    fun main(args: Array<String>) {
        ExecutedApp.configureWindowManagerAppName()
        Application.launch(ExecutedApp::class.java, *args)
    }
}
