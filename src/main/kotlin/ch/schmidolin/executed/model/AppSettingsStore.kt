package ch.schmidolin.executed.model

import java.io.File
import java.util.*

data class AppSettings(
    val outputDirectory: File,
    val lastWorkspaceDirectory: File?,
    val lastSelectedFile: File?,
    val windowX: Double? = null,
    val windowY: Double? = null,
    val windowWidth: Double? = null,
    val windowHeight: Double? = null,
    val windowMaximized: Boolean = false,
    val isFirstLaunch: Boolean = false
)

class AppSettingsStore(
    private val settingsFile: File = defaultSettingsFile()
) {

    fun load(
        defaultOutputDir: File = File(System.getProperty("user.dir"), "output"),
        defaultWorkspaceDir: File = resolveDefaultWorkspaceDirectory()
    ): AppSettings {
        val props = Properties()
        val fileExisted = settingsFile.exists() && settingsFile.isFile
        if (fileExisted) {
            try {
                settingsFile.inputStream().use { props.load(it) }
            } catch (_: Exception) {
            }
        }

        val outputDir = props.getProperty(KEY_OUTPUT_DIR)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: defaultOutputDir

        val savedWorkspaceDir = props.getProperty(KEY_LAST_WORKSPACE_DIR)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isDirectory }

        val workspaceDir = savedWorkspaceDir ?: defaultWorkspaceDir
        val isFirstLaunch = !fileExisted || savedWorkspaceDir == null

        val selectedFile = props.getProperty(KEY_LAST_SELECTED_FILE)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }

        val windowX = props.getProperty(KEY_WINDOW_X)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val windowY = props.getProperty(KEY_WINDOW_Y)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val windowWidth = props.getProperty(KEY_WINDOW_WIDTH)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val windowHeight = props.getProperty(KEY_WINDOW_HEIGHT)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val windowMaximized = props.getProperty(KEY_WINDOW_MAXIMIZED)?.toBooleanStrictOrNull() ?: false

        return AppSettings(
            outputDirectory = outputDir,
            lastWorkspaceDirectory = workspaceDir,
            lastSelectedFile = selectedFile,
            windowX = windowX,
            windowY = windowY,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            windowMaximized = windowMaximized,
            isFirstLaunch = isFirstLaunch
        )
    }

    fun save(settings: AppSettings) {
        try {
            settingsFile.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty(KEY_OUTPUT_DIR, settings.outputDirectory.absolutePath)
            settings.lastWorkspaceDirectory?.let {
                props.setProperty(KEY_LAST_WORKSPACE_DIR, it.absolutePath)
            }
            settings.lastSelectedFile?.let {
                props.setProperty(KEY_LAST_SELECTED_FILE, it.absolutePath)
            }
            settings.windowX?.takeIf { it.isFinite() }?.let {
                props.setProperty(KEY_WINDOW_X, it.toString())
            }
            settings.windowY?.takeIf { it.isFinite() }?.let {
                props.setProperty(KEY_WINDOW_Y, it.toString())
            }
            settings.windowWidth?.takeIf { it.isFinite() && it > 0.0 }?.let {
                props.setProperty(KEY_WINDOW_WIDTH, it.toString())
            }
            settings.windowHeight?.takeIf { it.isFinite() && it > 0.0 }?.let {
                props.setProperty(KEY_WINDOW_HEIGHT, it.toString())
            }
            props.setProperty(KEY_WINDOW_MAXIMIZED, settings.windowMaximized.toString())
            settingsFile.outputStream().use { out ->
                props.store(out, "Executed Application Settings")
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val KEY_OUTPUT_DIR = "outputDirectory"
        private const val KEY_LAST_WORKSPACE_DIR = "lastWorkspaceDirectory"
        private const val KEY_LAST_SELECTED_FILE = "lastSelectedFile"
        private const val KEY_WINDOW_X = "windowX"
        private const val KEY_WINDOW_Y = "windowY"
        private const val KEY_WINDOW_WIDTH = "windowWidth"
        private const val KEY_WINDOW_HEIGHT = "windowHeight"
        private const val KEY_WINDOW_MAXIMIZED = "windowMaximized"

        fun defaultSettingsFile(): File {
            val userHome = System.getProperty("user.home") ?: System.getProperty("user.dir")
            val configBase = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: File(userHome, ".config").absolutePath
            return File(configBase, "executed/settings.properties")
        }

        fun resolveDefaultWorkspaceDirectory(): File {
            val cwd = File(System.getProperty("user.dir"))
            return if (cwd.exists() && cwd.isDirectory) cwd else File(".")
        }
    }
}
