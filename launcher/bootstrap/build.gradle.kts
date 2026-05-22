// Pure-JVM bootstrap module.
plugins {
    `java-library`
}

java {
    toolchain {
        // Match the FCL JRE 25 target. This is JVM, not ART.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // compileOnly: the JVM that runs our bootstrap will have getdown-pro.jar
    // on its classpath (we ship it as an asset and add it explicitly).
    compileOnly(files("$rootDir/../refs/sk-desktop-linux-install/getdown-pro.jar"))

    // Compile-only against the AngelAuraMC Android LWJGL jars so we can write
    // GLFW shadows that satisfy SK's expectations.
    val lwjglZip = file("$rootDir/app/src/main/assets/lwjgl/lwjgl-3.4.1-android-modules.zip")
    if (!lwjglZip.exists()) {
        // Fail loudly: a silent skip here lets GLFW.java compile errors blame
        // the source instead of the missing classpath.
        throw GradleException(
            "Missing LWJGL modules zip at $lwjglZip. " +
            "Run ../scripts/build-lwjgl3-android.sh then ../scripts/stage-launcher-assets.sh."
        )
    }
    val extracted = layout.buildDirectory.dir("lwjgl-classpath").get().asFile
    val needsExtract = !extracted.exists() ||
        (extracted.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L) < lwjglZip.lastModified()
    if (needsExtract) {
        extracted.deleteRecursively()
        extracted.mkdirs()
        copy {
            from(zipTree(lwjglZip)) { include("**/lwjgl.jar", "**/lwjgl-glfw.jar") }
            into(extracted)
            eachFile { path = name }            // flatten
            includeEmptyDirs = false
        }
    }
    compileOnly(fileTree(extracted) { include("lwjgl.jar", "lwjgl-glfw.jar") })
}

tasks.named<Jar>("jar") {
    archiveFileName.set("sk-bootstrap.jar")
}
