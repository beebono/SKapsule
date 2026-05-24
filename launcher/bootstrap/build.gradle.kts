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

// Compile-only API stubs for the frenchpress types NativeBridgePrompt links
// against (the real jar is Java 25 / class v69, unreadable by this JDK 21
// compiler). Built into their own source set so they never enter sk-bootstrap.jar.
sourceSets {
    create("stubs")
}

dependencies {
    // compileOnly: the JVM that runs our bootstrap will have getdown-pro.jar
    // on its classpath (we ship it as an asset and add it explicitly). Compile
    // against the committed asset copy — same jar we run against, and unlike the
    // gitignored refs/ tree it's present in a clean checkout (e.g. CI).
    compileOnly(files("$rootDir/app/src/main/assets/sk/getdown-pro.jar"))

    // NativeBridgePrompt implements co.frenchpress.CredentialPrompt, but the
    // shaded frenchpress.jar is compiled for Java 25 (class v69) and this module
    // uses a JDK 21 compiler (v65) which can't read it. Java linkage is nominal,
    // so we compile against release-21 API STUBS of those two types (in
    // src/stubs); at runtime NativeBridgePrompt binds to the real classes on
    // SK's classloader. The stubs are compileOnly and excluded from the jar
    // (see the jar task) so they can't shadow the real frenchpress.
    compileOnly(sourceSets["stubs"].output)

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
    // Belt-and-suspenders: stubs are a separate source set so they aren't here
    // anyway, but never let a co.frenchpress class ship in sk-bootstrap.jar —
    // it's first on SK's classpath and would shadow the real frenchpress.
    exclude("co/frenchpress/**")
}
