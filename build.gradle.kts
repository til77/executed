import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "ch.schmidolin.executed"
version = "1.0.0"

repositories {
    mavenCentral()
}

val javafxVersion = "23.0.2"
val javafxModules = listOf("javafx-base", "javafx-graphics", "javafx-controls", "javafx-swing")
val os = OperatingSystem.current()
val hostJavafxPlatform = when {
    os.isLinux -> if (System.getProperty("os.arch").contains("aarch64")) "linux-aarch64" else "linux"
    os.isMacOsX -> if (System.getProperty("os.arch").contains("aarch64")) "mac-aarch64" else "mac"
    os.isWindows -> "win"
    else -> "linux"
}
val bundledJavafxPlatforms = listOf("linux", "win", "mac-aarch64")

val fatJarPlatforms: Configuration = configurations.create("fatJarPlatforms") {
    isTransitive = false
}

dependencies {
    for (module in javafxModules) {
        implementation("org.openjfx:$module:$javafxVersion:$hostJavafxPlatform")
        for (platform in bundledJavafxPlatforms) {
            fatJarPlatforms("org.openjfx:$module:$javafxVersion:$platform")
        }
    }

    implementation("org.fxmisc.richtext:richtextfx:0.11.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("ch.schmidolin.executed.Launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("Executed")
    archiveVersion.set("")
    manifest {
        attributes(
            "Main-Class" to "ch.schmidolin.executed.Launcher",
            "Implementation-Title" to "Executed",
            "Implementation-Version" to project.version
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Our own license/notice files go first so they win over same-named dependency files.
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.file("third-party-licenses")) {
        into("META-INF/third-party-licenses")
    }
    // Bundled platform jars come first so their natives win over the build host's.
    val jarInputs = fatJarPlatforms.files + configurations.runtimeClasspath.get().files
    from(jarInputs.map { if (it.isDirectory) it else zipTree(it) }) {
        // Dependency LICENSE/NOTICE files collide with each other; their content is
        // reproduced in THIRD-PARTY-NOTICES.md and META-INF/third-party-licenses.
        exclude(
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF",
            "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt"
        )
    }
}