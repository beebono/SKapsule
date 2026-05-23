# SKapsule

An unofficial Android (arm64) port of **Spiral Knights**.

SKapsule runs the *real* Spiral Knights desktop client on your phone or tablet. It
ships a custom JRE and a set of native libraries, then boots the game's own Java VM
on-device — the game code and assets are downloaded and patched from the official
servers at runtime (via getdown), exactly like the desktop client. Nothing about the
game itself is bundled in this repository.

> **Status: experimental / alpha.** The game boots, logs in, and is playable, but
> there are known rough edges (see [Known issues](#known-issues)). Expect bugs.

---

## Disclaimer

Spiral Knights is © SEGA / Grey Havens, LLC. **This is an unofficial, fan-made port
and is not affiliated with, endorsed by, or supported by SEGA or Grey Havens.**

- No Spiral Knights game code or art assets are included in this repository or in the
  APK. They are downloaded from the official servers at first launch, the same way the
  official desktop launcher works.
- You need your own Spiral Knights account (web or Steam) to play.
- This project exists to let people play a game they already own on hardware the
  official client doesn't target. Please support the game through official channels.

---

## Controls

Gameplay is **gamepad-first**. Connect a Bluetooth/USB controller for the actual
playing of the game.

- **Gamepad** — primary input for gameplay (movement, combat, menus).
- **Touch** — intended for menuing and pointer interaction, not core combat.
- **Keyboard** — used as needed for text entry (login, chat). An on-screen
  **Keyboard** button is provided for devices without a physical keyboard.

---

## Installing (players)

1. Grab the latest signed `app-release.apk` from the
   [Releases](../../releases) page.
2. Copy it to your arm64 Android device (Android 8.0 / API 26 or newer) and install,
   allowing installation from unknown sources if prompted.
3. Launch **SKapsule**. On first run it unpacks the bundled JRE and runtime, then
   downloads/patches the game from the official servers — this first launch takes a
   while and needs a network connection.
4. Choose **Play (Web)** or **Play (Steam)** and sign in with your own account.

Only `arm64-v8a` devices are supported (no 32-bit builds).

---

## Building from source (developers)

The APK is a *full from-source* build: every native component is compiled from its
submodule, staged into the launcher, and then the Android app is assembled. CI does
exactly this — see [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)
for the canonical, reproducible recipe.

### Prerequisites

| Tool | Version | Used for |
|------|---------|----------|
| Android SDK | compileSdk/targetSdk 35 | building the app |
| Android NDK | `30.0.14904198` | native libs (Clang 20; NDK 27's Clang 18 miscompiles OpenAL's C++20 ranges) |
| CMake | `3.22.1` | native build |
| JDK 8 | | LWJGL's Java-8 multi-release layer |
| JDK 21 | | Gradle / AGP (JDK 25 breaks Gradle here) |
| JDK 25 | | caciocavallo + frenchpress (`--release 25`) |
| ant, ninja, zip | | submodule build scripts |

Set `ANDROID_HOME`/`ANDROID_NDK_HOME` appropriately.

### 1. Clone with submodules

```bash
git clone --recurse-submodules https://github.com/beebono/sk-arm64.git
cd sk-arm64
```

### 2. Build the native components

These compile each submodule for `arm64-v8a` and drop outputs into `out/`:

```bash
./scripts/build-gl4es-android.sh
./scripts/build-openal-android.sh
./scripts/build-lwjgl3-android.sh        # JAVA_HOME -> JDK 25 (matches dev box), JAVA8_HOME -> JDK 8
./scripts/build-cacio-android.sh         # CACIO_JAVA_HOME -> JDK 25
./scripts/build-frenchpress-android.sh   # FRENCHPRESS_JAVA_HOME -> JDK 25
```

### 3. Stage assets into the launcher

```bash
./scripts/stage-launcher-assets.sh
```

### 4. Assemble the APK

```bash
cd launcher
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleRelease
```

The output lands in `launcher/app/build/outputs/apk/release/`. Without signing
configured (below) this is `app-release-unsigned.apk`.

### Signing (optional)

Release signing reads, in order, a gitignored `launcher/keystore.properties` then
environment variables. With neither present, `assembleRelease` still succeeds and
emits an *unsigned* APK. Provide `storeFile`/`storePassword`/`keyAlias`/`keyPassword`
(props) or `SK_KEYSTORE_FILE`/`SK_KEYSTORE_PASSWORD`/`SK_KEY_ALIAS`/`SK_KEY_PASSWORD`
(env) to produce a signed `app-release.apk`. See `keystore.properties.template`.

In CI, the keystore is supplied via the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD` repo secrets. A push of a `v*` tag with signing
secrets present publishes a GitHub Release with the signed APK attached.

---

## How it works

```
LauncherActivity ─ unpacks JRE + runtime, picks Web/Steam, launches GameActivity
        │
GameActivity (:game process) ─ starts the FCL JVM, sets up EGL/GLES + input
        │
native (sklauncher.c) ─ boots the JVM, calls into the bootstrap
        │
SkBootstrap ─ runs HeadlessGetdown (validates/patches the game), then invokes
              com.threerings.projectx.client.ProjectXApp in-process
```

The game is a standard desktop Java client, so the port supplies everything that
client expects on a platform Android lacks:

| Component | What it provides | Source |
|-----------|------------------|--------|
| **JRE 25** | the Java runtime the game runs on | bundled asset |
| **gl4es** | translates the game's OpenGL calls to OpenGL ES | [`gl4es`](https://github.com/beebono/gl4es) submodule |
| **openal-soft** | audio | [`openal-soft`](https://github.com/kcat/openal-soft) submodule |
| **LWJGL 3.4.1** | windowing / GL / input bindings (Android-native build) | [`lwjgl3`](https://github.com/AngelAuraMC/lwjgl3) submodule |
| **caciocavallo** | headless AWT bridge (the game uses AWT/Swing for some UI) | [`caciocavallo17`](https://github.com/AngelAuraMC/caciocavallo17) submodule |
| **frenchpress** | Steam login (SteamKit-style auth) | [`frenchpress`](https://github.com/beebono/frenchpress) submodule |
| **getdown** | downloads, verifies, and patches the game | bundled `getdown-pro.jar` |

A small `bootstrap` Java module (`SkBootstrap`, `HeadlessGetdown`) and the launcher's
Kotlin installers wire these together.

### Repository layout

```
launcher/          Android app (Kotlin) + bootstrap (Java) — the buildable project
  app/             the Android application module
  bootstrap/       SkBootstrap / HeadlessGetdown / GLFW shim, staged into the APK
scripts/           build-*-android.sh per native component + stage-launcher-assets.sh
gl4es/             submodule — GL → GLES translation
openal-soft/       submodule — audio
lwjgl3/            submodule — LWJGL Android build
caciocavallo17/    submodule — headless AWT
frenchpress/       submodule — Steam login
out/               native build outputs (generated, gitignored)
.github/workflows/ from-source CI + release automation
```

---

## Known issues

- **Character-shadow shader artifact.** Character shadows can render as a filled
  quad. The shader *link* bug is fixed, but a visual artifact persists from another
  cause. Workaround: set graphics quality to **Low**.
- **Experimental maturity.** Lifecycle/resume, input, and login paths work but
  haven't been hardened across the full range of devices and Android versions.
- **APK size.** The bundled runtime makes for a large (~90 MB) APK; it is untuned.

---

## References

These projects were consulted during development. They are not part of the build (the
repo's `refs/` working directory is gitignored), but they're credited here as the
shoulders this port stands on:

- [getdown](https://github.com/threerings/getdown) — Three Rings' application
  installer/patcher/launcher; SKapsule drives it headlessly to update the game.
- [clyde](https://github.com/threerings/clyde) — Three Rings' game tooling/runtime
  library used by the Spiral Knights client.
- [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) /
  [KnightLauncher-Android](https://github.com/AngelAuraMC) — related Android Java-game
  launchers that informed the JVM-on-Android approach (gl4es, LWJGL, cacio bridge).
- The Spiral Knights desktop Linux install layout — reference for the appdir/getdown
  layout SKapsule reproduces on-device.

---

## License

The original code in this repository (the launcher, bootstrap, and build scripts) is
licensed under the **MIT License**.

Bundled and submoduled third-party components retain their own licenses, including
MIT (gl4es), BSD (LWJGL, getdown, clyde), LGPL-2.x (OpenAL Soft, dynamically linked as
a separate `.so`), and GPL-2.0 with the Classpath Exception (caciocavallo). The LGPL's
dynamic-linking allowance and caciocavallo's Classpath Exception are what permit the
launcher's own code to be MIT-licensed while distributing those components alongside
it; each component's own `LICENSE`/`COPYING` file holds the authoritative terms.

Spiral Knights and all related game assets are the property of their respective
owners and are **not** covered by this license.
