# AGENTS.md / CLAUDE.md

This file provides guidance to Claude Code or other AI coding agents when working with code in this repository.

## What this is

An Android app (`moe.momokko.sillytavernapp`) that embeds the **SillyTavern 1.18.0** Node server inside the APK and
shows its web UI in a full-screen WebView. SillyTavern's `server.js` runs on a self-built `libnode.so` (nodejs-mobile,
Node 24) bound to `127.0.0.1:8616`; user data lives at `/storage/emulated/0/Documents/SillyTavern`. The README is the
authoritative build guide — read it before changing build/packaging.

## How it works

- A self-built `libnode.so` (nodejs-mobile, Node 24) runs SillyTavern's `server.js` on `127.0.0.1:8616` inside a
  foreground service (`NodeService`).
- A JNI bridge (`native-lib`) starts Node, sets `TMPDIR`/`HOME`/`NODE_ENV`, and `chdir`s into the writable project dir.
- `MainActivity` hosts the WebView, polls until the server is up, then loads `http://127.0.0.1:8616/`.
- The bundled Node project is extracted from assets to app-private storage once per APK update (`AssetExtractor`), so
  warm starts are fast.
- Node can only start once per process, so the app arms an idle shutdown (configurable) that kills the process;
  reopening is a fresh cold start. Shutdown is deferred while a generation is in flight (tracked by an injected WebView
  fetch/XHR busy counter).

## Project layout

```
app/
  CMakeLists.txt                  # builds native-lib, links the prebuilt libnode
  build.gradle.kts                # arm64-only, NDK/CMake, cleartext localhost
  libnode/                        # Node 24 runtime
  src/main/
    cpp/native-lib.cpp            # JNI: startNodeWithArguments / setEnv / chDir
    java/moe/momokko/sillytavernapp/
      NodeBridge.kt               # native method declarations
      AssetExtractor.kt           # extract bundled project once per APK update
      NodeService.kt              # foreground service running server.js
      MainActivity.kt             # WebView host, readiness poll, insets, idle shutdown
    assets/
      st-app-extension.js         # extension to manage app behavior
      webview-hooks.js            # fetch/XHR busy-counter injection
      nodejs-project/             # SillyTavern install
    res/xml/network_security_config.xml   # cleartext to 127.0.0.1 / localhost
```

## Two artifacts are gitignored and must be built locally

The build will not work from a fresh clone until both exist (see README Steps 1–2):

1. `app/libnode/` — self-built Node 24 runtime (`bin/arm64-v8a/libnode.so` + `include/node/`).
2. `app/src/main/assets/nodejs-project/` — the SillyTavern install. **`config.yaml` is the one tracked file** here;
   everything else is the npm-installed SillyTavern checkout. A plain `cp -R` of SillyTavern into this dir is safe
   because upstream has no root `config.yaml`.

**`libnode.so` MUST be built with ICU** (`--with-intl=small-icu`, not the nodejs-mobile default `none`). SillyTavern's
dependencies use Unicode regex property escapes (`/\p{Cc}/u`) that V8 rejects at module-compile time without ICU,
killing the server at boot with a `SyntaxError`.

## Build & run

```bash
./gradlew :app:assembleDebug          # build the APK
adb logcat -s SILLYTAVERN-NODE:*      # watch the embedded Node server logs
adb logcat -s SILLYTAVERN-EXTRACT:*   # watch asset extraction
```

- **arm64-v8a only.** Needs an arm64 device/emulator; NDK 29.0.14206865, CMake 3.22.1, build-tools/platform 36, Java 21,
  minSdk 26.
- The native build (`native-lib`) requires **C++20** — Node 24's V8 headers need it (`std::is_constant_evaluated`). It
  links the prebuilt `libnode` as an imported shared lib via `app/CMakeLists.txt`.
- `jniLibs.srcDir("libnode/bin")` packages `libnode.so`; `useLegacyPackaging = true` keeps it uncompressed/unstripped.
- First launch prompts for **All-Files access** (the `dataRoot` is on shared storage) — grant it or the server is
  useless.

## Architecture & invariants

The runtime is three cooperating layers; understand all three before changing startup/lifecycle:

- **`native-lib.cpp` (JNI bridge)** — exposes `startNodeWithArguments`, `setEnv`, `chDir` to Kotlin via `NodeBridge.kt`.
  It boots libnode in-process.
- **`NodeService.kt` (foreground service)** — sets `HOME`/`TMPDIR`/`NODE_ENV`, `chdir`s into the extracted project, and
  runs `server.js --configPath config.yaml` on a non-daemon thread. **Node can only initialise once per OS process** (
  libnode keeps global V8 state), so the service starts Node exactly once and never restarts it. `nodeStarted` guards
  this.
- **`MainActivity.kt` (WebView host)** — polls `http://127.0.0.1:8616/` until ready, then loads it; manages window
  insets; routes non-local links to a Custom Tab; injects the web-side scripts on every `onPageFinished` (
  `injectAppScripts()` → `webview-hooks.js` then `st-app-extension.js`); and owns the background lifecycle. On `onStop`
  it **always pauses the WebView** (`onPause`/`pauseTimers`) for battery once any in-flight generation finishes, and *
  *only kills the process** if the user-set idle timeout is `> 0` (see bridge below). Default timeout is **0 = never
  auto-exit**, so backgrounding is normally a fast pause/resume; a kill (when armed) means reopening is a fresh cold
  start. The "defer while busy" check polls a fetch/XHR busy counter (`window.__ST_BUSY`) injected by
  `assets/webview-hooks.js`.

- **`AssetExtractor.kt`** copies `assets/nodejs-project` into `filesDir/nodejs-project` (assets are read-only;
  SillyTavern must write into its own dir). Re-extraction is gated on the APK's `lastUpdateTime` stored in
  `SharedPreferences("ST_PREFS")` key `apk_last_update` — installing a new APK changes `lastUpdateTime`, which forces a
  re-extract of the bundled project.

Because "stop the server" = "kill the process," any feature touching server lifecycle must work with cold-start-only
semantics, not in-process restart.

## config.yaml (tracked, mobile-tuned)

`dataRoot` on shared storage, `port: 8616`, `listen: false` (127.0.0.1 only), `browserLaunch.enabled: false`,
`whitelistMode: true`, `securityOverride: false`, `enableServerPlugins: false`. Cleartext to localhost is allowed via
`res/xml/network_security_config.xml`.

## WebView ↔ native bridge (app-control-bridge)

SillyTavern (in the WebView) calls back into native controls through `window.STAndroid`, registered in `onCreate` via
`addJavascriptInterface(WebAppBridge(), "STAndroid")` **before** the URL loads. The `WebAppBridge` inner class exposes
three `@JavascriptInterface` methods:

- `getIdleTimeoutMinutes(): Int` / `setIdleTimeoutMinutes(min)` — read/write the auto-exit idle timeout in
  `SharedPreferences("ST_PREFS")` key `idle_timeout_min` (clamped `>= 0`, default `0` = never). Native prefs are the
  single source of truth; nothing is stored in ST settings.
- `notifyAiResponded(title, body)` — posts a heads-up notification on the `ai_reply` channel (`IMPORTANCE_HIGH`), tap
  reopens `MainActivity`. Silent no-op if `POST_NOTIFICATIONS` (Android 13+, requested in `onCreate`) is denied.

The web side is `assets/st-app-extension.js`: it polls for `window.SillyTavern.getContext()`, hooks
`eventTypes.GENERATION_ENDED` to fire `notifyAiResponded` only when `document.hidden`, and injects an "Auto-exit after
idle (minutes)" control into ST's Extensions panel wired to the timeout getter/setter. Every `STAndroid` access is
guarded so the script is inert in a plain browser.

**Security note:** `addJavascriptInterface` exposes the bridge to all JS in the WebView; this is acceptable only because
nothing but local `127.0.0.1` content is ever loaded and the surface is minimal (one clamped int + two display strings).
Keep it minimal.

Design/rationale (e.g. why pause is split from kill): `docs/superpowers/specs/2026-05-28-app-control-bridge-design.md`.
