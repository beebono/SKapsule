// Pure-JVM bootstrap module.
plugins {
    `java-library`
}

java {
    toolchain {
        // Match the FCL JRE 25 target. This is JVM, not ART.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    // compileOnly: the JVM that runs our bootstrap will have getdown-pro.jar
    // on its classpath (we ship it as an asset and add it explicitly). Compile
    // against the committed asset copy — same jar we run against, and unlike the
    // gitignored refs/ tree it's present in a clean checkout (e.g. CI).
    compileOnly(files("$rootDir/app/src/main/assets/sk/getdown-pro.jar"))

    // NativeBridgePrompt implements co.frenchpress.CredentialPrompt. Now that this
    // module compiles under JDK 25 it can read the real shaded frenchpress.jar
    // (class v69) directly, so the old release-21 API stubs are gone. Compile
    // against the committed asset copy — same jar we ship and run against, present
    // in a clean checkout (CI) — mirroring the getdown-pro.jar wiring above. Keep it
    // compileOnly: at runtime NativeBridgePrompt binds to frenchpress on SK's
    // classloader, and frenchpress classes must never enter sk-bootstrap.jar.
    compileOnly(files("$rootDir/app/src/main/assets/frenchpress/frenchpress.jar"))

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

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(files("$rootDir/app/src/main/assets/sk/getdown-pro.jar"))
    testImplementation(files("$rootDir/app/src/main/assets/frenchpress/frenchpress.jar"))
}

tasks.named<Test>("test") {
    useJUnit()
}
